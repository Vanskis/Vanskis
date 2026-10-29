package com.vanskis.liquidpocket.chat

import com.vanskis.liquidpocket.core.ContextSize
import com.vanskis.liquidpocket.engine.ChatMessage
import com.vanskis.liquidpocket.engine.Engine
import com.vanskis.liquidpocket.engine.EngineConfig
import com.vanskis.liquidpocket.engine.EngineState
import com.vanskis.liquidpocket.engine.GenerationParams
import com.vanskis.liquidpocket.engine.ReplyChunk
import com.vanskis.liquidpocket.engine.Role
import com.vanskis.liquidpocket.engine.ToolCall
import com.vanskis.liquidpocket.tools.Tool
import com.vanskis.liquidpocket.tools.ToolResult
import com.vanskis.liquidpocket.tools.ToolResults
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
    /** A tool call waiting for the user's approval; the reply is paused (still [generating]) meanwhile. */
    val confirmation: ToolConfirmation? = null,
) {
    val generating: Boolean get() = streaming != null

    /** Index of the newest turn the user wrote, -1 if there is none. Regenerate and edit act on it. */
    val lastUserIndex: Int get() = turns.indexOfLast { it.role == Role.USER && !it.fromTool }
}

/** What a write tool will do, shown on the card the user approves or cancels. */
data class ToolConfirmation(val toolName: String, val description: String)

/** Rounds of tool calls (run them, then ask the model again) allowed per user message. */
const val MAX_TOOL_ROUNDS = 5

/**
 * The open conversation, its use of [engine] and its persistence in [store]. Plain Kotlin so it is
 * unit-tested on the JVM; [ChatViewModel] runs it in its scope.
 *
 * When the request offers tools ([GenerationParams.tools]) and the reply calls some, each call is
 * run from [tools] (a write only once the user approves it), its result added as a tool turn, and
 * the model asked again, up to [MAX_TOOL_ROUNDS] times per message.
 *
 * [work] runs model loading, the token stream and tools off the caller's (main) thread. Each
 * operation that touches [store] waits for the previous one, so writes land in the order they were made.
 */
