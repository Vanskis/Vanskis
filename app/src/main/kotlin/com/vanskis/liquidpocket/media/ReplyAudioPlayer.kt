package com.vanskis.liquidpocket.media

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Plays the model's speech as it streams: [enqueue] returns at once and a writer thread feeds a
 * streaming [AudioTrack]. [stop] drops what is queued and silences the track mid-chunk.
 *
 * The writer thread alone creates, writes to and releases the track; other threads only pause and
 * flush it (under [lock]), which also wakes a blocked write.
 */
class ReplyAudioPlayer {
    private class Chunk(val generation: Int, val sampleRate: Int, val samples: FloatArray)

    private val queue = LinkedBlockingQueue<Chunk>()
    /** Chunks enqueued and not yet written or dropped. */
    private val inFlight = AtomicInteger()
    /** Frames written to the track since it last started from an empty buffer. Writer thread writes, under [lock]. */
    private var framesWritten = 0L
    private val lock = Any()
    @Volatile private var generation = 0
    @Volatile private var released = false
    private var track: AudioTrack? = null
    /** The generation the track last played; a new one starts from an empty buffer. */
    private var trackGeneration = -1
    private var writer: Thread? = null

    /** Queues [samples] (mono float at [sampleRate]) behind what is already playing. */
    fun enqueue(samples: FloatArray, sampleRate: Int) {
        if (released || samples.isEmpty() || sampleRate <= 0) return
        synchronized(lock) {
            if (writer == null) writer = Thread(::writeLoop, "reply-audio").apply { isDaemon = true; start() }
        }
        inFlight.incrementAndGet()
        queue.put(Chunk(generation, sampleRate, samples))
    }

    /** Whether speech is still queued or playing; false once the last of it has left the speaker or [stop] ran. */
    val busy: Boolean
        get() = synchronized(lock) {
            if (inFlight.get() > 0) return@synchronized true
            val t = track ?: return@synchronized false
            trackGeneration == generation && runCatching { t.playbackHeadPosition.toLong() < framesWritten }.getOrDefault(false)
        }

    /** Stops playback now and forgets what is queued. */
    fun stop() {
        synchronized(lock) {
            generation++
            val dropped = ArrayList<Chunk>()
            inFlight.addAndGet(-queue.drainTo(dropped))
            track?.let {
                runCatching {
                    it.pause()
                    it.flush()
                }
            }
        }
    }

    fun release() {
        released = true
        stop()
        writer?.interrupt()
    }

    private fun writeLoop() {
        try {
            while (!released) {
                val chunk = queue.take()
                try {
                    val t = synchronized(lock) {
                        if (chunk.generation != generation) return@synchronized null
                        val current = track
                        trackFor(chunk.sampleRate).also {
                            // A write that raced stop() may have left old speech in the buffer.
                            if (trackGeneration != chunk.generation || it !== current) {
                                if (it.playState == AudioTrack.PLAYSTATE_PLAYING) it.pause()
                                it.flush()
                                trackGeneration = chunk.generation
                                framesWritten = 0
                            }
                            if (it.playState != AudioTrack.PLAYSTATE_PLAYING) it.play()
                        }
                    } ?: continue
                    var offset = 0
                    while (offset < chunk.samples.size && chunk.generation == generation) {
                        val n = t.write(chunk.samples, offset, chunk.samples.size - offset, AudioTrack.WRITE_BLOCKING)
                        if (n <= 0) break
                        offset += n
                        synchronized(lock) { framesWritten += n }
                    }
                } finally {
                    inFlight.decrementAndGet()
                }
            }
        } catch (_: InterruptedException) {
        } finally {
            synchronized(lock) {
                track?.release()
                track = null
            }
        }
    }

    /** Called with [lock] held, on the writer thread. */
    private fun trackFor(sampleRate: Int): AudioTrack {
        track?.takeIf { it.sampleRate == sampleRate }?.let { return it }
        track?.release()
        val minBytes = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            // About half a second: enough to ride out decode hiccups, short enough for Stop to feel instant.
            .setBufferSizeInBytes(maxOf(minBytes, sampleRate * 4 / 2))
            .build()
            .also { track = it }
    }
}
