package com.vanskis.liquidpocket.media

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission

/**
 * Records one mono float clip from the microphone at a given rate, on its own thread. Clips are cut
 * at [MAX_SECONDS]: a voice message is a question, not a dictation.
 */
class VoiceRecorder {
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    private var buffer = FloatArray(0)
    private var length = 0
    private var sampleRate = 0

    val isRecording: Boolean get() = running

    /** Starts recording; throws if the microphone cannot be opened. */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start(sampleRate: Int) {
        check(!running) { "Already recording" }
        val minBytes = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        require(minBytes > 0) { "The microphone does not record at $sampleRate Hz" }
        val rec = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBytes, sampleRate * 4 / 5))
            .build()
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IllegalStateException("Could not open the microphone")
        }
        this.sampleRate = sampleRate
        buffer = FloatArray(sampleRate * 5)
        length = 0
        record = rec
        running = true
        rec.startRecording()
        thread = Thread({ readLoop(rec) }, "voice-recorder").apply { start() }
    }

    private fun readLoop(rec: AudioRecord) {
        val chunk = FloatArray(sampleRate / 10)
        val max = sampleRate * MAX_SECONDS
        while (running) {
            val n = rec.read(chunk, 0, chunk.size, AudioRecord.READ_BLOCKING)
            if (n < 0) break
            synchronized(this) {
                val take = minOf(n, max - length)
                if (take <= 0) return@synchronized
                if (length + take > buffer.size) buffer = buffer.copyOf(minOf(max, maxOf(buffer.size * 2, length + take)))
                System.arraycopy(chunk, 0, buffer, length, take)
                length += take
            }
        }
    }

    /** Recorded milliseconds so far. */
    fun elapsedMillis(): Long = synchronized(this) { if (sampleRate == 0) 0 else length * 1000L / sampleRate }

    /** Stops and returns the clip, or null if nothing was being recorded. */
    fun stop(): AudioClip? {
        val rec = record ?: return null
        running = false
        runCatching { rec.stop() }
        thread?.join(500)
        rec.release()
        record = null
        thread = null
        return synchronized(this) { AudioClip(buffer.copyOf(length), sampleRate) }
    }

    companion object {
        const val MAX_SECONDS = 60
    }
}
