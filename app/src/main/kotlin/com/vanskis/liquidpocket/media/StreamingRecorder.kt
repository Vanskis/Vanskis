package com.vanskis.liquidpocket.media

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import androidx.annotation.RequiresPermission

/**
 * Streams mono PCM16 frames from the microphone, for live voice's voice-activity detection. Records as
 * a call does (echo cancellation where the phone has it), so the reply playing from the speaker is
 * less likely to be taken for the user talking over it. Frames arrive on the recorder's own thread.
 */
class StreamingRecorder {
    private var record: AudioRecord? = null
    private var echo: AcousticEchoCanceler? = null
    private var thread: Thread? = null
    @Volatile private var running = false

    val isRecording: Boolean get() = running

    /** Starts streaming [frameSamples]-long frames to [onFrame]; throws if the microphone cannot be opened. */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start(sampleRate: Int, frameSamples: Int, onFrame: (ShortArray) -> Unit) {
        check(!running) { "Already recording" }
        val minBytes = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        require(minBytes > 0) { "The microphone does not record at $sampleRate Hz" }
        val rec = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBytes, frameSamples * 2 * 8))
            .build()
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IllegalStateException("Could not open the microphone")
        }
        if (AcousticEchoCanceler.isAvailable()) {
            echo = AcousticEchoCanceler.create(rec.audioSessionId)?.also { runCatching { it.enabled = true } }
        }
        record = rec
        running = true
        rec.startRecording()
        thread = Thread({ readLoop(rec, frameSamples, onFrame) }, "live-recorder").apply { start() }
    }

    private fun readLoop(rec: AudioRecord, frameSamples: Int, onFrame: (ShortArray) -> Unit) {
        while (running) {
            val frame = ShortArray(frameSamples)
            var filled = 0
            while (running && filled < frameSamples) {
                val n = rec.read(frame, filled, frameSamples - filled, AudioRecord.READ_BLOCKING)
                if (n < 0) {
                    running = false
                    return
                }
                filled += n
            }
            if (filled == frameSamples) onFrame(frame)
        }
    }

    fun stop() {
        val rec = record ?: return
        running = false
        runCatching { rec.stop() }
        thread?.join(500)
        echo?.release()
        echo = null
        rec.release()
        record = null
        thread = null
    }
}
