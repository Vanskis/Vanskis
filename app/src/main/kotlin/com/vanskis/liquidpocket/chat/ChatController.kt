package com.vanskis.liquidpocket.chat

import com.vanskis.liquidpocket.core.ContextSize
import com.vanskis.liquidpocket.docs.DocumentContext
import com.vanskis.liquidpocket.engine.Accelerator
import com.vanskis.liquidpocket.engine.AudioInput
import com.vanskis.liquidpocket.engine.Capabilities
import com.vanskis.liquidpocket.engine.ChatMessage
import com.vanskis.liquidpocket.engine.Engine
import com.vanskis.liquidpocket.engine.EngineConfig
import com.vanskis.liquidpocket.engine.EngineState
import com.vanskis.liquidpocket.engine.GenerationParams
import com.vanskis.liquidpocket.engine.GenerationStats
import com.vanskis.liquidpocket.engine.ImageInput
import com.vanskis.liquidpocket.engine.ReplyChunk
import com.vanskis.liquidpocket.engine.Role
import com.vanskis.liquidpocket.engine.ToolCall
import com.vanskis.liquidpocket.tools.Choices
import com.vanskis.liquidpocket.tools.Tool
import com.vanskis.liquidpocket.tools.ToolResult
import com.vanskis.liquidpocket.tools.ToolResults
import com.vanskis.liquidpocket.tools.WebTools
import com.vanskis.liquidpocket.ui.components.contextTokensUsed
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
    /** The streamed reply's reasoning so far, or null when it has none (yet). */
    val streamingThinking: String? = null,
    val loading: Boolean = false,
    val error: String? = null,
    /** A tool call waiting for the user's approval; the reply is paused (still [generating]) meanwhile. */
    val confirmation: ToolConfirmation? = null,
    /**
     * Tokens the conversation takes in the context, as the engine counts the history it is sent
     * (or estimated from the newest reply's stats when the engine cannot count). 0 for a new chat.
     */
    val contextUsed: Int = 0,
    /** Compact now is summarizing older turns. */
    val compacting: Boolean = false,
) {
    val generating: Boolean get() = streaming != null

    /** Loading, generating or compacting: nothing new can start. */
    val busy: Boolean get() = loading || generating || compacting

    /** Compact now can run: nothing is busy and there are more than [Compaction.KEEP_TURNS] turns since the newest summary. */
    val canCompact: Boolean get() = !busy && Compaction.canCompact(turns)

    /** Index of the newest turn the user wrote, -1 if there is none. Regenerate and edit act on it. */
    val lastUserIndex: Int get() = turns.indexOfLast { it.role == Role.USER && !it.fromTool }
}

/**
 * What a write tool will do, shown on the card the user approves or cancels. [id] is new for each
 * card, so an answer is only taken for the card it was given on.
 */
