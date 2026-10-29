package com.vanskis.liquidpocket.chat

import com.vanskis.liquidpocket.core.ContextSize
import com.vanskis.liquidpocket.engine.ChatMessage
import com.vanskis.liquidpocket.engine.Engine
import com.vanskis.liquidpocket.engine.EngineConfig
import com.vanskis.liquidpocket.engine.EngineState
import com.vanskis.liquidpocket.engine.GenerationParams
import com.vanskis.liquidpocket.engine.GenerationStats
import com.vanskis.liquidpocket.engine.Role
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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

const val SYSTEM_PROMPT =
    "You are Liquid Pocket, a helpful assistant running fully offline on the user's phone. Be concise."

/** A finished turn. [stats] is set on assistant replies when the engine reported them. */
data class Turn(val role: Role, val text: String, val stats: GenerationStats? = null)

data class ChatState(
    val turns: List<Turn> = emptyList(),
    /** The reply being streamed, or null when not generating. */
    val streaming: String? = null,
    val loading: Boolean = false,
    val error: String? = null,
) {
    val generating: Boolean get() = streaming != null
}

/**
 * The conversation (in memory only) and its use of [engine]. Plain Kotlin so it is unit-tested
 * on the JVM; [ChatViewModel] runs it in its scope.
 *
 * [work] runs model loading and the token stream off the caller's (main) thread.
 */
class ChatController(
    private val engine: Engine,
    private val scope: CoroutineScope,
    private val work: CoroutineDispatcher,
) {
    private val _state = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = _state.asStateFlow()

    private val loadMutex = Mutex()
    private var generation: Job? = null

    /** The request behind the engine's current model, as the engine may clamp the context size. */
    private var loadedFor: Pair<String, Int>? = null

    /** Loads [modelPath] with [contextSize] unless the engine already has exactly that loaded. */
    suspend fun ensureLoaded(modelPath: String, contextSize: Int) = loadMutex.withLock {
        val request = modelPath to contextSize
        val alreadyLoaded = when (val current = engine.state.value) {
            is EngineState.Ready -> current.modelPath == modelPath &&
                (current.contextSize == ContextSize.clamp(contextSize) || loadedFor == request)
            EngineState.Generating -> loadedFor == request
            else -> false
        }
        if (alreadyLoaded) return@withLock
        stopAndWait()
        _state.update { it.copy(loading = true) }
        try {
            withContext(work) { engine.load(modelPath, EngineConfig(contextSize)) }
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

    /** Appends a user turn and streams the reply. Ignored while loading or generating. */
    fun send(text: String, params: GenerationParams) {
        val prompt = text.trim()
        val now = _state.value
        if (prompt.isEmpty() || now.loading || now.generating) return
        val turns = now.turns + Turn(Role.USER, prompt)
        _state.update { it.copy(turns = turns, streaming = "", error = null) }
        val history = listOf(ChatMessage(Role.SYSTEM, SYSTEM_PROMPT)) + turns.map { ChatMessage(it.role, it.text) }
        val statsBefore = engine.lastStats.value
        generation = scope.launch {
            var failure: String? = null
            try {
                engine.chat(history, params)
                    // Caught before flowOn so tokens already buffered still arrive after a failure.
                    .catch { e ->
                        if (e is CancellationException) throw e
                        failure = "Generation failed: ${e.message ?: e.javaClass.simpleName}"
                    }
                    .flowOn(work)
                    .collect { piece -> _state.update { it.copy(streaming = (it.streaming ?: "") + piece) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = "Generation failed: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                // Stats belong to this reply only if the engine published new ones for it.
                val stats = engine.lastStats.value?.takeIf { failure == null && it !== statsBefore }
                _state.update {
                    val reply = it.streaming.orEmpty()
                    it.copy(
                        turns = if (reply.isEmpty()) it.turns else it.turns + Turn(Role.ASSISTANT, reply, stats),
                        streaming = null,
                        error = failure ?: it.error,
                    )
                }
            }
        }
    }

    /** Ends the reply early; the text so far is kept as the assistant turn. */
    fun stop() {
        if (_state.value.generating) engine.stop()
    }

    fun newChat() {
        scope.launch {
            stopAndWait()
            _state.update { it.copy(turns = emptyList(), error = null) }
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    /** Reports an error that happened outside the controller (e.g. the model file is missing). */
    fun showError(message: String) = _state.update { it.copy(error = message) }

    private suspend fun stopAndWait() {
        val job = generation ?: return
        if (job.isActive) engine.stop()
        job.join()
    }
}
