package com.vanskis.liquidpocket.chat

import com.vanskis.liquidpocket.engine.Accelerator
import com.vanskis.liquidpocket.engine.Capabilities
import com.vanskis.liquidpocket.engine.ChatMessage
import com.vanskis.liquidpocket.engine.Engine
import com.vanskis.liquidpocket.engine.EngineConfig
import com.vanskis.liquidpocket.engine.EngineState
import com.vanskis.liquidpocket.engine.GenerationParams
import com.vanskis.liquidpocket.engine.GenerationStats
import com.vanskis.liquidpocket.engine.ReplyChunk
import com.vanskis.liquidpocket.engine.Role
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class SavedStateHitTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class SyncTestEngine : Engine {
        override val state = MutableStateFlow<EngineState>(EngineState.Idle)
        override val capabilities = MutableStateFlow(Capabilities.NONE)
        override val lastStats = MutableStateFlow<GenerationStats?>(null)

        var syncCacheResult = true
        var syncCacheCalledWith: List<ChatMessage>? = null
        var syncCacheCallCount = 0

        var nextFastPath = false
        var nextReReadTokens = 0

        var tokens = Channel<String>(Channel.UNLIMITED)
        var modelPath = ""

        override suspend fun load(modelPath: String, config: EngineConfig) {
            this.modelPath = modelPath
            state.value = EngineState.Ready(modelPath, config.contextSize)
        }

        override suspend fun unload() {
            state.value = EngineState.Idle
        }

        override fun chat(messages: List<ChatMessage>, params: GenerationParams): Flow<ReplyChunk> = flow {
            state.value = EngineState.Generating
            try {
                for (t in tokens) emit(ReplyChunk.Text(t))
            } finally {
                lastStats.value = GenerationStats(
                    promptTokens = 20,
                    prefillTokensPerSecond = 100.0,
                    generatedTokens = 5,
                    decodeTokensPerSecond = 20.0,
                    fastPath = nextFastPath,
                    reReadTokens = nextReReadTokens,
                )
                state.value = EngineState.Ready(modelPath, 8192)
            }
        }

        override fun stop() {
            tokens.close()
        }

        override suspend fun syncCache(messages: List<ChatMessage>, params: GenerationParams): Boolean {
            syncCacheCallCount++
            syncCacheCalledWith = messages
            return syncCacheResult
        }

        override suspend fun countTokens(messages: List<ChatMessage>, params: GenerationParams): Int = 20

        fun reply(text: String) {
            tokens.trySend(text)
            tokens.close()
        }
    }

    private class InMemoryChatStore : ChatStore {
        val titles = mutableMapOf<Long, String>()
        val turnsMap = mutableMapOf<Long, MutableList<Turn>>()
        private var nextId = 1L

        override suspend fun createConversation(title: String): Long {
            val id = nextId++
            titles[id] = title
            turnsMap[id] = mutableListOf()
            return id
        }

        override suspend fun turns(conversationId: Long): List<Turn> =
            turnsMap[conversationId]?.toList() ?: emptyList()

        override suspend fun appendTurn(conversationId: Long, turn: Turn) {
            turnsMap.getOrPut(conversationId) { mutableListOf() }.add(turn)
        }

        override suspend fun truncate(conversationId: Long, fromIndex: Int) {
            val list = turnsMap[conversationId] ?: return
            while (list.size > fromIndex) list.removeAt(list.lastIndex)
        }

        override suspend fun deleteConversation(conversationId: Long) {
            titles.remove(conversationId)
            turnsMap.remove(conversationId)
        }

        override suspend fun factTexts(): List<String> = emptyList()
    }

    private val modelKey = ModelStateKey("/model.gguf", 1000L, 1000L, 8192)

    @Test
    fun syncCacheSavesStateWhenTokensMatch() = runTest {
        val kvDir = tempFolder.newFolder("kv")
        val engine = SyncTestEngine()
        val store = InMemoryChatStore()
        val savedState = SavedChatState(
            kvDir = kvDir,
            saveState = { file ->
                file.writeBytes(ByteArray(100))
                File(file.parentFile, "${file.name}.text").writeText("cached")
                100L
            },
            loadState = { 50 },
        )

        val controller = ChatController(
            engine = engine,
            store = store,
            scope = this,
            work = StandardTestDispatcher(testScheduler),
            savedState = savedState,
        )

        val modelFile = tempFolder.newFile("model1.gguf")
        engine.load(modelFile.absolutePath, EngineConfig(contextSize = 8192))
        val convId = store.createConversation("chat")
        controller.open(convId)
        advanceUntilIdle()

        // Turn 1: user sends message, reply completes
        engine.syncCacheResult = true
        engine.reply("Hello!")
        controller.send("Hi", GenerationParams())
        advanceUntilIdle()

        // Verify syncCache was called before saving state
        assertEquals(1, engine.syncCacheCallCount)
        assertNotNull(engine.syncCacheCalledWith)
        // History should include system, user, and assistant turn
        val history = engine.syncCacheCalledWith!!
        assertEquals(3, history.size)
        assertEquals(Role.SYSTEM, history[0].role)
        assertEquals(Role.USER, history[1].role)
        assertEquals(Role.ASSISTANT, history[2].role)
        assertEquals("Hello!", history[2].content)

        // Since syncCache succeeded, savedState has state
        assertTrue("State should be saved on syncCache success", savedState.hasState(convId))
    }

    @Test
    fun syncCacheMismatchAbortsSaveAndCleansUp() = runTest {
        val kvDir = tempFolder.newFolder("kv")
        val engine = SyncTestEngine()
        val store = InMemoryChatStore()
        val savedState = SavedChatState(
            kvDir = kvDir,
            saveState = { file ->
                file.writeBytes(ByteArray(100))
                File(file.parentFile, "${file.name}.text").writeText("cached")
                100L
            },
            loadState = { 50 },
        )

        val controller = ChatController(
            engine = engine,
            store = store,
            scope = this,
            work = StandardTestDispatcher(testScheduler),
            savedState = savedState,
        )

        val modelFile = tempFolder.newFile("model2.gguf")
        engine.load(modelFile.absolutePath, EngineConfig(contextSize = 8192))
        val convId = store.createConversation("chat")
        controller.open(convId)
        advanceUntilIdle()

        // Turn 1: syncCache fails (simulating token mismatch or dropped thinking)
        engine.syncCacheResult = false
        engine.reply("Hello with thinking")
        controller.send("Hi", GenerationParams())
        advanceUntilIdle()

        assertEquals(1, engine.syncCacheCallCount)
        assertFalse("State must NOT be saved when syncCache returns false", savedState.hasState(convId))
    }

    @Test
    fun fastPathAndReReadStatsAreReportedOnTurns() = runTest {
        val kvDir = tempFolder.newFolder("kv")
        val engine = SyncTestEngine()
        val store = InMemoryChatStore()
        val savedState = SavedChatState(
            kvDir = kvDir,
            saveState = { 100L },
            loadState = { 50 },
        )

        val controller = ChatController(
            engine = engine,
            store = store,
            scope = this,
            work = StandardTestDispatcher(testScheduler),
            savedState = savedState,
        )

        val modelFile = tempFolder.newFile("model3.gguf")
        engine.load(modelFile.absolutePath, EngineConfig(contextSize = 8192))
        val convId = store.createConversation("chat")
        controller.open(convId)
        advanceUntilIdle()

        // Turn 1: first turn (no fast path)
        engine.nextFastPath = false
        engine.nextReReadTokens = 0
        engine.reply("Answer 1")
        controller.send("Question 1", GenerationParams())
        advanceUntilIdle()

        val turn1Stats = controller.state.value.turns.last().stats
        assertNotNull(turn1Stats)
        assertFalse(turn1Stats!!.fastPath)
        assertEquals(0, turn1Stats.reReadTokens)

        // Turn 2: hits fast path (0 re-read tokens)
        engine.tokens = Channel(Channel.UNLIMITED)
        engine.nextFastPath = true
        engine.nextReReadTokens = 0
        engine.reply("Answer 2")
        controller.send("Question 2", GenerationParams())
        advanceUntilIdle()

        val turn2Stats = controller.state.value.turns.last().stats
        assertNotNull(turn2Stats)
        assertTrue("Turn 2 should report fastPath = true", turn2Stats!!.fastPath)
        assertEquals(0, turn2Stats.reReadTokens)

        // Turn 3: simulate cache miss on a hybrid model where tokens had to be re-read
        engine.tokens = Channel(Channel.UNLIMITED)
        engine.nextFastPath = false
        engine.nextReReadTokens = 45
        engine.reply("Answer 3")
        controller.send("Question 3", GenerationParams())
        advanceUntilIdle()

        val turn3Stats = controller.state.value.turns.last().stats
        assertNotNull(turn3Stats)
        assertFalse("Turn 3 should report fastPath = false", turn3Stats!!.fastPath)
        assertEquals(45, turn3Stats.reReadTokens)
    }

    @Test
    fun templateThinkingDivergenceDemonstration() {
        // Demonstrate why raw generation diverges from history template rendering when thinking is used:
        // Turn 1 reply generates thinking before content:
        val turn1 = Turn(Role.ASSISTANT, text = "result", thinking = "deep reasoning")

        // While replying, turn keeps thinking:
        val replyingMessage = turn1.thinking?.let { ChatMessage(Role.ASSISTANT, turn1.text, thinking = it) }
        assertEquals("deep reasoning", replyingMessage?.thinking)

        // Once the reply finishes and turn 2 begins, history() drops thinking for past turns:
        val historyTurn2Message = ChatMessage(Role.ASSISTANT, turn1.text, thinking = null)
        assertNull(historyTurn2Message.thinking)

        // The raw generated cache text contains the thinking tokens, whereas the template rendering
        // for turn 2 does not contain thinking tokens.
        assertFalse(replyingMessage?.thinking == historyTurn2Message.thinking)
    }
}
