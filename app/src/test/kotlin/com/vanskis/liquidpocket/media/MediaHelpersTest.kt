package com.vanskis.liquidpocket.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MediaHelpersTest {

    @Test fun `wav encode and decode round trip within 16-bit precision`() {
        val samples = FloatArray(1600) { kotlin.math.sin(it / 10.0).toFloat() * 0.8f } + floatArrayOf(1f, -1f, 0f)
        val bytes = Wav.encode(samples, 16000)
        assertEquals(44 + samples.size * 2, bytes.size)
        val clip = Wav.decode(bytes)
        assertEquals(16000, clip.sampleRate)
        assertEquals(samples.size, clip.samples.size)
        assertArrayEquals(samples, clip.samples, 1f / 16000)
        assertEquals(100L, clip.durationMillis)
    }

    @Test fun `wav encode clamps samples outside -1 to 1`() {
        val clip = Wav.decode(Wav.encode(floatArrayOf(2f, -3f), 8000))
        assertArrayEquals(floatArrayOf(1f, -1f), clip.samples, 1e-3f)
    }

    @Test fun `wav decode reads float stereo and mixes it to mono, skipping unknown chunks`() {
        val frames = listOf(0.5f to -0.5f, 1f to 0f)
        val data = ByteBuffer.allocate(frames.size * 8).order(ByteOrder.LITTLE_ENDIAN)
        frames.forEach { (l, r) -> data.putFloat(l).putFloat(r) }
        val list = "LIST".toByteArray() + le(3) + byteArrayOf(1, 2, 3, 0) // odd size, padded
        val fmt = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(3).putShort(2).putInt(22050).putInt(22050 * 8).putShort(8).putShort(32).array()
        val body = "WAVE".toByteArray() + "fmt ".toByteArray() + le(16) + fmt + list +
            "data".toByteArray() + le(data.capacity()) + data.array()
        val bytes = "RIFF".toByteArray() + le(body.size) + body

        val clip = Wav.decode(bytes)
        assertEquals(22050, clip.sampleRate)
        assertArrayEquals(floatArrayOf(0f, 0.5f), clip.samples, 1e-6f)
    }

    @Test fun `wav duration comes from the header`() {
        val file = File.createTempFile("clip", ".wav")
        try {
            file.writeBytes(Wav.encode(FloatArray(16000 * 4 + 8000), 16000))
            assertEquals(4500L, Wav.durationMillis(file))
            file.writeBytes(byteArrayOf(1, 2, 3))
            assertNull(Wav.durationMillis(file))
        } finally {
            file.delete()
        }
    }

    @Test fun `resampling changes the length by the rate ratio and keeps a constant signal`() {
        val up = Wav.resample(FloatArray(100) { 0.25f }, 16000, 48000)
        assertEquals(300, up.size)
        assertTrue(up.all { it == 0.25f })
        val down = Wav.resample(FloatArray(48000) { it / 48000f }, 48000, 16000)
        assertEquals(16000, down.size)
        assertEquals(0.5f, down[8000], 1e-4f)
    }

    @Test fun `images are scaled down to a long side of 768 keeping the aspect`() {
        assertEquals(768 to 576, ImageScale.fit(4032, 3024))
        assertEquals(576 to 768, ImageScale.fit(3024, 4032))
        assertEquals(768 to 1, ImageScale.fit(10000, 3))
        assertEquals(640 to 480, ImageScale.fit(640, 480))
        assertEquals(768 to 768, ImageScale.fit(768, 768))
    }

    @Test fun `decode subsampling stays at or above the target size`() {
        assertEquals(4, ImageScale.sampleSize(4032, 3024)) // 1008 >= 768, 504 is not
        assertEquals(1, ImageScale.sampleSize(1500, 1000))
        assertEquals(2, ImageScale.sampleSize(1536, 1000))
        assertEquals(1, ImageScale.sampleSize(300, 200))
    }

    @Test fun `argb pixels pack into rgb bytes`() {
        val rgb = ImageScale.argbToRgb(intArrayOf(0xFF102030.toInt(), 0x00FFFFFF, 0xFFFF0080.toInt()))
        assertArrayEquals(byteArrayOf(0x10, 0x20, 0x30, -1, -1, -1, -1, 0, 0x80.toByte()), rgb)
    }

    @Test fun `exif orientation maps to rotation and mirroring`() {
        assertEquals(ImageScale.Orientation(0, false), ImageScale.orientation(1))
        assertEquals(ImageScale.Orientation(0, false), ImageScale.orientation(0))
        assertEquals(ImageScale.Orientation(90, false), ImageScale.orientation(6))
        assertEquals(ImageScale.Orientation(270, false), ImageScale.orientation(8))
        assertEquals(ImageScale.Orientation(180, false), ImageScale.orientation(3))
        assertEquals(ImageScale.Orientation(0, true), ImageScale.orientation(2))
        assertTrue(ImageScale.orientation(6).swapsSides && ImageScale.orientation(5).swapsSides)
        assertTrue(!ImageScale.orientation(3).swapsSides)
    }

    @Test fun `markdown is stripped for speech`() {
        val md = """
            # Plan
            Here is **bold**, *italic*, `code` and a [link](https://x.y).

            - first item
            1. numbered
            > quoted ***both***

            ```kotlin
            val x = 1
            ```
            | a | b |
            |---|---|
            | 1 | 2 |
            ---
            ![a cat](cat.png) snake_case_name __stays__
        """.trimIndent()
        assertEquals(
            "Plan\nHere is bold, italic, code and a link.\n\nfirst item\nnumbered\nquoted both\n\n" +
                "a, b\n1, 2\na cat snake_case_name stays",
            SpeechText.from(md),
        )
    }

    private fun le(v: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
}
