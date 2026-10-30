package com.vanskis.liquidpocket.engine

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LlamaEngineTest {
    @Test
    fun countTokensWithoutModelThrowsEngineException() {
        val engine = LlamaEngine(nativeLibDir = "")
        val e = assertThrows(EngineException::class.java) {
            runBlocking { engine.countTokens(listOf(ChatMessage(Role.USER, "hi")), GenerationParams()) }
        }
        assertEquals("No model loaded", e.message)
    }

    @Test
    fun benchmarkWithoutModelThrowsEngineException() {
        val engine = LlamaEngine(nativeLibDir = "")
        val e = assertThrows(EngineException::class.java) { runBlocking { engine.benchmark() } }
        assertEquals("No model loaded", e.message)
    }

    @Test
    fun npuAvailableOnlyWhenTheBackendIsPackaged() {
        val dir = Files.createTempDirectory("lp-native").toFile()
        try {
            // The CPU build ships libggml-cpu-*.so only; libggml-hexagon.so would be auto-loaded, so the NPU build renames it.
            File(dir, "libggml-cpu-armv8.2_2.so").writeText("")
            assertFalse(LlamaEngine(dir.path).npuAvailable)
            File(dir, "liblp-hexagon.so").writeText("")
            assertTrue(LlamaEngine(dir.path).npuAvailable)
        } finally {
            dir.deleteRecursively()
        }
    }
}
