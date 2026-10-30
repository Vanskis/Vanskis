package com.vanskis.liquidpocket.engine

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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
}
