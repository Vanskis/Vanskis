package com.vanskis.liquidpocket.chat

import com.vanskis.liquidpocket.docs.DocumentFormat
import com.vanskis.liquidpocket.docs.ExtractedDocument
import com.vanskis.liquidpocket.engine.Accelerator
import com.vanskis.liquidpocket.engine.AudioInput
import com.vanskis.liquidpocket.engine.Capabilities
import com.vanskis.liquidpocket.engine.ChatMessage
import com.vanskis.liquidpocket.engine.Engine
import com.vanskis.liquidpocket.engine.EngineConfig
import com.vanskis.liquidpocket.engine.EngineException
import com.vanskis.liquidpocket.engine.EngineState
import com.vanskis.liquidpocket.engine.GenerationParams
import com.vanskis.liquidpocket.engine.GenerationStats
import com.vanskis.liquidpocket.engine.ImageInput
import com.vanskis.liquidpocket.engine.ReplyChunk
import com.vanskis.liquidpocket.engine.Role
import com.vanskis.liquidpocket.engine.ToolCall
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
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
        override val capabilities = MutableStateFlow(Capabilities.NONE)
        override val lastStats = MutableStateFlow<GenerationStats?>(null)
        val loads = mutableListOf<Pair<String, Int>>()
        val configs = mutableListOf<EngineConfig>()
        var trainedContext = 32768
        var loadFailure: Exception? = null
        var chatFailure: Exception? = null
        var publishStats = true
        var sent: List<ChatMessage>? = null
        var sentParams: GenerationParams? = null
        /** Speech emitted before the text, as a speaking model would. */
        var speech: List<FloatArray> = emptyList()
        var chats = 0
        var tokens = Channel<String>(Channel.UNLIMITED)
        /** Reasoning emitted before anything else, then [thinkingMs] of (virtual) time passes. */
        var thinking: List<String> = emptyList()
        var thinkingMs = 0L
        /** What [countTokens] answers; null counts like an engine that cannot (the interface's default). */
        var tokenCount: Int? = null
        val counted = mutableListOf<List<ChatMessage>>()
        /** Summarization requests ([Compaction.INSTRUCTIONS] first), answered with [summaryText] or [summaryFailure]. */
        val summaries = mutableListOf<Pair<List<ChatMessage>, GenerationParams>>()
        var summaryText = "They talked."
        var summaryFailure: Exception? = null

        override suspend fun countTokens(messages: List<ChatMessage>, params: GenerationParams): Int {
            val count = tokenCount ?: return super.countTokens(messages, params)
            counted += messages
            return count
        }

        override suspend fun load(modelPath: String, config: EngineConfig) {
            loadFailure?.let { throw it }
            loads += modelPath to config.contextSize
            configs += config
            // Always the CPU, like a phone whose NPU cannot run the model.
            state.value = EngineState.Ready(modelPath, minOf(config.contextSize, trainedContext))
        }

        override suspend fun unload() {
            state.value = EngineState.Idle
        }

        override fun chat(messages: List<ChatMessage>, params: GenerationParams): Flow<ReplyChunk> = flow {
            if (messages.first().content == Compaction.INSTRUCTIONS) {
                summaries += messages to params
                summaryFailure?.let { throw it }
                emit(ReplyChunk.Text(summaryText))
                return@flow
            }
            sent = messages
            sentParams = params
            chats++
            val ready = state.value
            state.value = EngineState.Generating
            try {
                thinking.forEach { emit(ReplyChunk.Thinking(it)) }
                delay(thinkingMs)
                speech.forEach { emit(ReplyChunk.Audio(it)) }
                for (t in tokens) emit(ReplyChunk.Text(t))
                // Like the real engine, tool calls come once the text ends; they are not reply text.
                emit(ReplyChunk.ToolCall(ToolCall("noop", "{}")))
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

    /** Conversations in memory, ids from 1. */
    private class FakeStore : ChatStore {
        val titles = linkedMapOf<Long, String>()
        val saved = mutableMapOf<Long, MutableList<Turn>>()
        val facts = mutableListOf<String>()
        private var nextId = 1L

        override suspend fun createConversation(title: String): Long {
            val id = nextId++
            titles[id] = title
            saved[id] = mutableListOf()
            return id
        }

        override suspend fun turns(conversationId: Long): List<Turn> = saved.getValue(conversationId).toList()

        override suspend fun appendTurn(conversationId: Long, turn: Turn) {
            saved.getValue(conversationId) += turn
        }

        override suspend fun truncate(conversationId: Long, fromIndex: Int) {
            val list = saved.getValue(conversationId)
            while (list.size > fromIndex) list.removeAt(list.lastIndex)
        }

        override suspend fun deleteConversation(conversationId: Long) {
            titles.remove(conversationId)
            saved.remove(conversationId)
        }

        override suspend fun factTexts(): List<String> = facts.toList()
    }

    /** Decodes nothing: each path maps to a fixed input, a path it does not know is a missing file. */
    private class FakeMedia : MediaLoader {
        val images = mutableMapOf<String, ImageInput>()
        val clips = mutableMapOf<String, AudioInput>()
        val audioRates = mutableListOf<Int>()

        override fun image(path: String) = images[path] ?: throw java.io.FileNotFoundException(path)

        override fun audio(path: String, sampleRate: Int): AudioInput {
            audioRates += sampleRate
            return clips[path] ?: throw java.io.FileNotFoundException(path)
        }
    }

    private class RecordingSink : ReplySink {
        val audio = mutableListOf<FloatArray>()
        /** Per finished reply: its text, completed, spoke. */
        val finished = mutableListOf<Triple<String, Boolean, Boolean>>()
        override fun audio(samples: FloatArray) { audio += samples }
        override fun finished(text: String, completed: Boolean, spoke: Boolean) { finished += Triple(text, completed, spoke) }
    }

    private val engine = FakeEngine()
    private val store = FakeStore()
    private val media = FakeMedia()
    /** Saved documents by id, for the controller's [DocumentLoader]. */
    private val docs = mutableMapOf<String, ExtractedDocument>()
    private val documents = DocumentLoader { docs[it] }
    private val sink = RecordingSink()
    private val params = GenerationParams()

    private fun TestScope.controller() = ChatController(
        engine, store, this, StandardTestDispatcher(testScheduler), media = media, documents = documents, sink = sink,
        clock = { testScheduler.currentTime },
    )

    private val omni = Capabilities(vision = true, audioIn = true, audioOut = true, audioInSampleRate = 16000, audioOutSampleRate = 24000)

    /** Sends [text] and lets the engine answer [reply] in full. */
    private fun TestScope.exchange(c: ChatController, text: String, reply: String) {
        engine.tokens = Channel(Channel.UNLIMITED)
        engine.reply(reply)
        c.send(text, params)
        advanceUntilIdle()
    }

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

    @Test fun `switching the accelerator reloads the model once, even when the NPU falls back`() = runTest {
        var accelerator = Accelerator.CPU
        val c = ChatController(engine, store, this, StandardTestDispatcher(testScheduler), accelerator = { accelerator })
        c.ensureLoaded("/m.gguf", 8192)
        c.ensureLoaded("/m.gguf", 8192)
        assertEquals(listOf(Accelerator.CPU), engine.configs.map { it.accelerator })

        accelerator = Accelerator.NPU
        c.ensureLoaded("/m.gguf", 8192)
        c.ensureLoaded("/m.gguf", 8192)
        assertEquals(listOf(Accelerator.CPU, Accelerator.NPU), engine.configs.map { it.accelerator })
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

    @Test fun `the first message saves a conversation titled after it, with both turns`() = runTest {
        val c = controller()
        c.ready()
        exchange(c, "What is a heat pump and how does it work in winter?", "It moves heat.")

        val id = c.state.value.conversationId!!
        assertEquals("What is a heat pump and how does it work…", store.titles[id])
        assertEquals(c.state.value.turns, store.saved[id])
        assertNotNull("stats are saved with the reply", store.saved.getValue(id)[1].stats)

        exchange(c, "more", "Sure")
        assertEquals("later turns go to the same conversation", listOf(id), store.titles.keys.toList())
        assertEquals(4, store.saved.getValue(id).size)
    }

    @Test fun `regenerate replaces the last reply and resends the history up to its question`() = runTest {
        val c = controller()
        c.ready()
        exchange(c, "hi", "Hello")
        exchange(c, "joke?", "Bad joke")

        engine.tokens = Channel(Channel.UNLIMITED)
        engine.reply("Good joke")
        c.regenerate(params)
        advanceUntilIdle()

        assertEquals(
            listOf(
                ChatMessage(Role.SYSTEM, SYSTEM_PROMPT),
                ChatMessage(Role.USER, "hi"),
                ChatMessage(Role.ASSISTANT, "Hello"),
                ChatMessage(Role.USER, "joke?"),
            ),
            engine.sent,
        )
        val texts = listOf("hi", "Hello", "joke?", "Good joke")
        assertEquals(texts, c.state.value.turns.map { it.text })
        assertEquals(texts, store.saved.getValue(c.state.value.conversationId!!).map { it.text })
    }

    @Test fun `regenerate after a failed reply asks again`() = runTest {
        val c = controller()
        c.ready()
        engine.chatFailure = EngineException("context full")
        exchange(c, "hi", "")
        engine.chatFailure = null

        engine.tokens = Channel(Channel.UNLIMITED)
        engine.reply("Hello")
        c.regenerate(params)
        advanceUntilIdle()

        assertEquals(listOf("hi", "Hello"), c.state.value.turns.map { it.text })
        assertEquals(2, engine.chats)
    }

    @Test fun `regenerate and edit are ignored while a reply is streaming`() = runTest {
        val c = controller()
        c.ready()
        exchange(c, "hi", "Hello")
        engine.tokens = Channel(Channel.UNLIMITED)
        c.send("more", params)
        runCurrent()
        c.regenerate(params)
        c.editAndResend("other", params)
        engine.reply("Sure")
        advanceUntilIdle()

        assertEquals(listOf("hi", "Hello", "more", "Sure"), c.state.value.turns.map { it.text })
        assertEquals(2, engine.chats)
    }

    @Test fun `edit and resend replaces the last question and its reply`() = runTest {
        val c = controller()
        c.ready()
        exchange(c, "hi", "Hello")
        exchange(c, "wether in Paris?", "Did you mean weather?")

        engine.tokens = Channel(Channel.UNLIMITED)
        engine.reply("Mild")
        c.editAndResend("  weather in Paris?  ", params)
        advanceUntilIdle()

        assertEquals(ChatMessage(Role.USER, "weather in Paris?"), engine.sent!!.last())
        assertEquals(4, engine.sent!!.size)
        val texts = listOf("hi", "Hello", "weather in Paris?", "Mild")
        assertEquals(texts, c.state.value.turns.map { it.text })
        assertEquals(texts, store.saved.getValue(c.state.value.conversationId!!).map { it.text })
    }

    @Test fun `opening a conversation restores its turns and continues it`() = runTest {
        val c = controller()
        c.ready()
        exchange(c, "first chat", "One")
        val first = c.state.value.conversationId!!
        c.newChat()
        advanceUntilIdle()
        assertNull(c.state.value.conversationId)
        assertEquals(emptyList<Turn>(), c.state.value.turns)

        exchange(c, "second chat", "Two")
        val second = c.state.value.conversationId!!
        assertTrue(first != second)
        assertEquals("second chat", store.titles[second])

        c.open(first)
        advanceUntilIdle()
        assertEquals(first, c.state.value.conversationId)
        assertEquals(listOf("first chat", "One"), c.state.value.turns.map { it.text })

        exchange(c, "again", "Three")
        assertEquals(
            listOf(Role.SYSTEM, Role.USER, Role.ASSISTANT, Role.USER),
            engine.sent!!.map { it.role },
        )
        assertEquals("first chat", engine.sent!![1].content)
        assertEquals(4, store.saved.getValue(first).size)
        assertEquals(2, store.saved.getValue(second).size)
    }

    @Test fun `opening another conversation mid-reply keeps the partial reply in the old one`() = runTest {
        val c = controller()
        c.ready()
        exchange(c, "old", "Done")
        val old = c.state.value.conversationId!!
        c.newChat()
        advanceUntilIdle()
        engine.tokens = Channel(Channel.UNLIMITED)
        c.send("new", params)
        engine.tokens.send("Par")
        runCurrent()
        val current = c.state.value.conversationId!!

        c.open(old)
        advanceUntilIdle()

        assertEquals(listOf("old", "Done"), c.state.value.turns.map { it.text })
        assertFalse(c.state.value.generating)
        assertEquals(listOf("new", "Par"), store.saved.getValue(current).map { it.text })
    }

    @Test fun `deleting the open conversation starts a new chat`() = runTest {
        val c = controller()
        c.ready()
        exchange(c, "hi", "Hello")
        val id = c.state.value.conversationId!!

        c.deleteConversation(id)
        advanceUntilIdle()

        assertNull(c.state.value.conversationId)
        assertEquals(emptyList<Turn>(), c.state.value.turns)
        assertFalse(store.saved.containsKey(id))
    }

    @Test fun `remembered facts are appended to the system prompt`() = runTest {
        store.facts += listOf("My name is Van.", "I live in  Lyon.")
        val c = controller()
        c.ready()
        exchange(c, "hi", "Hello Van")

        assertEquals(
            ChatMessage(
                Role.SYSTEM,
                "$SYSTEM_PROMPT\n\n${SystemPrompt.FACTS_HEADER}\n- My name is Van.\n- I live in Lyon.",
            ),
            engine.sent!!.first(),
        )
    }

    @Test fun `custom instructions reach the system message, read for each reply`() = runTest {
        var custom = "Answer in French."
        val c = ChatController(
            engine, store, this, StandardTestDispatcher(testScheduler), media = media, sink = sink,
            customInstructions = { custom },
        )
        c.ready()
        exchange(c, "hi", "Bonjour")
        assertEquals(
            ChatMessage(Role.SYSTEM, "$SYSTEM_PROMPT\n\n${SystemPrompt.CUSTOM_HEADER}\nAnswer in French."),
            engine.sent!!.first(),
        )

        custom = "Be brief."
        exchange(c, "again", "Ok")
        assertEquals("$SYSTEM_PROMPT\n\n${SystemPrompt.CUSTOM_HEADER}\nBe brief.", engine.sent!!.first().content)
    }

    @Test fun `a stop before the reply reaches the engine starts no reply`() = runTest {
        val c = controller()
        c.ready()
        c.send("hi", params)
        c.stop()
        advanceUntilIdle()

        assertEquals(0, engine.chats)
        assertFalse(c.state.value.generating)
        assertEquals(listOf("hi"), c.state.value.turns.map { it.text })
    }

    @Test fun `attachments are sent to the engine as images and audio on the user message`() = runTest {
        engine.capabilities.value = omni
        val photo = ImageInput(1, 1, byteArrayOf(1, 2, 3))
        val clip = AudioInput(floatArrayOf(0.5f))
        media.images["/a/p.jpg"] = photo
        media.clips["/a/v.wav"] = clip
        val c = controller()
        c.ready()
        engine.reply("A cat")
        val attachments = listOf(Attachment("/a/p.jpg", AttachmentKind.IMAGE), Attachment("/a/v.wav", AttachmentKind.AUDIO))
        assertTrue(c.send("what is this?", params, attachments))
        advanceUntilIdle()

        val user = engine.sent!!.last()
        assertEquals("what is this?", user.content)
        assertEquals(listOf(photo), user.images)
        assertEquals(listOf(clip), user.audio)
        assertEquals(listOf(16000), media.audioRates)
        assertEquals(attachments, c.state.value.turns[0].attachments)
        assertEquals(attachments, store.saved.getValue(1L)[0].attachments)
    }

    @Test fun `media the model cannot take is dropped with a note`() = runTest {
        engine.capabilities.value = Capabilities.NONE
        media.images["/a/p.jpg"] = ImageInput(1, 1, byteArrayOf(1, 2, 3))
        media.clips["/a/v.wav"] = AudioInput(floatArrayOf(0f))
        val c = controller()
        c.ready()
        engine.reply("ok")
        c.send("hi", params, listOf(Attachment("/a/p.jpg", AttachmentKind.IMAGE), Attachment("/a/v.wav", AttachmentKind.AUDIO)))
        advanceUntilIdle()

        val user = engine.sent!!.last()
        assertTrue(user.images.isEmpty() && user.audio.isEmpty())
        assertEquals("hi\n\n${MediaNotes.IMAGE_UNSUPPORTED}\n\n${MediaNotes.AUDIO_UNSUPPORTED}", user.content)
        assertTrue(media.audioRates.isEmpty())
    }

    @Test fun `an attachment whose file is gone becomes a note`() = runTest {
        engine.capabilities.value = omni
        val c = controller()
        c.ready()
        engine.reply("ok")
        c.send("", params, listOf(Attachment("/gone.jpg", AttachmentKind.IMAGE)))
        advanceUntilIdle()

        val user = engine.sent!!.last()
        assertEquals(MediaNotes.IMAGE_MISSING, user.content)
        assertTrue(user.images.isEmpty())
        assertNull(c.state.value.error)
    }

    private val report = ExtractedDocument("report.pdf", DocumentFormat.PDF_MIME, listOf("Revenue grew 12% in Q3."))

    @Test fun `a document's text goes in front of the user's text, which is saved as written`() = runTest {
        docs["d1"] = report
        val c = controller()
        c.ready()
        engine.reply("12%")
        c.send("How much did revenue grow?", params, listOf(Attachment("d1", AttachmentKind.DOCUMENT, "report.pdf")))
        advanceUntilIdle()

        val user = engine.sent!!.last()
        assertEquals("From report.pdf (full text):\nRevenue grew 12% in Q3.\n\nHow much did revenue grow?", user.content)
        assertTrue(user.images.isEmpty() && user.audio.isEmpty())
        assertEquals("How much did revenue grow?", c.state.value.turns[0].text)
    }

    @Test fun `a message with only a document routes to the text model and is named after it`() = runTest {
        docs["d1"] = report
        val models = object : ModelTargets {
            override suspend fun text() = LoadTarget("/text.gguf", null, 8192)
            override suspend fun media() = LoadTarget("/omni.gguf", "/omni-mmproj.gguf", 8192)
        }
        val c = ChatController(
            engine, store, this, StandardTestDispatcher(testScheduler), media = media, documents = documents, models = models,
        )
        engine.reply("A report")
        assertTrue(c.send("", params, listOf(Attachment("d1", AttachmentKind.DOCUMENT, "report.pdf"))))
        advanceUntilIdle()

        assertEquals(listOf("/text.gguf"), engine.loads.map { it.first })
        assertEquals("report.pdf", store.titles.getValue(1L))
    }

    @Test fun `a document that is gone becomes a note`() = runTest {
        val c = controller()
        c.ready()
        engine.reply("ok")
        c.send("summarize it", params, listOf(Attachment("d-gone", AttachmentKind.DOCUMENT, "old.pdf")))
        advanceUntilIdle()

        assertEquals("summarize it\n\n${MediaNotes.DOCUMENT_MISSING}", engine.sent!!.last().content)
        assertNull(c.state.value.error)
    }

    @Test fun `a message with only an attachment is sent and names the chat`() = runTest {
        engine.capabilities.value = omni
        media.clips["/a/v.wav"] = AudioInput(floatArrayOf(0f))
        val c = controller()
        c.ready()
        assertFalse(c.send("  ", params))
        engine.reply("Hi")
        assertTrue(c.send("  ", params, listOf(Attachment("/a/v.wav", AttachmentKind.AUDIO))))
        advanceUntilIdle()

        assertEquals("Voice message", store.titles.getValue(1L))
        assertEquals(listOf(Role.USER, Role.ASSISTANT), c.state.value.turns.map { it.role })
    }

    @Test fun `speech chunks reach the sink and the finished reply is reported`() = runTest {
        engine.capabilities.value = omni
        val a = floatArrayOf(0.1f, 0.2f)
        val b = floatArrayOf(0.3f)
        engine.speech = listOf(a, b)
        val c = controller()
        c.ready()
        exchange(c, "hi", "Hello")

        assertEquals(listOf(a, b), sink.audio)
        assertEquals("finished, and spoken by the model", listOf(Triple("Hello", true, true)), sink.finished)
        assertEquals("Hello", c.state.value.turns.last().text)
    }

    @Test fun `a stopped reply is reported as not completed`() = runTest {
        val c = controller()
        c.ready()
        c.send("hi", params)
        engine.tokens.send("Hel")
        runCurrent()
        c.stop()
        advanceUntilIdle()

        assertEquals("not completed, and not spoken", listOf(Triple("Hel", false, false)), sink.finished)
    }

    @Test fun `a finished reply without speech is reported as not spoken`() = runTest {
        engine.capabilities.value = omni
        val c = controller()
        c.ready()
        exchange(c, "hi", "Hello")

        assertEquals(listOf(Triple("Hello", true, false)), sink.finished)
    }

    @Test fun `thinking streams apart from the reply and is kept on the turn with how long it took`() = runTest {
        val c = controller()
        c.ready()
        engine.thinking = listOf("17 * 23 ", "= 391.")
        engine.thinkingMs = 1500
        c.send("What is 17 * 23?", params)
        runCurrent()
        assertEquals("17 * 23 = 391.", c.state.value.streamingThinking)
        assertEquals("", c.state.value.streaming)
        assertTrue("thinking is on by default", engine.sentParams!!.think)

        engine.reply("391.")
        advanceUntilIdle()

        val s = c.state.value
        val answer = s.turns.last()
        assertEquals("391.", answer.text)
        assertEquals("17 * 23 = 391.", answer.thinking)
        assertEquals(1500L, answer.thinkingMillis)
        assertNull(s.streamingThinking)
        assertEquals(answer, store.saved.getValue(1L).last())
        assertEquals("only the answer is spoken", "391.", sink.finished.single().first)
    }

    @Test fun `earlier thinking is never sent back to the engine`() = runTest {
        val c = controller()
        c.ready()
        engine.thinking = listOf("The user greets me.")
        exchange(c, "hi", "Hello!")
        assertEquals("The user greets me.", c.state.value.turns.last().thinking)

        engine.thinking = emptyList()
        exchange(c, "how are you?", "Fine.")

        val sent = engine.sent!!
        assertEquals(listOf(Role.SYSTEM, Role.USER, Role.ASSISTANT, Role.USER), sent.map { it.role })
        assertEquals("Hello!", sent[2].content)
        assertTrue(sent.none { it.content.contains("greets") })
    }

    @Test fun `the think setting reaches the engine`() = runTest {
        val c = controller()
        c.ready()
        c.think = false
        exchange(c, "hi", "Hello")
        assertFalse(engine.sentParams!!.think)

        c.think = true
        exchange(c, "again", "Hello")
        assertTrue(engine.sentParams!!.think)
    }

    /** Three exchanges, six turns: q1 a1 q2 a2 q3 a3. */
    private suspend fun TestScope.threeExchanges(c: ChatController) {
        c.ready()
        exchange(c, "q1", "a1")
        exchange(c, "q2", "a2")
        exchange(c, "q3", "a3")
    }

    @Test fun `a prompt at 85 percent of the context is compacted before the reply`() = runTest {
        val c = controller()
        threeExchanges(c)
        assertTrue(engine.summaries.isEmpty())
        engine.tokenCount = 8192 * 85 / 100 + 1
        engine.summaryText = "  The user asked q1.  "
        exchange(c, "q4", "a4")

        // The last four turns (a2 q3 a3 q4) are kept from q2, the user's message they start after.
        // The model summarized q1 a1, without thinking and with a short budget.
        val (request, summaryParams) = engine.summaries.single()
        assertTrue(request[1].content.contains("User: q1"))
        assertTrue(request[1].content.contains("Assistant: a1"))
        assertFalse(request[1].content.contains("q2"))
        assertFalse(summaryParams.think)
        assertEquals(400, summaryParams.maxNewTokens)

        // The engine then sees the summary and only the turns after it.
        assertEquals(
            listOf(
                ChatMessage(Role.SYSTEM, SYSTEM_PROMPT),
                ChatMessage(Role.SYSTEM, "${Compaction.SUMMARY_HEADER}\nThe user asked q1."),
                ChatMessage(Role.USER, "q2"),
                ChatMessage(Role.ASSISTANT, "a2"),
                ChatMessage(Role.USER, "q3"),
                ChatMessage(Role.ASSISTANT, "a3"),
                ChatMessage(Role.USER, "q4"),
            ),
            engine.sent,
        )
        // Every turn stays in the conversation, the summary in front of the turns kept, and is saved so.
        val turns = c.state.value.turns
        assertEquals(listOf("q1", "a1", "The user asked q1.", "q2", "a2", "q3", "a3", "q4", "a4"), turns.map { it.text })
        assertTrue(turns[2].summary && turns[2].role == Role.SYSTEM)
        assertEquals(turns, store.saved.getValue(c.state.value.conversationId!!))
        assertNull(c.state.value.error)
    }

    @Test fun `below 85 percent nothing is compacted`() = runTest {
        val c = controller()
        threeExchanges(c)
        engine.tokenCount = 8192 * 85 / 100 - 1
        exchange(c, "q4", "a4")
        assertTrue(engine.summaries.isEmpty())
        assertEquals(8, engine.sent!!.size)
        assertFalse(c.state.value.turns.any { it.summary })
    }

    @Test fun `a summary that fails sends the turn uncompacted without an error`() = runTest {
        val c = controller()
        threeExchanges(c)
        engine.tokenCount = 8000
        engine.summaryFailure = EngineException("boom")
        exchange(c, "q4", "a4")
        assertEquals(1, engine.summaries.size)
        assertEquals(listOf("q1", "a1", "q2", "a2", "q3", "a3", "q4"), engine.sent!!.drop(1).map { it.content })
        assertEquals(listOf("q1", "a1", "q2", "a2", "q3", "a3", "q4", "a4"), c.state.value.turns.map { it.text })
        assertNull(c.state.value.error)
    }

    @Test fun `compact now summarizes below the threshold`() = runTest {
        val c = controller()
        c.ready()
        exchange(c, "q1", "a1")
        exchange(c, "q2", "a2")
        // Four turns: nothing to compact yet.
        assertFalse(c.state.value.canCompact)
        exchange(c, "q3", "a3")
        assertTrue(c.state.value.canCompact)

        engine.tokenCount = 100
        c.compact()
        assertTrue(c.state.value.compacting)
        assertTrue(c.state.value.busy)
        advanceUntilIdle()
        assertFalse(c.state.value.compacting)
        assertEquals(1, engine.summaries.size)
        val turns = c.state.value.turns
        assertEquals(listOf(false, false, true, false, false, false, false), turns.map { it.summary })
        assertEquals(turns, store.saved.getValue(c.state.value.conversationId!!))
        assertFalse(c.state.value.canCompact)
        assertEquals(100, c.state.value.contextUsed)

        exchange(c, "q4", "a4")
        assertEquals(listOf("q2", "a2", "q3", "a3", "q4"), engine.sent!!.drop(2).map { it.content })
        assertEquals(1, engine.summaries.size)
    }

    @Test fun `a second compaction summarizes the first summary with the turns after it`() = runTest {
        val c = controller()
        threeExchanges(c)
        engine.summaryText = "first"
        c.compact()
        advanceUntilIdle()
        exchange(c, "q4", "a4")
        exchange(c, "q5", "a5")
        engine.summaryText = "second"
        c.compact()
        advanceUntilIdle()

        val request = engine.summaries.last().first[1].content
        assertTrue(request.contains("first"))
        assertTrue(request.contains("User: q2"))
        assertTrue(request.contains("User: q3"))
        assertFalse(request.contains("q1"))
        assertFalse(request.contains("q4"))
        exchange(c, "q6", "a6")
        assertEquals(
            listOf("${Compaction.SUMMARY_HEADER}\nsecond", "q4", "a4", "q5", "a5", "q6"),
            engine.sent!!.drop(1).map { it.content },
        )
    }

    @Test fun `context used is counted after each reply and on opening a chat, estimated when the engine cannot count`() = runTest {
        val c = controller()
        c.ready()
        exchange(c, "q1", "a1")
        // The fake cannot count by default: the newest reply's stats, prompt plus generated.
        assertEquals(12, c.state.value.contextUsed)

        engine.tokenCount = 321
        exchange(c, "q2", "a2")
        assertEquals(321, c.state.value.contextUsed)
        assertEquals(listOf(Role.SYSTEM, Role.USER, Role.ASSISTANT, Role.USER, Role.ASSISTANT), engine.counted.last().map { it.role })

        val id = c.state.value.conversationId!!
        c.newChat()
        advanceUntilIdle()
        assertEquals(0, c.state.value.contextUsed)
        engine.tokenCount = 654
        c.open(id)
        advanceUntilIdle()
        assertEquals(654, c.state.value.contextUsed)
    }

    @Test fun `a reopened chat sends its summary and the turns after it`() = runTest {
        val id = store.createConversation("old")
        store.saved.getValue(id) += listOf(
            Turn(Role.USER, "q1"), Turn(Role.ASSISTANT, "a1"),
            Turn(Role.SYSTEM, "earlier", summary = true),
            Turn(Role.USER, "q2"), Turn(Role.ASSISTANT, "a2"),
        )
        val c = controller()
        c.ready()
        c.open(id)
        advanceUntilIdle()
        exchange(c, "q3", "a3")
        assertEquals(
            listOf(SYSTEM_PROMPT, "${Compaction.SUMMARY_HEADER}\nearlier", "q2", "a2", "q3"),
            engine.sent!!.map { it.content },
        )
    }

    @Test fun `speak is passed only when the model can speak`() = runTest {
        val c = controller()
        c.ready()
        engine.capabilities.value = omni.copy(audioOut = false, audioOutSampleRate = 0)
        engine.tokens = Channel(Channel.UNLIMITED)
        engine.reply("a")
        c.send("hi", GenerationParams(speak = true))
        advanceUntilIdle()
        assertEquals(false, engine.sentParams!!.speak)

        engine.capabilities.value = omni
        engine.tokens = Channel(Channel.UNLIMITED)
        engine.reply("b")
        c.send("again", GenerationParams(speak = true))
        advanceUntilIdle()
        assertEquals(true, engine.sentParams!!.speak)

        engine.tokens = Channel(Channel.UNLIMITED)
        engine.reply("c")
        c.send("quiet", GenerationParams(speak = false))
        advanceUntilIdle()
        assertEquals(false, engine.sentParams!!.speak)
    }
}
