package com.vanskis.liquidpocket.chat

import com.vanskis.liquidpocket.engine.ChatMessage
import com.vanskis.liquidpocket.engine.Engine
import com.vanskis.liquidpocket.engine.EngineConfig
import com.vanskis.liquidpocket.engine.EngineException
import com.vanskis.liquidpocket.engine.EngineState
import com.vanskis.liquidpocket.engine.GenerationParams
import com.vanskis.liquidpocket.engine.GenerationStats
import com.vanskis.liquidpocket.engine.Role
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatControllerTest {

    /** Streams whatever the test sends into [tokens]; [stop] ends the reply like the real engine. */
    private class FakeEngine : Engine {
        override val state = MutableStateFlow<EngineState>(EngineState.Idle)
        override val lastStats = MutableStateFlow<GenerationStats?>(null)
        val loads = mutableListOf<Pair<String, Int>>()
        var trainedContext = 32768
        var loadFailure: Exception? = null
        var chatFailure: Exception? = null
        var publishStats = true
        var sent: List<ChatMessage>? = null
        var tokens = Channel<String>(Channel.UNLIMITED)

        override suspend fun load(modelPath: String, config: EngineConfig) {
            loadFailure?.let { throw it }
            loads += modelPath to config.contextSize
            state.value = EngineState.Ready(modelPath, minOf(config.contextSize, trainedContext))
        }

        override suspend fun unload() {
            state.value = EngineState.Idle
        }

        override fun chat(messages: List<ChatMessage>, params: GenerationParams): Flow<String> = flow {
            sent = messages
            val ready = state.value
            state.value = EngineState.Generating
            try {
                for (t in tokens) emit(t)
                chatFailure?.let { throw it }
            } finally {
                if (publishStats) lastStats.value = GenerationStats(10, 100.0, 2, 25.0)
                state.value = ready
            }
        }

        override fun stop() {
            tokens.close()
        }

        fun reply(vararg pieces: String) {
            pieces.forEach { tokens.trySend(it) }
            tokens.close()
        }
    }

    private val engine = FakeEngine()
    private val params = GenerationParams()

    private fun TestScope.controller() = ChatController(engine, this, StandardTestDispatcher(testScheduler))

    private suspend fun ChatController.ready() = ensureLoaded("/m.gguf", 8192)

    @Test fun `streams the reply into the conversation and commits it with its stats`() = runTest {
        val c = controller()
        c.ready()
        c.send("  hi  ", params)
        engine.tokens.send("Hel")
        runCurrent()
        assertEquals("Hel", c.state.value.streaming)
        assertTrue(c.state.value.generating)

        engine.reply("lo")
        advanceUntilIdle()
        val s = c.state.value
        assertNull(s.streaming)
        assertEquals(listOf(Role.USER, Role.ASSISTANT), s.turns.map { it.role })
        assertEquals("hi", s.turns[0].text)
        assertEquals("Hello", s.turns[1].text)
        assertSame(engine.lastStats.value, s.turns[1].stats)
        assertEquals(
            listOf(ChatMessage(Role.SYSTEM, SYSTEM_PROMPT), ChatMessage(Role.USER, "hi")),
            engine.sent,
        )
    }

    @Test fun `each turn sends the full history after the system prompt`() = runTest {
        val c = controller()
        c.ready()
        engine.reply("Hello")
        c.send("hi", params)
        advanceUntilIdle()
        engine.tokens = Channel(Channel.UNLIMITED)
        engine.reply("Sure")
        c.send("more", params)
        advanceUntilIdle()

        assertEquals(
            listOf(
                ChatMessage(Role.SYSTEM, SYSTEM_PROMPT),
                ChatMessage(Role.USER, "hi"),
                ChatMessage(Role.ASSISTANT, "Hello"),
                ChatMessage(Role.USER, "more"),
            ),
            engine.sent,
        )
        assertEquals(4, c.state.value.turns.size)
    }

    @Test fun `stop commits the partial reply`() = runTest {
        val c = controller()
        c.ready()
        c.send("hi", params)
        engine.tokens.send("Par")
        runCurrent()
        c.stop()
        advanceUntilIdle()

        val s = c.state.value
        assertFalse(s.generating)
        assertEquals("Par", s.turns.last().text)
        assertEquals(Role.ASSISTANT, s.turns.last().role)
    }

    @Test fun `send is ignored while a reply is streaming`() = runTest {
        val c = controller()
        c.ready()
        c.send("one", params)
        runCurrent()
        c.send("two", params)
        engine.reply("ok")
        advanceUntilIdle()

        assertEquals(listOf("one", "ok"), c.state.value.turns.map { it.text })
    }

    @Test fun `an engine failure becomes a visible error and keeps the text so far`() = runTest {
        val c = controller()
        c.ready()
        engine.chatFailure = EngineException("decode failed")
        engine.reply("Half")
        c.send("hi", params)
        advanceUntilIdle()

        val s = c.state.value
        assertFalse(s.generating)
        assertEquals("Half", s.turns.last().text)
        assertNull("stats of a failed reply are not shown", s.turns.last().stats)
        assertTrue(s.error!!.contains("decode failed"))
    }

    @Test fun `a failure before any text adds no assistant turn`() = runTest {
        val c = controller()
        c.ready()
        engine.chatFailure = EngineException("context full")
        engine.reply()
        c.send("hi", params)
        advanceUntilIdle()

        assertEquals(listOf(Role.USER), c.state.value.turns.map { it.role })
        assertNotNull(c.state.value.error)
    }

    @Test fun `stats left over from an earlier reply are not attached`() = runTest {
        val c = controller()
        c.ready()
        engine.lastStats.value = GenerationStats(1, 1.0, 1, 1.0)
        engine.publishStats = false
        engine.reply("Hi")
        c.send("hi", params)
        advanceUntilIdle()

        assertNull(c.state.value.turns.last().stats)
    }

    @Test fun `new chat stops the reply and clears the conversation`() = runTest {
        val c = controller()
        c.ready()
        c.send("hi", params)
        engine.tokens.send("Par")
        runCurrent()
        c.newChat()
        advanceUntilIdle()

        assertEquals(emptyList<Turn>(), c.state.value.turns)
        assertFalse(c.state.value.generating)
    }

    @Test fun `loads once per model and context size and reloads when the size changes`() = runTest {
        val c = controller()
        c.ensureLoaded("/m.gguf", 8192)
        c.ensureLoaded("/m.gguf", 8192)
        assertEquals(listOf("/m.gguf" to 8192), engine.loads)

        c.ensureLoaded("/m.gguf", 16384)
        assertEquals(listOf("/m.gguf" to 8192, "/m.gguf" to 16384), engine.loads)
        assertFalse(c.state.value.loading)
    }

    @Test fun `a context the engine clamped to the model's limit does not reload every time`() = runTest {
        engine.trainedContext = 4096
        val c = controller()
        c.ensureLoaded("/m.gguf", 8192)
        c.ensureLoaded("/m.gguf", 8192)
        assertEquals(1, engine.loads.size)
    }

    @Test fun `a model the engine already has loaded is not loaded again`() = runTest {
        engine.state.value = EngineState.Ready("/m.gguf", 8192)
        controller().ensureLoaded("/m.gguf", 8192)
        assertEquals(emptyList<Pair<String, Int>>(), engine.loads)
    }

    @Test fun `a load failure is reported and leaves the loading state`() = runTest {
        engine.loadFailure = EngineException("bad gguf")
        val c = controller()
        c.ensureLoaded("/m.gguf", 8192)

        assertFalse(c.state.value.loading)
        assertTrue(c.state.value.error!!.contains("bad gguf"))
    }
}
