package com.vanskis.liquidpocket.chat

import com.vanskis.liquidpocket.core.ContextSize
import com.vanskis.liquidpocket.engine.AudioInput
import com.vanskis.liquidpocket.engine.Capabilities
import com.vanskis.liquidpocket.engine.ChatMessage
import com.vanskis.liquidpocket.engine.Engine
import com.vanskis.liquidpocket.engine.EngineConfig
import com.vanskis.liquidpocket.engine.EngineState
import com.vanskis.liquidpocket.engine.GenerationParams
import com.vanskis.liquidpocket.engine.ImageInput
import com.vanskis.liquidpocket.engine.ReplyChunk
import com.vanskis.liquidpocket.engine.Role
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class ChatState(
    /** The open conversation, or null for a new chat that is saved when its first message is sent. */
    val conversationId: Long? = null,
    val turns: List<Turn> = emptyList(),
    /** The reply being streamed, or null when not generating. */
    val streaming: String? = null,
    val loading: Boolean = false,
    val error: String? = null,
) {
    val generating: Boolean get() = streaming != null

    /** Index of the newest user turn, -1 if there is none. Regenerate and edit act on it. */
    val lastUserIndex: Int get() = turns.indexOfLast { it.role == Role.USER }
}

/**
 * The open conversation, its use of [engine] and its persistence in [store]. Plain Kotlin so it is
 * unit-tested on the JVM; [ChatViewModel] runs it in its scope.
 *
 * [work] runs model loading and the token stream off the caller's (main) thread. Each operation
 * that touches [store] waits for the previous one, so writes land in the order they were made.
 * [media] decodes attachments for the engine; [sink] gets the reply's speech and its end.
 */