class ChatController(
    private val engine: Engine,
    private val store: ChatStore,
    private val scope: CoroutineScope,
    private val work: CoroutineDispatcher,
    private val tools: List<Tool> = emptyList(),
) {
    private val _state = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = _state.asStateFlow()

    private val loadMutex = Mutex()

    /** The newest operation; the next one joins it first. */
    private var job: Job? = null

    /** Set by [stop] so a reply whose operation has not reached the engine yet is not started. */
    private var stopRequested = false

    /** Completed with the user's answer to [ChatState.confirmation]. */
    private var approval: CompletableDeferred<Boolean>? = null

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

    /** Appends a user turn and streams the reply. Ignored while loading or generating. */
    fun send(text: String, params: GenerationParams) {
        val prompt = text.trim()
        val now = _state.value
        if (prompt.isEmpty() || busy(now)) return
        reply(keep = now.turns.size, newUser = Turn(Role.USER, prompt), params = params)
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

    /** Ends the reply early; the text so far is kept as the assistant turn. A tool call awaiting approval is declined. */
    fun stop() {
        if (_state.value.generating) requestStop()
    }

    /** Runs the tool call waiting in [ChatState.confirmation]. */
    fun approveTool() {
        approval?.complete(true)
    }

    /** Declines the tool call waiting in [ChatState.confirmation]; the model is told so. */
    fun declineTool() {
        approval?.complete(false)
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

    private fun requestStop() {
        stopRequested = true
        engine.stop()
        approval?.complete(false)
    }

    /**
     * Keeps the first [keep] turns, appends [newUser] if given, and streams the assistant's reply to
     * the result, running the tools it calls. The store is brought to the same turns before the engine starts.
     */
    private fun reply(keep: Int, newUser: Turn?, params: GenerationParams) {
        val before = _state.value
        val kept = before.turns.take(keep)
        val turns = if (newUser == null) kept else kept + newUser
        val dropped = before.turns.size > keep
        stopRequested = false
        _state.update { it.copy(turns = turns, streaming = "", error = null) }
        after { previous ->
            previous?.join()
            var conversationId = before.conversationId
            try {
                persist {
                    val id = conversationId
                        ?: store.createConversation(ConversationTitle.from(turns.first { it.role == Role.USER }.text))
                    conversationId = id
                    if (dropped) store.truncate(id, keep)
                    if (newUser != null) store.appendTurn(id, newUser)
                }
                _state.update { it.copy(conversationId = conversationId) }
                if (stopRequested) return@after
                val facts = persist { store.factTexts() }.orEmpty()
                val system = ChatMessage(Role.SYSTEM, SystemPrompt.build(facts, tools = params.tools.isNotEmpty()))
                var rounds = 0
                while (true) {
                    val calls = streamReply(system, params, conversationId, mayCallTools = rounds < MAX_TOOL_ROUNDS)
                    if (calls.isEmpty()) break
                    rounds++
                    runTools(calls, conversationId)
                    if (stopRequested) break
                }
            } finally {
                _state.update { it.copy(streaming = null, confirmation = null) }
            }
        }
    }

    /**
     * Streams one reply to [system] and the turns so far, and commits it. Returns the tool calls in it
     * that are to run now (they are saved on the reply); none when it failed or was stopped.
     */
    private suspend fun streamReply(
        system: ChatMessage,
        params: GenerationParams,
        conversationId: Long?,
        mayCallTools: Boolean,
    ): List<ToolCall> {
        val statsBefore = engine.lastStats.value
        val calls = mutableListOf<ToolCall>()
        var failure: String? = null
        var toRun = emptyList<ToolCall>()
        try {
            val history = listOf(system) + _state.value.turns.map { it.toMessage() }
            engine.chat(history, params)
                // Caught before flowOn so text already buffered still arrives after a failure.
                .catch { e ->
                    if (e is CancellationException) throw e
                    failure = "Generation failed: ${e.message ?: e.javaClass.simpleName}"
                }
                .flowOn(work)
                .collect { chunk ->
                    // Audio is for a later milestone.
                    if (chunk is ReplyChunk.Text) {
                        _state.update { it.copy(streaming = (it.streaming ?: "") + chunk.text) }
                    } else if (chunk is ReplyChunk.ToolCall) {
                        calls += chunk.call
                    }
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure = "Generation failed: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            // Calls are run only when tools were offered, and not after a failure or a stop.
            val called = if (failure == null && !stopRequested && params.tools.isNotEmpty()) calls.toList() else emptyList()
            if (called.isNotEmpty() && !mayCallTools) failure = "Stopped after $MAX_TOOL_ROUNDS rounds of tool use."
            toRun = if (mayCallTools) called else emptyList()
            // Stats belong to this reply only if the engine published new ones for it.
            val stats = engine.lastStats.value?.takeIf { failure == null && it !== statsBefore }
            val text = _state.value.streaming.orEmpty()
            val replyTurn = if (text.isEmpty() && toRun.isEmpty()) null else Turn(Role.ASSISTANT, text, stats, toolCalls = toRun)
            _state.update {
                it.copy(
                    turns = if (replyTurn == null) it.turns else it.turns + replyTurn,
                    // Still generating while its tool calls run.
                    streaming = if (toRun.isEmpty()) null else "",
                    error = failure ?: it.error,
                )
            }
            if (replyTurn != null) save(conversationId, replyTurn)
        }
        return toRun
    }

    /**
     * Runs [calls] in order and commits a tool turn with each result. Images a tool returns follow
     * the tool turns on a user turn of their own, as the engine takes images only on user turns.
     */
    private suspend fun runTools(calls: List<ToolCall>, conversationId: Long?) {
        val shown = mutableListOf<Turn>()
        for (call in calls) {
            val result = if (stopRequested) ToolResult(ToolResults.CANCELLED) else runTool(call)
            commit(conversationId, Turn(Role.TOOL, result.text, toolName = call.name))
            if (result.images.isNotEmpty()) {
                shown += Turn(Role.USER, "Photos from ${call.name}:", images = result.images, toolName = call.name)
            }
        }
        shown.forEach { commit(conversationId, it) }
    }

    private suspend fun runTool(call: ToolCall): ToolResult {
        val tool = tools.firstOrNull { it.spec.name == call.name }
            ?: return ToolResult(ToolResults.error("There is no tool named ${call.name}."))
        return try {
            val problem = tool.check(call.argumentsJson)
            when {
                problem != null -> ToolResult(ToolResults.error(problem))
                tool.needsConfirmation && !confirm(ToolConfirmation(call.name, tool.describe(call.argumentsJson))) ->
                    ToolResult(ToolResults.DECLINED)
                else -> withContext(work) { tool.run(call.argumentsJson) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult(ToolResults.error("${call.name} failed: ${e.message ?: e.javaClass.simpleName}"))
        }
    }

    /** Shows [confirmation] and waits for the user's answer; a stop counts as declining. */
    private suspend fun confirm(confirmation: ToolConfirmation): Boolean {
        if (stopRequested) return false
        val answer = CompletableDeferred<Boolean>()
        approval = answer
        _state.update { it.copy(confirmation = confirmation) }
        try {
            return answer.await()
        } finally {
            approval = null
            _state.update { it.copy(confirmation = null) }
        }
    }

    /** Adds [turn] to the conversation and saves it. */
    private suspend fun commit(conversationId: Long?, turn: Turn) {
        _state.update { it.copy(turns = it.turns + turn) }
        save(conversationId, turn)
    }

    private suspend fun save(conversationId: Long?, turn: Turn) {
        if (conversationId != null) withContext(NonCancellable) { persist { store.appendTurn(conversationId, turn) } }
    }

    /** Launches [block] as the newest operation; it gets the previous one to wait for or stop. */
    private fun after(block: suspend (previous: Job?) -> Unit) {
        val previous = job
        job = scope.launch { block(previous) }
    }

    private suspend fun stopAndWait(previous: Job?) {
        if (previous == null) return
        if (previous.isActive) requestStop()
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

    // Attachments are not sent yet: the engine takes decoded pixels and samples, a later milestone.
    // Tool photos (on a turn from a tool) are kept as pixels in memory and sent.
    private fun Turn.toMessage() =
        ChatMessage(role, text, images = images, toolCalls = toolCalls, toolName = toolName.takeIf { role == Role.TOOL })
}