data class ToolConfirmation(val id: Long, val toolName: String, val description: String)

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
 * [media] decodes attachments for the engine and [documents] reads attached documents back, whose
 * relevant passages go in front of the user's text; [sink] gets the reply's speech and its end.
 *
 * Each reply is routed through [models] ([route]): the media model when the newest user turn has
 * photos or voice (documents are text and do not count), the text model otherwise, loaded in place of the current one if need be. Nothing
 * is swapped back after a reply; the next reply routes again. [clock] times the model's thinking.
 * [customInstructions] (the user's, from Settings) is read for each reply's system prompt.
 *
 * A reply to a new user turn first compacts the conversation ([Compaction]) when its prompt fills
 * [Compaction.THRESHOLD_PERCENT] of the context; [compact] does it on request. A summary that fails
 * leaves the conversation as it was. [ChatState.contextUsed] is counted after each reply and on [open].
 * [accelerator] (also from Settings) is read for each load; a change reloads the model on the next turn.
 *
 * A model that can see captions the photos of the user turn it answered ([Turn.caption]); a model
 * that cannot is sent the caption in their place. Photos a tool returns to a model that cannot see
 * are captioned by the media model, swapped in for that and back out, and handed over as text.
 */
class ChatController(
    private val engine: Engine,
    private val store: ChatStore,
    private val scope: CoroutineScope,
    private val work: CoroutineDispatcher,
    private val tools: List<Tool> = emptyList(),
    private val media: MediaLoader = MediaLoader.NONE,
    private val documents: DocumentLoader = DocumentLoader.NONE,
    private val sink: ReplySink = ReplySink.NONE,
    private val models: ModelTargets = ModelTargets.NONE,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val customInstructions: () -> String = { "" },
    private val accelerator: () -> Accelerator = { Accelerator.CPU },
) {
    private val _state = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = _state.asStateFlow()

    private val loadMutex = Mutex()

    /** The newest operation; the next one joins it first. */
    private var job: Job? = null

    /** Set by [stop] so a reply whose operation has not reached the engine yet is not started. */
    private var stopRequested = false

    /** A reply's photos are being captioned ([captionPhotos]) after it ended; [stop] still stops that. */
    private var captioning = false

    /** Completed with the user's answer to [ChatState.confirmation]. */
    private var approval: CompletableDeferred<Boolean>? = null

    /** The [ToolConfirmation.id] of the newest card. */
    private var lastConfirmationId = 0L

    /**
     * The request behind the engine's current model, as the engine may clamp the context size and
     * fall back from the NPU to the CPU.
     */
    private var loadedFor: Triple<String, Int, Accelerator>? = null

    /** Lets a model that thinks reason before answering ([GenerationParams.think]). */
    var think: Boolean = true

    /** The newest reply's params, which [open] and [compact] count the context with (tools take room in it). */
    private var lastParams = GenerationParams()

    /**
     * Loads [modelPath] (with its projector [mmprojPath]) and [contextSize] unless the engine already
     * has exactly that loaded, stopping a reply in progress first.
     */
    suspend fun ensureLoaded(modelPath: String, contextSize: Int, mmprojPath: String? = null) {
        val target = LoadTarget(modelPath, mmprojPath, contextSize)
        if (isLoaded(target)) return
        stopAndWait(job)
        load(target)
    }

    /**
     * Loads the model the open conversation's next reply routes to, unless it is loaded already.
     * Does nothing while a reply is in progress, as a reply loads the model it needs itself, or
     * when no model is installed.
     */
    suspend fun ensureLoaded() {
        if (_state.value.generating) return
        job?.join()
        // Routed after the wait: the operation waited for (an open, say) may have changed the turns.
        if (_state.value.generating) return
        val target = route(_state.value.turns, textOnly = false) ?: return
        if (isLoaded(target)) return
        load(target)
    }

    /** The model for a reply to [turns], see [ModelTargets.route]; [textOnly] routes as if nothing was attached. */
    private suspend fun route(turns: List<Turn>, textOnly: Boolean): LoadTarget? {
        val newestUser = turns.lastOrNull { it.role == Role.USER && !it.fromTool }
        val hasMedia = newestUser?.attachments.orEmpty().any { it.kind == AttachmentKind.IMAGE || it.kind == AttachmentKind.AUDIO }
        return models.route(hasMedia = !textOnly && hasMedia)
    }

    /** Loads [target] unless it is loaded already; false when loading failed (the error is shown). */
    private suspend fun load(target: LoadTarget): Boolean = loadMutex.withLock {
        if (isLoaded(target)) return@withLock true
        _state.update { it.copy(loading = true) }
        val accelerator = accelerator()
        try {
            withContext(work) { engine.load(target.path, EngineConfig(target.contextSize, target.mmprojPath, accelerator)) }
            loadedFor = Triple(target.path, target.contextSize, accelerator)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            loadedFor = null
            _state.update { it.copy(error = "Could not load the model: ${e.message ?: e.javaClass.simpleName}") }
            false
        } finally {
            _state.update { it.copy(loading = false) }
        }
    }

    private fun isLoaded(target: LoadTarget): Boolean {
        val request = Triple(target.path, target.contextSize, accelerator())
        return when (val current = engine.state.value) {
            is EngineState.Ready -> current.modelPath == target.path && (loadedFor == request ||
                current.contextSize == ContextSize.clamp(target.contextSize) && current.accelerator == request.third)
            EngineState.Generating -> loadedFor == request
            else -> false
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
        reply(keep = now.turns.size, newUser = Turn(Role.USER, prompt, attachments = attachments), params = withThink(params))
        return true
    }

    /** Replaces the reply to the newest user turn (or adds one, if it has none) with a new one. */
    fun regenerate(params: GenerationParams) {
        val now = _state.value
        val last = now.lastUserIndex
        if (last < 0 || busy(now)) return
        reply(keep = last + 1, newUser = null, params = withThink(params))
    }

    /** Replaces the newest user turn with [text], drops what followed it, and streams a new reply. */
    fun editAndResend(text: String, params: GenerationParams) {
        val prompt = text.trim()
        val now = _state.value
        val last = now.lastUserIndex
        if (prompt.isEmpty() || last < 0 || busy(now)) return
        val edited = now.turns[last].copy(text = prompt)
        reply(keep = last, newUser = edited, params = withThink(params))
    }

    /** Ends the reply early; the text so far is kept as the assistant turn. A tool call awaiting approval is declined. */
    fun stop() {
        if (_state.value.generating || _state.value.compacting || captioning) requestStop()
    }

    /**
     * Summarizes the turns before the last [Compaction.KEEP_TURNS] now, whatever the context use.
     * Ignored while busy or with nothing to summarize ([ChatState.canCompact]).
     */
    fun compact() {
        if (!_state.value.canCompact) return
        stopRequested = false
        _state.update { it.copy(compacting = true, error = null) }
        after { previous ->
            previous?.join()
            try {
                // The loaded model summarizes; the text model is loaded only when none is.
                if (engine.state.value !is EngineState.Ready) {
                    val target = route(_state.value.turns, textOnly = true)
                    if (target != null && !load(target)) return@after
                }
                if (stopRequested) return@after
                compactTurns(_state.value.conversationId)
                refreshContextUsed(systemMessage(lastParams), lastParams)
            } finally {
                _state.update { it.copy(compacting = false) }
            }
        }
    }

    /** Runs the tool call waiting in [ChatState.confirmation], if [confirmationId] is that card's. */
    fun approveTool(confirmationId: Long) = answer(confirmationId, true)

    /** Declines the tool call waiting in [ChatState.confirmation], if [confirmationId] is that card's; the model is told so. */
    fun declineTool(confirmationId: Long) = answer(confirmationId, false)

    private fun answer(confirmationId: Long, approved: Boolean) {
        if (_state.value.confirmation?.id == confirmationId) approval?.complete(approved)
    }

    /** Starts a new, unsaved conversation. */
    fun newChat() = after { previous ->
        stopAndWait(previous)
        _state.update { it.copy(conversationId = null, turns = emptyList(), error = null, contextUsed = 0) }
    }

    /** Opens a saved conversation with all its turns, and counts the context they take. */
    fun open(conversationId: Long) = after { previous ->
        stopAndWait(previous)
        val turns = persist { store.turns(conversationId) } ?: return@after
        _state.update { it.copy(conversationId = conversationId, turns = turns, error = null, contextUsed = 0) }
        refreshContextUsed(systemMessage(lastParams), lastParams)
    }

    /** Deletes a saved conversation; when it is the open one, a new chat takes its place. */
    fun deleteConversation(conversationId: Long) = after { previous ->
        if (_state.value.conversationId == conversationId) {
            stopAndWait(previous)
            _state.update { it.copy(conversationId = null, turns = emptyList(), error = null, contextUsed = 0) }
        } else {
            previous?.join()
        }
        persist { store.deleteConversation(conversationId) }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    /** Reports an error that happened outside the controller (e.g. the model file is missing). */
    fun showError(message: String) = _state.update { it.copy(error = message) }

    private fun busy(state: ChatState) = state.busy

    private fun withThink(params: GenerationParams) = params.copy(think = think)

    private fun requestStop() {
        stopRequested = true
        engine.stop()
        approval?.complete(false)
    }

    /**
     * Keeps the first [keep] turns, appends [newUser] if given, and streams the assistant's reply to
     * the result, running the tools it calls. The store is brought to the same turns before the engine starts.
     *
     * The model the turns route to ([route]) is loaded
     * first if it is not already; when none is installed the loaded model answers. With [newUser],
     * the conversation is compacted first if it fills the context ([compactIfFull]).
     */
    private fun reply(keep: Int, newUser: Turn?, params: GenerationParams) {
        val before = _state.value
        val kept = before.turns.take(keep)
        val turns = if (newUser == null) kept else kept + newUser
        val dropped = before.turns.size > keep
        stopRequested = false
        lastParams = params
        val id = ++newestReply
        _state.update { it.copy(turns = turns, streaming = "", streamingThinking = null, error = null) }
        after { previous ->
            previous?.join()
            var conversationId = before.conversationId
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
                val system = systemMessage(params)
                val target = route(turns, textOnly = false)
                if (target != null && (!load(target) || stopRequested)) return@after
                if (newUser != null) {
                    compactIfFull(system, params, conversationId)
                    if (stopRequested) return@after
                }
                var rounds = 0
                while (true) {
                    val calls = streamReply(system, params, conversationId, mayCallTools = rounds < MAX_TOOL_ROUNDS)
                    if (calls.isEmpty()) break
                    rounds++
                    runTools(calls, conversationId)
                    // Offered choices end the turn: the user's pick is the next message.
                    if (stopRequested || Choices.offerIn(calls) != null) break
                }
                if (!stopRequested) captionPhotos(conversationId)
                refreshContextUsed(system, params)
            } finally {
                // A reply sent while this one captioned its photos owns the streaming state by now.
                if (newestReply == id) _state.update { it.copy(streaming = null, streamingThinking = null, confirmation = null) }
            }
        }
    }

    /** Counts [reply] calls; each operation clears the streaming state only while its reply is the newest. */
    private var newestReply = 0L

    /** The system prompt for a reply with [params]: the remembered facts, tool guidance and custom instructions. */
    private suspend fun systemMessage(params: GenerationParams): ChatMessage {
        val facts = persist { store.factTexts() }.orEmpty()
        val prompt = SystemPrompt.build(
            facts,
            tools = params.tools.isNotEmpty(),
            custom = customInstructions(),
            webSearch = params.tools.any { it.name == WebTools.SEARCH },
        )
        return ChatMessage(Role.SYSTEM, prompt)
    }

    /** What the engine is sent for the turns so far: [system], then the newest summary and the turns after it. */
    private suspend fun history(system: ChatMessage): List<ChatMessage> {
        val caps = engine.capabilities.value
        val turns = _state.value.turns
        // Documents give the passages for the newest question, whichever turn attached them.
        val question = turns.lastOrNull { it.role == Role.USER && !it.fromTool }?.text.orEmpty()
        return withContext(work) {
            // Media is decoded once per turn, not again for the count after the reply; documents are
            // read again when the question changes.
            val previous = converted.takeIf { caps == convertedFor } ?: java.util.IdentityHashMap()
            val sameQuestion = question == convertedQuestion
            val next = java.util.IdentityHashMap<Turn, ChatMessage>()
            val history = Compaction.history(system, turns) { turn ->
                val reused = previous[turn]?.takeIf { sameQuestion || turn.attachments.none { it.kind == AttachmentKind.DOCUMENT } }
                (reused ?: turn.toMessage(caps, question)).also { next[turn] = it }
            }
            converted = next
            convertedFor = caps
            convertedQuestion = question
            history
        }
    }

    /** The messages [history] last made of each turn, with the capabilities and the question they were made for. */
    private var converted = java.util.IdentityHashMap<Turn, ChatMessage>()
    private var convertedFor: Capabilities? = null
    private var convertedQuestion: String? = null

    /** Tokens the history takes as the engine counts it; the estimate from the newest reply's stats if it cannot. */
    private suspend fun contextUsed(system: ChatMessage, params: GenerationParams): Int =
        try {
            val history = history(system)
            withContext(work) { engine.countTokens(history, params.copy(speak = false)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            contextTokensUsed(_state.value.turns)
        }

    private suspend fun refreshContextUsed(system: ChatMessage, params: GenerationParams) {
        val used = contextUsed(system, params)
        _state.update { it.copy(contextUsed = used) }
    }

    /** Compacts the conversation when the prompt for the next reply fills [Compaction.THRESHOLD_PERCENT] of the context. */
    private suspend fun compactIfFull(system: ChatMessage, params: GenerationParams, conversationId: Long?) {
        val total = (engine.state.value as? EngineState.Ready)?.contextSize ?: return
        if (!Compaction.canCompact(_state.value.turns)) return
        if (Compaction.shouldCompact(contextUsed(system, params), total)) compactTurns(conversationId)
    }

    /**
     * Has the model summarize the turns [Compaction.plan] picks, then puts the summary in front of
     * the turns kept and saves that. A summary that fails, comes back empty or is stopped changes nothing.
     */
    private suspend fun compactTurns(conversationId: Long?) {
        val turns = _state.value.turns
        val plan = Compaction.plan(turns) ?: return
        val summary = summarize(plan) ?: return
        if (stopRequested) return
        val compacted = Compaction.compacted(turns, plan, summary)
        _state.update { it.copy(turns = compacted) }
        if (conversationId != null) {
            withContext(NonCancellable) { persist { store.replaceFrom(conversationId, plan.cut, compacted.drop(plan.cut)) } }
        }
    }

    /** The model's summary for [plan], or null when it failed or came back empty. Nothing of it is streamed. */
    private suspend fun summarize(plan: Compaction.Plan): String? = quietReply(Compaction.request(plan), Compaction.PARAMS)

    /**
     * The model's caption of the newest user turn's photos, if it can see them and the turn has none
     * yet, saved on the turn ([Turn.caption]). A caption that fails, comes back empty or is stopped is left out.
     */
    private suspend fun captionPhotos(conversationId: Long?) {
        if (!engine.capabilities.value.vision) return
        val index = _state.value.lastUserIndex
        val turn = _state.value.turns.getOrNull(index)?.takeIf { it.caption == null } ?: return
        val photos = turn.attachments.filter { it.kind == AttachmentKind.IMAGE }
        val images = withContext(work) { photos.mapNotNull { photo -> loadOrNull { media.image(photo.path) } } }
        if (images.isEmpty()) return
        captioning = true
        val caption = try {
            quietReply(PhotoCaptions.request(images), PhotoCaptions.PARAMS)
        } finally {
            captioning = false
        }
        if (caption == null || stopRequested) return
        val captioned = turn.copy(caption = caption)
        // A turn sent meanwhile has joined the turns already; this one is replaced where it still is.
        _state.update { s ->
            if (s.turns.getOrNull(index) !== turn) s else s.copy(turns = s.turns.toMutableList().also { it[index] = captioned })
        }
        if (conversationId != null) withContext(NonCancellable) { persist { store.replaceTurn(conversationId, index, captioned) } }
    }

    /** The model's text answer to [messages], or null when it failed or came back empty. Nothing of it is streamed or spoken. */
    private suspend fun quietReply(messages: List<ChatMessage>, params: GenerationParams): String? =
        try {
            val text = StringBuilder()
            engine.chat(messages, params)
                .flowOn(work)
                .collect { if (it is ReplyChunk.Text) text.append(it.text) }
            text.toString().trim().ifEmpty { null }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    /**
     * Streams one reply to [system] and the turns so far, and commits it with its thinking. Returns
     * the tool calls in it that are to run now (they are saved on the reply); none when it failed or was stopped.
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
        var spoke = false
        // When the model started and stopped thinking; it stops at the first chunk that is not thinking.
        var thinkingFrom: Long? = null
        var thinkingUntil: Long? = null
        try {
            val caps = engine.capabilities.value
            val history = history(system)
            engine.chat(history, params.copy(speak = params.speak && caps.audioOut))
                // Caught before flowOn so text already buffered still arrives after a failure.
                .catch { e ->
                    if (e is CancellationException) throw e
                    failure = "Generation failed: ${e.message ?: e.javaClass.simpleName}"
                }
                .flowOn(work)
                .collect { chunk ->
                    if (chunk is ReplyChunk.Thinking) {
                        if (thinkingFrom == null) thinkingFrom = clock()
                    } else if (thinkingFrom != null && thinkingUntil == null) {
                        thinkingUntil = clock()
                    }
                    when (chunk) {
                        is ReplyChunk.Text -> _state.update { it.copy(streaming = (it.streaming ?: "") + chunk.text) }
                        is ReplyChunk.Thinking -> _state.update { it.copy(streamingThinking = (it.streamingThinking ?: "") + chunk.text) }
                        is ReplyChunk.Audio -> {
                            spoke = true
                            sink.audio(chunk.samples)
                        }
                        is ReplyChunk.ToolCall -> calls += chunk.call
                    }
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure = "Generation failed: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            // Calls are run only when tools were offered, and not after a failure or a stop.
            val called = if (failure == null && !stopRequested && params.tools.isNotEmpty()) calls.toList() else emptyList()
            val thinkingMillis = thinkingFrom?.let { (thinkingUntil ?: clock()) - it }
            toRun = commitReply(called, failure, mayCallTools, statsBefore, conversationId, thinkingMillis, spoke)
        }
        return toRun
    }

    /**
     * Commits the streamed reply, with its thinking (which took [thinkingMillis]) and the [called]
     * tools it may run; returns those to run now. [spoke] tells the sink the model spoke it.
     */
    private suspend fun commitReply(
        called: List<ToolCall>,
        streamFailure: String?,
        mayCallTools: Boolean,
        statsBefore: GenerationStats?,
        conversationId: Long?,
        thinkingMillis: Long?,
        spoke: Boolean,
    ): List<ToolCall> {
        var failure = streamFailure
        if (called.isNotEmpty() && !mayCallTools) failure = "Stopped after $MAX_TOOL_ROUNDS rounds of tool use."
        val toRun = if (mayCallTools) called else emptyList()
        // Stats belong to this reply only if the engine published new ones for it.
        val stats = engine.lastStats.value?.takeIf { failure == null && it !== statsBefore }
        val text = _state.value.streaming.orEmpty()
        val thinking = _state.value.streamingThinking?.takeIf { it.isNotBlank() }
        val replyTurn = if (text.isEmpty() && toRun.isEmpty()) {
            null
        } else {
            Turn(
                Role.ASSISTANT, text, stats, toolCalls = toRun,
                thinking = thinking, thinkingMillis = thinkingMillis.takeIf { thinking != null },
            )
        }
        _state.update {
            it.copy(
                turns = if (replyTurn == null) it.turns else it.turns + replyTurn,
                // Still generating while its tool calls run.
                streaming = if (toRun.isEmpty()) null else "",
                streamingThinking = null,
                error = failure ?: it.error,
            )
        }
        // The reply has ended once no tools are left to run; a tool round's text is not its end.
        // Offered choices end it too; a voice reply reads them out.
        val offer = Choices.offerIn(toRun)
        val ended = if (offer != null) offer.spoken(text) else text.takeIf { toRun.isEmpty() }
        if (!ended.isNullOrEmpty()) sink.finished(ended, completed = failure == null && !stopRequested, spoke = spoke)
        if (replyTurn != null) save(conversationId, replyTurn)
        return toRun
    }

    /**
     * Runs [calls] in order and commits a tool turn with each result. Images a tool returns follow
     * the tool turns on a user turn of their own, as the engine takes images only on user turns, or
     * as their captions when the loaded model cannot see them ([captionToolPhotos]).
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
        captionToolPhotos(shown).forEach { commit(conversationId, it) }
    }

    /**
     * [shown] for a loaded model that cannot see: the media model is loaded in its place to caption
     * each photo, then the text model is loaded back. A turn whose photos all got a caption has them
     * in its text instead. Without the media model, or once stopped, [shown] stays as it is.
     */
    private suspend fun captionToolPhotos(shown: List<Turn>): List<Turn> {
        if (shown.isEmpty() || engine.capabilities.value.vision || stopRequested) return shown
        val mediaTarget = models.media() ?: return shown
        val textTarget = models.text() ?: return shown
        val captions = if (load(mediaTarget) && engine.capabilities.value.vision) {
            shown.map { turn -> turn.images.map { if (stopRequested) null else quietReply(PhotoCaptions.request(listOf(it)), PhotoCaptions.PARAMS) } }
        } else {
            null
        }
        // Once stopped, the next reply loads the model it needs.
        if (stopRequested) return shown
        load(textTarget)
        if (captions == null) return shown
        return shown.zip(captions) { turn, texts ->
            if (texts.any { it == null }) {
                turn
            } else {
                turn.copy(text = (listOf(turn.text) + texts.map { PhotoCaptions.note(it!!) }).joinToString("\n"), images = emptyList())
            }
        }
    }

    private suspend fun runTool(call: ToolCall): ToolResult {
        val tool = tools.firstOrNull { it.spec.name == call.name }
            ?: return ToolResult(ToolResults.error("There is no tool named ${call.name}."))
        return try {
            val problem = tool.check(call.argumentsJson)
            when {
                problem != null -> ToolResult(ToolResults.error(problem))
                tool.needsConfirmation && !confirm(ToolConfirmation(++lastConfirmationId, call.name, tool.describe(call.argumentsJson))) ->
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

    /** Names a conversation that starts with only a photo, a voice message or a document. */
    private fun Turn.titleText(): String = text.ifBlank {
        when (attachments.firstOrNull()?.kind) {
            AttachmentKind.IMAGE -> "Photo"
            AttachmentKind.AUDIO -> "Voice message"
            AttachmentKind.DOCUMENT -> attachments.first().name ?: "Document"
            null -> text
        }
    }

    /**
     * A user turn's attachments become decoded images and audio the model takes; media it cannot
     * take, or whose file is gone, becomes a note in the text so the engine is never given it. A
     * document becomes the passages relevant to the turn's text and the newest [question]
     * ([DocumentContext]), in front of the turn's text.
     */
    private fun Turn.toMessage(caps: Capabilities, question: String): ChatMessage {
        // Tool photos (on a user turn from a tool) are kept as pixels in memory and sent as they are,
        // or as a note to a model that cannot see them (the text model). Thinking is never sent back.
        if (role != Role.USER || attachments.isEmpty()) {
            val unseen = images.isNotEmpty() && !caps.vision
            return ChatMessage(
                role,
                if (unseen) listOf(text, MediaNotes.IMAGE_UNSUPPORTED).filter { it.isNotBlank() }.joinToString("\n\n") else text,
                images = if (unseen) emptyList() else images,
                toolCalls = toolCalls,
                toolName = toolName.takeIf { role == Role.TOOL },
            )
        }
        val images = mutableListOf<ImageInput>()
        val audio = mutableListOf<AudioInput>()
        val notes = mutableListOf<String>()
        val docs = mutableListOf<String>()
        // A follow-up's words find its passages; this turn's own words keep what it asked about.
        val query = if (question.isBlank() || question == text) text else "$text\n$question"
        for (attachment in attachments) {
            when (attachment.kind) {
                AttachmentKind.IMAGE -> when {
                    !caps.vision -> notes += caption?.let { PhotoCaptions.note(it) } ?: MediaNotes.IMAGE_UNSUPPORTED
                    else -> loadOrNull { media.image(attachment.path) }?.let { images += it } ?: run { notes += MediaNotes.IMAGE_MISSING }
                }
                AttachmentKind.AUDIO -> when {
                    !caps.audioIn -> notes += MediaNotes.AUDIO_UNSUPPORTED
                    else -> loadOrNull { media.audio(attachment.path, caps.audioInSampleRate) }?.let { audio += it }
                        ?: run { notes += MediaNotes.AUDIO_MISSING }
                }
                AttachmentKind.DOCUMENT -> loadOrNull { documents.document(attachment.path) }
                    ?.let { docs += DocumentContext.forQuestion(it, query) } ?: run { notes += MediaNotes.DOCUMENT_MISSING }
            }
        }
        val content = (docs + text + notes.distinct()).filter { it.isNotBlank() }.joinToString("\n\n")
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
