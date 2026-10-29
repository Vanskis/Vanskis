package com.vanskis.liquidpocket.media

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** Mono [samples] in -1..1 at [sampleRate]. */
class AudioClip(val samples: FloatArray, val sampleRate: Int) {
    val durationMillis: Long get() = if (sampleRate <= 0) 0 else samples.size * 1000L / sampleRate
}

/**
 * WAV files for voice messages. Written as 16-bit PCM mono; read as 16-bit PCM or 32-bit float with
 * any channel count (mixed down to mono).
 */
object Wav {
    private const val PCM = 1
    private const val FLOAT = 3
    private const val EXTENSIBLE = 0xFFFE

    fun encode(samples: FloatArray, sampleRate: Int): ByteArray {
        val dataSize = samples.size * 2
        val buf = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray()).putInt(36 + dataSize).put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray()).putInt(16).putShort(PCM.toShort()).putShort(1)
            .putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
        buf.put("data".toByteArray()).putInt(dataSize)
        for (s in samples) buf.putShort((s.coerceIn(-1f, 1f) * Short.MAX_VALUE).roundToInt().toShort())
        return buf.array()
    }

    fun decode(bytes: ByteArray): AudioClip {
        val h = header(bytes)
        val buf = ByteBuffer.wrap(bytes, h.dataOffset, minOf(h.dataSize, bytes.size - h.dataOffset)).order(ByteOrder.LITTLE_ENDIAN)
        val bytesPerSample = h.bitsPerSample / 8
        val frames = buf.remaining() / (bytesPerSample * h.channels)
        val out = FloatArray(frames)
        for (i in 0 until frames) {
            var sum = 0f
            repeat(h.channels) {
                sum += if (h.float) buf.getFloat() else buf.getShort() / 32768f
            }
            out[i] = sum / h.channels
        }
        return AudioClip(out, h.sampleRate)
    }

    /** The clip's length from its header alone, or null if [file] is not a WAV this reads. */
    fun durationMillis(file: File): Long? = runCatching {
        val head = ByteArray(minOf(file.length(), 4096L).toInt())
        file.inputStream().use { input ->
            var read = 0
            while (read < head.size) {
                val n = input.read(head, read, head.size - read)
                if (n < 0) break
                read += n
            }
        }
        val h = header(head)
        h.dataSize.toLong() / (h.bitsPerSample / 8 * h.channels) * 1000L / h.sampleRate
    }.getOrNull()

    /** Linear resampling; voice clips are re-read at the loaded model's rate if it differs. */
    fun resample(samples: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to || samples.isEmpty()) return samples
        val n = (samples.size.toLong() * to / from).toInt().coerceAtLeast(1)
        val step = from.toDouble() / to
        return FloatArray(n) { i ->
            val pos = i * step
            val j = pos.toInt().coerceAtMost(samples.lastIndex)
            val next = samples[minOf(j + 1, samples.lastIndex)]
            val t = (pos - j).toFloat()
            samples[j] + (next - samples[j]) * t
        }
    }

    private class Header(
        val channels: Int,
        val sampleRate: Int,
        val bitsPerSample: Int,
        val float: Boolean,
        val dataOffset: Int,
        val dataSize: Int,
    )

    private fun header(bytes: ByteArray): Header {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(bytes.size >= 12 && tag(buf, 0) == "RIFF" && tag(buf, 8) == "WAVE") { "Not a WAV file" }
        var pos = 12
        var format = -1
        var channels = 0
        var rate = 0
        var bits = 0
        while (pos + 8 <= bytes.size) {
            val id = tag(buf, pos)
            val size = buf.getInt(pos + 4)
            val body = pos + 8
            when (id) {
                "fmt " -> {
                    format = buf.getShort(body).toInt() and 0xFFFF
                    channels = buf.getShort(body + 2).toInt()
                    rate = buf.getInt(body + 4)
                    bits = buf.getShort(body + 14).toInt()
                }
                "data" -> {
                    val float = format == FLOAT || (format == EXTENSIBLE && bits == 32)
                    require(format == PCM || format == FLOAT || format == EXTENSIBLE) { "Unsupported WAV format $format" }
                    require(channels > 0 && rate > 0 && (if (float) bits == 32 else bits == 16)) { "Unsupported WAV layout" }
                    return Header(channels, rate, bits, float, body, size)
                }
            }
            pos = body + size + (size and 1)
        }
        throw IllegalArgumentException("WAV has no data")
    }

    private fun tag(buf: ByteBuffer, at: Int) = String(ByteArray(4) { buf.get(at + it) }, Charsets.US_ASCII)
}
