package com.vanskis.liquidpocket.chat

import com.vanskis.liquidpocket.engine.AudioInput
import com.vanskis.liquidpocket.engine.ImageInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceAndMediaTest {

    @Test fun `the model is asked to speak only when replies are spoken and it is chosen`() {
        assertTrue(VoiceRoute.modelSpeaks(true, VoiceEngine.MODEL))
        assertFalse(VoiceRoute.modelSpeaks(false, VoiceEngine.MODEL))
        assertFalse(VoiceRoute.modelSpeaks(true, VoiceEngine.ANDROID))
    }

    @Test fun `android speaks when replies are spoken and the model did not speak`() {
        assertFalse(VoiceRoute.androidSpeaks(speakReplies = true, spoke = true))
        assertTrue(VoiceRoute.androidSpeaks(speakReplies = true, spoke = false))
        assertFalse(VoiceRoute.androidSpeaks(speakReplies = false, spoke = false))
        assertFalse(VoiceRoute.androidSpeaks(speakReplies = false, spoke = true))
    }

    @Test fun `the mic records for the model only when it is chosen and a media model is installed`() {
        assertTrue(VoiceRoute.modelHears(VoiceEngine.MODEL, mediaInstalled = true))
        assertFalse(VoiceRoute.modelHears(VoiceEngine.ANDROID, mediaInstalled = true))
        assertFalse(VoiceRoute.modelHears(VoiceEngine.MODEL, mediaInstalled = false))
        assertEquals(16_000, VoiceRoute.RECORD_SAMPLE_RATE)
    }

    private class CountingLoader : MediaLoader {
        var loads = 0
        override fun image(path: String): ImageInput {
            loads++
            return ImageInput(10, 10, ByteArray(300))
        }
        override fun audio(path: String, sampleRate: Int): AudioInput {
            loads++
            return AudioInput(FloatArray(sampleRate / 100))
        }
    }

    @Test fun `decoded media is cached by path and the least recently used goes first`() {
        val source = CountingLoader()
        val cache = CachingMediaLoader(source, maxBytes = 700)
        val a = cache.image("a")
        assertSame(a, cache.image("a"))
        assertEquals(1, source.loads)
        cache.image("b")
        cache.image("a") // a is now the most recent
        cache.image("c") // 900 bytes > 700: b goes
        assertEquals(3, source.loads)
        assertSame(a, cache.image("a"))
        assertEquals(3, source.loads)
        cache.image("b")
        assertEquals(4, source.loads)
    }

    @Test fun `audio is cached per sample rate`() {
        val source = CountingLoader()
        val cache = CachingMediaLoader(source)
        val x = cache.audio("v", 16000)
        assertSame(x, cache.audio("v", 16000))
        assertNotSame(x, cache.audio("v", 24000))
        assertEquals(2, source.loads)
    }

    @Test fun `a failed load is not cached`() {
        var fail = true
        val cache = CachingMediaLoader(object : MediaLoader {
            override fun image(path: String): ImageInput = if (fail) throw java.io.IOException("x") else ImageInput(1, 1, ByteArray(3))
            override fun audio(path: String, sampleRate: Int) = AudioInput(FloatArray(0))
        })
        runCatching { cache.image("p") }
        fail = false
        assertEquals(1, cache.image("p").width)
    }
}
