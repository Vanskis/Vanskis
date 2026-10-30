package com.vanskis.liquidpocket.chat

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

/** Routing replies between the text model and the media model, one loaded at a time. */
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
            capabilities.value = if (modelPath == MEDIA.path) OMNI else Capabilities.NONE
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
            // As the native engine does, a model refuses media it cannot take.
            if (!capabilities.value.vision && messages.any { it.images.isNotEmpty() }) {
                throw EngineException("The loaded model cannot see images")
            }
            if (!capabilities.value.audioIn && messages.any { it.audio.isNotEmpty() }) {
                throw EngineException("The loaded model cannot hear audio")
            }
            val (text, calls) = replies.removeFirstOrNull() ?: ("Done" to emptyList())
            if (text.isNotEmpty()) emit(ReplyChunk.Text(text))
            hold?.let { it.await(); hold = null }
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

    private class Models(var textInstalled: Boolean = true, var mediaInstalled: Boolean = true) : ModelTargets {
        /** The context size set in Settings. */
        var contextSize = TEXT.contextSize
        override suspend fun text() = if (textInstalled) TEXT.copy(contextSize = contextSize) else null
        override suspend fun media() = if (mediaInstalled) MEDIA else null
    }

    private class Media : MediaLoader {
        override fun image(path: String) = ImageInput(1, 1, ByteArray(3))
        override fun audio(path: String, sampleRate: Int) = AudioInput(FloatArray(1))
    }

    /** Like look_at_photos: returns the photos for the model to see. */
    private class PhotoTool : Tool {
        override val spec = ToolSpec("look_at_photos", "Looks at photos.", """{"type":"object"}""")
        override val needsConfirmation = false
        override fun describe(argumentsJson: String) = "photos"
        override suspend fun run(argumentsJson: String) =
            ToolResult("""{"photos":1}""", images = listOf(ImageInput(1, 1, ByteArray(3))))
    }

    private val engine = SwapEngine()
    private val store = Store()
    private val models = Models()
    private val photos = PhotoTool()
    private val params = GenerationParams(tools = listOf(photos.spec), speak = true)
    private val photo = listOf(Attachment("/p.jpg", AttachmentKind.IMAGE))
    private val voice = listOf(Attachment("/v.wav", AttachmentKind.AUDIO))

    private fun TestScope.controller() =
        ChatController(engine, store, this, StandardTestDispatcher(testScheduler), listOf(photos), media = Media(), models = models)

    @Test fun `a text turn loads the text model and is answered there`() = runTest {
        val c = controller()
        c.send("What is 17 * 23?", params)
        advanceUntilIdle()

        assertEquals(listOf(TEXT.path), engine.loads)
        assertEquals(TEXT.path, engine.chats.single().first)
        val s = c.state.value
        assertEquals(listOf("What is 17 * 23?", "Done"), s.turns.map { it.text })
        assertNull(s.error)
        assertFalse(s.busy)
    }

    @Test fun `a photo turn goes to the media model`() = runTest {
        val c = controller()
        c.ensureLoaded()
        advanceUntilIdle()
        engine.loads.clear()
        c.send("What is this?", params, photo)
        advanceUntilIdle()

        assertEquals(listOf(MEDIA.path), engine.loads)
        assertEquals(MEDIA.path, engine.chats.single().first)
        assertTrue("the media model sees the photo", engine.sent.single().last().images.isNotEmpty())
        assertNull(c.state.value.error)
    }

    @Test fun `a voice turn goes to the media model, which hears the clip and may speak`() = runTest {
        val c = controller()
        c.send("", params, voice)
        advanceUntilIdle()

        val (model, sentParams) = engine.chats.single()
        assertEquals(MEDIA.path, model)
        assertTrue(engine.sent.single().last().audio.isNotEmpty())
        assertTrue(sentParams.speak)
        assertNull(c.state.value.error)
    }

    @Test fun `after a media reply the next text turn goes back to the text model, and not before`() = runTest {
        val c = controller()
        c.send("What is this?", params, photo)
        advanceUntilIdle()
        assertEquals("no swap back after the media reply", listOf(MEDIA.path), engine.loads)
        assertEquals(MEDIA.path, engine.loaded)

        c.send("And how old is it?", params)
        advanceUntilIdle()

        assertEquals(listOf(MEDIA.path, TEXT.path), engine.loads)
        assertEquals(TEXT.path, engine.chats.last().first)
        // The earlier photo reaches the text model as a note, not as pixels it would refuse.
        val earlier = engine.sent.last().first { it.role == Role.USER }
        assertTrue(earlier.images.isEmpty())
        assertTrue(earlier.content.endsWith(MediaNotes.IMAGE_UNSUPPORTED))
        assertNull(c.state.value.error)
    }

    @Test fun `without the media model a photo turn goes to the text model with a note`() = runTest {
        models.mediaInstalled = false
        val c = controller()
        c.send("What is this?", params, photo)
        advanceUntilIdle()

        assertEquals(listOf(TEXT.path), engine.loads)
        assertEquals(MediaNotes.IMAGE_UNSUPPORTED, engine.sent.single().last().content.lines().last())
        assertNull(c.state.value.error)
    }

    @Test fun `without the text model a text turn goes to the media model`() = runTest {
        models.textInstalled = false
        val c = controller()
        c.send("hi", params)
        advanceUntilIdle()

        assertEquals(listOf(MEDIA.path), engine.loads)
        assertEquals(MEDIA.path, engine.chats.single().first)
    }

    @Test fun `with no model installed the loaded model answers and nothing is loaded`() = runTest {
        models.textInstalled = false
        models.mediaInstalled = false
        val c = controller()
        c.ensureLoaded("/sideloaded.gguf", 8192)
        engine.loads.clear()
        c.ensureLoaded()
        c.send("What is this?", params, photo)
        advanceUntilIdle()

        assertTrue(engine.loads.isEmpty())
        assertEquals("/sideloaded.gguf", engine.chats.single().first)
    }

    @Test fun `a media model that fails to load shows the error and asks nothing`() = runTest {
        engine.failLoadOf = MEDIA.path
        val c = controller()
        c.send("What is this?", params, photo)
        advanceUntilIdle()

        val s = c.state.value
        assertTrue(engine.chats.isEmpty())
        assertTrue(s.error!!.contains("no memory"))
        assertEquals(listOf(Role.USER), s.turns.map { it.role })
        assertFalse(s.busy)
    }

    @Test fun `ensureLoaded resolves the model through the targets and loads it once`() = runTest {
        val c = controller()
        c.ensureLoaded()
        c.ensureLoaded()
        assertEquals("a new chat routes to the text model", listOf(TEXT.path), engine.loads)

        c.send("What is this?", params, photo)
        advanceUntilIdle()
        c.ensureLoaded()
        assertEquals("the newest turn has a photo: the media model stays", listOf(TEXT.path, MEDIA.path), engine.loads)

        c.newChat()
        advanceUntilIdle()
        c.ensureLoaded()
        assertEquals(listOf(TEXT.path, MEDIA.path, TEXT.path), engine.loads)
    }

    @Test fun `a new context size reloads the routed model`() = runTest {
        val c = controller()
        c.ensureLoaded()
        models.contextSize = 16384
        c.ensureLoaded()

        assertEquals(listOf(TEXT.path, TEXT.path), engine.loads)
        assertEquals(EngineState.Ready(TEXT.path, 16384), engine.state.value)
    }

    @Test fun `regenerating or editing a photo turn stays on the media model`() = runTest {
        val c = controller()
        c.send("What is this?", params, photo)
        advanceUntilIdle()

        c.ensureLoaded(TEXT.path, TEXT.contextSize)
        c.regenerate(params)
        advanceUntilIdle()
        assertEquals(MEDIA.path, engine.chats.last().first)

        c.ensureLoaded(TEXT.path, TEXT.contextSize)
        c.editAndResend("What breed is it?", params)
        advanceUntilIdle()
        assertEquals(MEDIA.path, engine.chats.last().first)
        assertEquals("the edited turn keeps its photo", photo, c.state.value.turns[c.state.value.lastUserIndex].attachments)
        assertTrue(engine.sent.last().last().images.isNotEmpty())
        assertEquals(listOf(MEDIA.path, TEXT.path, MEDIA.path, TEXT.path, MEDIA.path), engine.loads)
        assertNull(c.state.value.error)
    }

    @Test fun `opening the chat again during a media reply leaves it running`() = runTest {
        val c = controller()
        engine.hold = CompletableDeferred()
        engine.replies += "Let me look again." to listOf(ToolCall("look_at_photos", "{}"))
        engine.replies += "A tabby cat." to emptyList()
        c.send("What is this?", params, photo)
        advanceUntilIdle()
        assertTrue(c.state.value.generating)

        // What the chat screen does when it is shown again (a rotation, or back from Settings).
        launch { c.ensureLoaded() }
        advanceUntilIdle()
        assertEquals(MEDIA.path, engine.loaded)
        assertTrue(c.state.value.generating)

        engine.hold?.complete(Unit)
        advanceUntilIdle()

        val s = c.state.value
        assertEquals("A tabby cat.", s.turns.last().text)
        assertNull(s.error)
        assertEquals(listOf(MEDIA.path), engine.loads)
        assertFalse(s.busy)
    }

    @Test fun `think harder regenerates on the text model, thinking, with twice the tokens`() = runTest {
        val c = controller()
        c.think = false
        c.send("What is this?", params.copy(maxNewTokens = 512), photo)
        advanceUntilIdle()
        assertFalse("the setting reaches the engine", engine.chats.single().second.think)

        engine.replies += "A tabby cat, about two years old." to emptyList()
        c.thinkHarder(params.copy(maxNewTokens = 512))
        advanceUntilIdle()

        val (model, sentParams) = engine.chats.last()
        assertEquals(TEXT.path, model)
        assertTrue(sentParams.think)
        assertEquals(1024, sentParams.maxNewTokens)
        val s = c.state.value
        assertEquals(listOf("What is this?", "A tabby cat, about two years old."), s.turns.map { it.text })
        assertEquals(s.turns, store.saved.getValue(s.conversationId!!))
        assertFalse(s.busy)
    }

    private companion object {
        val TEXT = LoadTarget("/lfm3-1.7b.gguf", null, 8192)
        val MEDIA = LoadTarget("/lfm3-omni-2b.gguf", "/lfm3-omni-2b-mmproj.gguf", 8192)
        val OMNI = Capabilities(vision = true, audioIn = true, audioOut = true, audioInSampleRate = 16000, audioOutSampleRate = 24000)
    }
}