class ChatController(
    private val engine: Engine,
    private val store: ChatStore,
    private val scope: CoroutineScope,
    private val work: CoroutineDispatcher,
    private val media: MediaLoader = MediaLoader.NONE,
    private val sink: ReplySink = ReplySink.NONE,
) {
    private val _state = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = _state.asStateFlow()

    private val loadMutex = Mutex()

    /** The newest operation; the next one joins it first. */
    private var job: Job? = null

    /** Set by [stop] so a reply whose operation has not reached the engine yet is not started. */
    private var stopRequested = false

    /** The request behind the engine's current model, as the engine may clamp the context size. */
    private var loadedFor: Pair<String, Int>? = null

    /** Loads [modelPath] (with its projector [mmprojPath]) and [contextSize] unless the engine already has exactly that loaded. */
    suspend fun ensureLoaded(modelPath: String, contextSize: Int, mmprojPath: String? = null) = loadMutex.withLock {
        val request = modelPath to contextSize
        val alreadyLoaded = when (val current = engine.state.value) {
            is EngineState.Ready -> current.modelPath == modelPath &&
                (current.contextSize == ContextSize.clamp(contextSize) || loadedFor == request)
            EngineState.Generating -> loadedFor == request
            else -> false
        }
        if (alreadyLoaded) return@withLock
        stopAndWait(job)
        _state.update { it.copy(loading = true) }
        try {
            withContext(work) { engine.load(modelPath, EngineConfig(contextSize, mmprojPath)) }
            loadedFor = request
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            loadedFor = null
            _state.update { it.copy(error = "Could not load the model: ${e.message ?: e.javaClass.simpleName}") }
        } finally {
            _state.update { it.copy(loading = false) }
        }
    }

    /**
     * Appends a user turn with [attachments] and streams the reply. Ignored (false) while loading or
     * generating, or with neither text nor attachments.
     */
    fun send(text: String, params: GenerationParams, attachments: List<Attachment> = emptyList()): Boolean {
        val prompt = text.trim()
        val now = _state.value
        if ((prompt.isEmpty() && attachments.isEmpty()) || busy(now)) return false
        reply(keep = now.turns.size, newUser = Turn(Role.USER, prompt, attachments = attachments), params = params)
        return true
    }

    /** Replaces the reply to the newest user turn (or adds one, if it has none) with a new one. */
    fun regenerate(params: GenerationParams) {
        val now = _state.value
        val last = now.lastUserIndex
        if (last < 0 || busy(now)) return
        reply(keep = last + 1, newUser = null, params = params)
    }

    /** Replaces the newest user turn with [text], drops what followed it, and streams a new reply. */
    fun editAndResend(text: String, params: GenerationParams) {
        val prompt = text.trim()
        val now = _state.value
        val last = now.lastUserIndex
        if (prompt.isEmpty() || last < 0 || busy(now)) return
        val edited = now.turns[last].copy(text = prompt)
        reply(keep = last, newUser = edited, params = params)
    }

    /** Ends the reply early; the text so far is kept as the assistant turn. */
    fun stop() {
        if (_state.value.generating) {
            stopRequested = true
            engine.stop()
        }
    }

    /** Starts a new, unsaved conversation. */
    fun newChat() = after { previous ->
        stopAndWait(previous)
        _state.update { it.copy(conversationId = null, turns = emptyList(), error = null) }
    }

    /** Opens a saved conversation with all its turns. */
    fun open(conversationId: Long) = after { previous ->
        stopAndWait(previous)
        val turns = persist { store.turns(conversationId) } ?: return@after
        _state.update { it.copy(conversationId = conversationId, turns = turns, error = null) }
    }

    /** Deletes a saved conversation; when it is the open one, a new chat takes its place. */
    fun deleteConversation(conversationId: Long) = after { previous ->
        if (_state.value.conversationId == conversationId) {
            stopAndWait(previous)
            _state.update { it.copy(conversationId = null, turns = emptyList(), error = null) }
        } else {
            previous?.join()
        }
        persist { store.deleteConversation(conversationId) }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    /** Reports an error that happened outside the controller (e.g. the model file is missing). */
    fun showError(message: String) = _state.update { it.copy(error = message) }

    private fun busy(state: ChatState) = state.loading || state.generating

    /**
     * Keeps the first [keep] turns, appends [newUser] if given, and streams the assistant's reply to
     * the result. The store is brought to the same turns before the engine starts.
     */
    private fun reply(keep: Int, newUser: Turn?, params: GenerationParams) {
        val before = _state.value
        val kept = before.turns.take(keep)
        val turns = if (newUser == null) kept else kept + newUser
        val dropped = before.turns.size > keep
        stopRequested = false
        _state.update { it.copy(turns = turns, streaming = "", error = null) }
        val statsBefore = engine.lastStats.value
        after { previous ->
            previous?.join()
            var conversationId = before.conversationId
            var failure: String? = null
            try {
                persist {
                    val id = conversationId
                        ?: store.createConversation(ConversationTitle.from(turns.first { it.role == Role.USER }.titleText()))
                    conversationId = id
                    if (dropped) store.truncate(id, keep)
                    if (newUser != null) store.appendTurn(id, newUser)
                }
                _state.update { it.copy(conversationId = conversationId) }
                if (stopRequested) return@after
                val facts = persist { store.factTexts() }.orEmpty()
                val caps = engine.capabilities.value
                val history = withContext(work) {
                    listOf(ChatMessage(Role.SYSTEM, SystemPrompt.build(facts))) + turns.map { it.toMessage(caps) }
                }
                engine.chat(history, params.copy(speak = params.speak && caps.audioOut))
                    // Caught before flowOn so text already buffered still arrives after a failure.
                    .catch { e ->
                        if (e is CancellationException) throw e
                        failure = "Generation failed: ${e.message ?: e.javaClass.simpleName}"
                    }
                    .flowOn(work)
                    .collect { chunk ->
                        when (chunk) {
                            is ReplyChunk.Text -> _state.update { it.copy(streaming = (it.streaming ?: "") + chunk.text) }
                            is ReplyChunk.Audio -> sink.audio(chunk.samples)
                            // Tool calls are for a later milestone.
                            is ReplyChunk.ToolCall -> Unit
                        }
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = "Generation failed: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                // Stats belong to this reply only if the engine published new ones for it.
                val stats = engine.lastStats.value?.takeIf { failure == null && it !== statsBefore }
                val text = _state.value.streaming.orEmpty()
                val replyTurn = if (text.isEmpty()) null else Turn(Role.ASSISTANT, text, stats)
                _state.update {
                    it.copy(
                        turns = if (replyTurn == null) it.turns else it.turns + replyTurn,
                        streaming = null,
                        error = failure ?: it.error,
                    )
                }
                if (replyTurn != null) sink.finished(text, completed = failure == null && !stopRequested)
                val id = conversationId
                if (replyTurn != null && id != null) {
                    withContext(NonCancellable) { persist { store.appendTurn(id, replyTurn) } }
                }
            }
        }
    }

    /** Launches [block] as the newest operation; it gets the previous one to wait for or stop. */
    private fun after(block: suspend (previous: Job?) -> Unit) {
        val previous = job
        job = scope.launch { block(previous) }
    }

    private suspend fun stopAndWait(previous: Job?) {
        if (previous == null) return
        if (previous.isActive) {
            stopRequested = true
            engine.stop()
        }
        previous.join()
    }

    /** Runs a [store] call; a failure becomes a visible error and yields null. */
    private suspend fun <T> persist(block: suspend () -> T): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(error = "Could not save the chat: ${e.message ?: e.javaClass.simpleName}") }
            null
        }

    /** Names a conversation that starts with only a photo or a voice message. */
    private fun Turn.titleText(): String = text.ifBlank {
        when (attachments.firstOrNull()?.kind) {
            AttachmentKind.IMAGE -> "Photo"
            AttachmentKind.AUDIO -> "Voice message"
            null -> text
        }
    }

    /**
     * A user turn's attachments become decoded images and audio the model takes; media it cannot
     * take, or whose file is gone, becomes a note in the text so the engine is never given it.
     */
    private fun Turn.toMessage(caps: Capabilities): ChatMessage {
        if (role != Role.USER || attachments.isEmpty()) {
            return ChatMessage(role, text, toolCalls = toolCalls, toolName = toolName)
        }
        val images = mutableListOf<ImageInput>()
        val audio = mutableListOf<AudioInput>()
        val notes = mutableListOf<String>()
        for (attachment in attachments) {
            when (attachment.kind) {
                AttachmentKind.IMAGE -> when {
                    !caps.vision -> notes += MediaNotes.IMAGE_UNSUPPORTED
                    else -> loadOrNull { media.image(attachment.path) }?.let { images += it } ?: run { notes += MediaNotes.IMAGE_MISSING }
                }
                AttachmentKind.AUDIO -> when {
                    !caps.audioIn -> notes += MediaNotes.AUDIO_UNSUPPORTED
                    else -> loadOrNull { media.audio(attachment.path, caps.audioInSampleRate) }?.let { audio += it }
                        ?: run { notes += MediaNotes.AUDIO_MISSING }
                }
            }
        }
        val content = (listOf(text) + notes.distinct()).filter { it.isNotBlank() }.joinToString("\n\n")
        return ChatMessage(role, content, images = images, audio = audio)
    }

    private inline fun <T> loadOrNull(load: () -> T): T? =
        try {
            load()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
}
