package com.vanskis.liquidpocket.chat

import com.vanskis.liquidpocket.engine.Capabilities
import com.vanskis.liquidpocket.engine.ChatMessage
import com.vanskis.liquidpocket.engine.Engine
import com.vanskis.liquidpocket.engine.EngineConfig
import com.vanskis.liquidpocket.engine.EngineException
import com.vanskis.liquidpocket.engine.EngineState
import com.vanskis.liquidpocket.engine.GenerationParams
import com.vanskis.liquidpocket.engine.GenerationStats
import com.vanskis.liquidpocket.engine.ReplyChunk
import com.vanskis.liquidpocket.engine.Role
import com.vanskis.liquidpocket.engine.ToolCall
import com.vanskis.liquidpocket.engine.ToolSpec
import com.vanskis.liquidpocket.tools.Tool
import com.vanskis.liquidpocket.tools.ToolResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ModelSwapTest {

    /** Answers each chat() with the next scripted reply, "Done" once the script is used up. */
    private class SwapEngine : Engine {
        override val state = MutableStateFlow<EngineState>(EngineState.Idle)
        override val capabilities = MutableStateFlow(Capabilities.NONE)
        override val lastStats = MutableStateFlow<GenerationStats?>(null)
        val loads = mutableListOf<String>()
        val replies = ArrayDeque<Pair<String, List<ToolCall>>>()
        /** Per chat(): the model loaded and the params it was given. */
        val chats = mutableListOf<Pair<String, GenerationParams>>()
        val sent = mutableListOf<List<ChatMessage>>()
        var chatFailure: Exception? = null
        var failLoadOf: String? = null
        /** When set, the next reply waits for it (or for [stop]) after its first text. */
        var hold: CompletableDeferred<Unit>? = null

        val loaded: String? get() = (state.value as? EngineState.Ready)?.modelPath

        override suspend fun load(modelPath: String, config: EngineConfig) {
            loads += modelPath
            if (modelPath == failLoadOf) {
                state.value = EngineState.Error("no memory")
                throw EngineException("no memory")
            }
            state.value = EngineState.Ready(modelPath, config.contextSize)
        }

        override suspend fun unload() {
            state.value = EngineState.Idle
        }

        override fun stop() {
            hold?.complete(Unit)
        }

        override fun chat(messages: List<ChatMessage>, params: GenerationParams): Flow<ReplyChunk> = flow {
            chats += (loaded ?: "none") to params
            sent += messages
            val (text, calls) = replies.removeFirstOrNull() ?: ("Done" to emptyList())
            if (text.isNotEmpty()) emit(ReplyChunk.Text(text))
            hold?.let { it.await(); hold = null }
            chatFailure?.let { throw it }
            calls.forEach { emit(ReplyChunk.ToolCall(it)) }
        }
    }

    private class Store : ChatStore {
        val saved = mutableMapOf<Long, MutableList<Turn>>()
        private var nextId = 1L
        override suspend fun createConversation(title: String) = nextId++.also { saved[it] = mutableListOf() }
        override suspend fun turns(conversationId: Long) = saved.getValue(conversationId).toList()
        override suspend fun appendTurn(conversationId: Long, turn: Turn) {
            saved.getValue(conversationId) += turn
        }
        override suspend fun truncate(conversationId: Long, fromIndex: Int) {
            val list = saved.getValue(conversationId)
            while (list.size > fromIndex) list.removeAt(list.lastIndex)
        }
        override suspend fun deleteConversation(conversationId: Long) {
            saved.remove(conversationId)
        }
        override suspend fun factTexts() = emptyList<String>()
    }

    private class Models(var smartInstalled: Boolean = true) : ModelTargets {
        var downloads = 0
        override suspend fun resident() = RESIDENT
        override suspend fun smart() = if (smartInstalled) SMART else null
        override fun downloadSmart() {
            downloads++
        }
    }

    private class ClockTool : Tool {
        override val spec = ToolSpec("get_time", "The time.", """{"type":"object"}""")
        override val needsConfirmation = false
        val runs = mutableListOf<String>()
        override fun describe(argumentsJson: String) = "time"
        override suspend fun run(argumentsJson: String): ToolResult {
            runs += argumentsJson
            return ToolResult("""{"time":"10:00"}""")
        }
    }

    private val engine = SwapEngine()
    private val store = Store()
    private val models = Models()
    private val clock = ClockTool()
    private val params = GenerationParams(tools = listOf(clock.spec), speak = true)

    private fun TestScope.controller() =
        ChatController(engine, store, this, StandardTestDispatcher(testScheduler), listOf(clock), models = models)

    /** A conversation with one exchange on the resident model. */
    private suspend fun TestScope.chatted(): ChatController {
        val c = controller()
        c.ensureLoaded(RESIDENT.path, RESIDENT.contextSize, RESIDENT.mmprojPath)
        engine.replies += "Quick answer." to emptyList()
        c.send("What is 17 * 23?", params)
        advanceUntilIdle()
        engine.loads.clear()
        engine.chats.clear()
        return c
    }

    @Test fun `think harder swaps in the smart model for the reply and loads the resident back`() = runTest {
        val c = chatted()
        engine.replies += "391." to emptyList()
        c.thinkHarder(params)
        advanceUntilIdle()

        assertEquals(listOf(SMART.path, RESIDENT.path), engine.loads)
        assertEquals(RESIDENT.path, engine.loaded)
        val (model, sentParams) = engine.chats.single()
        assertEquals(SMART.path, model)
        assertFalse("the smart model does not speak", sentParams.speak)
        assertEquals(listOf("get_time"), sentParams.tools.map { it.name })
        val s = c.state.value
        assertEquals(listOf("What is 17 * 23?", "391."), s.turns.map { it.text })
        assertEquals(s.turns, store.saved.getValue(s.conversationId!!))
        assertNull(s.swap)
        assertFalse(s.busy)
    }

    @Test fun `the state shows each phase of the swap`() = runTest {
        val c = chatted()
        val phases = mutableListOf<ModelSwap?>()
        val watching = launch { c.state.collect { phases += it.swap } }
        c.thinkHarder(params)
        advanceUntilIdle()
        watching.cancel()

        assertEquals(listOf(null, ModelSwap.TO_SMART, ModelSwap.SMART, ModelSwap.BACK, null), phases.distinctUntilChangedList())
    }

    @Test fun `the resident is loaded back after the smart model fails`() = runTest {
        val c = chatted()
        engine.chatFailure = EngineException("decode failed")
        c.thinkHarder(params)
        advanceUntilIdle()

        assertEquals(listOf(SMART.path, RESIDENT.path), engine.loads)
        assertEquals(RESIDENT.path, engine.loaded)
        assertTrue(c.state.value.error!!.contains("decode failed"))
        assertNull(c.state.value.swap)
    }

    @Test fun `the resident is loaded back after the smart model fails to load`() = runTest {
        val c = chatted()
        engine.failLoadOf = SMART.path
        c.thinkHarder(params)
        advanceUntilIdle()

        assertEquals(listOf(SMART.path, RESIDENT.path), engine.loads)
        assertEquals(RESIDENT.path, engine.loaded)
        assertTrue(engine.chats.isEmpty())
        assertTrue(c.state.value.error!!.contains("no memory"))
    }

    @Test fun `the resident is loaded back after a stop`() = runTest {
        val c = chatted()
        engine.hold = CompletableDeferred()
        engine.replies += "Let me wo" to emptyList()
        c.thinkHarder(params)
        advanceUntilIdle()
        assertEquals(ModelSwap.SMART, c.state.value.swap)
        assertTrue(c.state.value.busy)

        c.stop()
        advanceUntilIdle()

        assertEquals(listOf(SMART.path, RESIDENT.path), engine.loads)
        assertEquals(RESIDENT.path, engine.loaded)
        assertEquals("Let me wo", c.state.value.turns.last().text)
        assertFalse(c.state.value.busy)
    }

    @Test fun `escalate hands the question to the smart model without running a tool`() = runTest {
        val c = controller()
        c.ensureLoaded(RESIDENT.path, RESIDENT.contextSize, RESIDENT.mmprojPath)
        engine.loads.clear()
        engine.replies += "Let me think." to listOf(ToolCall(Escalate.NAME, """{"reason":"math"}"""))
        engine.replies += "391." to emptyList()
        c.send("What is 17 * 23?", params)
        advanceUntilIdle()

        val (residentModel, residentParams) = engine.chats[0]
        assertEquals(RESIDENT.path, residentModel)
        assertEquals(listOf("get_time", Escalate.NAME), residentParams.tools.map { it.name })
        val (smartModel, smartParams) = engine.chats[1]
        assertEquals(SMART.path, smartModel)
        assertEquals("escalate is never offered to the smart model", listOf("get_time"), smartParams.tools.map { it.name })
        assertEquals(listOf(Role.SYSTEM, Role.USER), engine.sent[1].map { it.role })
        assertEquals(listOf(SMART.path, RESIDENT.path), engine.loads)
        assertEquals(RESIDENT.path, engine.loaded)
        assertTrue(clock.runs.isEmpty())

        val s = c.state.value
        assertEquals(listOf(Role.USER, Role.ASSISTANT), s.turns.map { it.role })
        assertEquals("391.", s.turns.last().text)
        assertEquals(s.turns, store.saved.getValue(s.conversationId!!))
        assertNull(s.confirmation)
        assertFalse(s.busy)
    }

    @Test fun `escalate is not offered while the smart model is not installed`() = runTest {
        models.smartInstalled = false
        val c = controller()
        c.send("hi", params)
        advanceUntilIdle()

        assertEquals(listOf("get_time"), engine.chats.single().second.tools.map { it.name })
    }

    @Test fun `think harder without the smart model starts its download and leaves the chat alone`() = runTest {
        val c = chatted()
        models.smartInstalled = false
        val before = c.state.value
        c.thinkHarder(params)
        advanceUntilIdle()

        assertEquals(1, models.downloads)
        assertTrue(engine.loads.isEmpty())
        assertTrue(engine.chats.isEmpty())
        assertEquals(before, c.state.value)
    }

    private fun <T> List<T>.distinctUntilChangedList(): List<T> =
        fold(mutableListOf()) { acc, x -> if (acc.isEmpty() || acc.last() != x) acc += x; acc }

    private companion object {
        val RESIDENT = LoadTarget("/omni.gguf", "/omni-mmproj.gguf", 8192)
        val SMART = LoadTarget("/smart.gguf", null, 8192)
    }
}
