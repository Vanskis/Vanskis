// Adapted from llama.cpp examples/llama.android/lib/.../InferenceEngineImpl.kt
// (MIT, Copyright (c) 2023-2026 The ggml authors; Android example by Arm).
package com.vanskis.liquidpocket.engine

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Entry point used by the app. */
object Engines {
    @Volatile
    private var llama: Engine? = null

    /** The process-wide llama.cpp engine. */
    fun llama(context: Context): Engine =
        llama ?: synchronized(this) {
            llama ?: context.applicationContext.let { app ->
                LlamaEngine(
                    app.applicationInfo.nativeLibraryDir,
                    NpuCrashGuard(File(app.noBackupFilesDir, NPU_CRASH_MARKER)),
                ).also { llama = it }
            }
        }

    /** In noBackupFilesDir: a crash on one phone says nothing about the NPU of the next. */
    private const val NPU_CRASH_MARKER = "npu-armed"
}

/**
 * [Engine] on llama.cpp. All native calls run on one dedicated thread (llama.cpp state is not
 * thread-safe, and OpenMP keeps one worker pool per calling thread); [mutex] keeps a reply from
 * interleaving with another reply or a load while its flow is suspended in `emit`.
 */
internal class LlamaEngine(
    private val nativeLibDir: String,
    /** Null in tests that never load a model. */
    private val crashGuard: NpuCrashGuard? = null,
) : Engine {
    private val dispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "lp-engine").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val mutex = Mutex()

    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
    override val state: StateFlow<EngineState> = _state.asStateFlow()
    private val _capabilities = MutableStateFlow(Capabilities.NONE)
    override val capabilities: StateFlow<Capabilities> = _capabilities.asStateFlow()
    private val _lastStats = MutableStateFlow<GenerationStats?>(null)
    override val lastStats: StateFlow<GenerationStats?> = _lastStats.asStateFlow()

    @Volatile
    private var stopRequested = false

    // Engine-thread state.
    private var nativeReady = false
    private var loaded: EngineState.Ready? = null
    private var loadedConfig: EngineConfig? = null
    // The NPU backend is started at most once per process; a failure is remembered, not retried.
    private var npuTried = false
    private var npuError: String? = null

    override val npuAvailable: Boolean
        get() = File(nativeLibDir, NPU_LIB).isFile

    override val npuCrashedLastRun: Boolean = crashGuard?.crashedLastRun == true

    /** Set from an NPU load until its first reply runs to the end; engine thread. */
    private var npuUnproven = false

    /** Starts the NPU backend (once); returns why it cannot be used, or null. Engine thread. */
    private fun initNpu(): String? {
        if (!npuTried) {
            npuError = LlamaNative.nativeInitNpu(nativeLibDir)
            npuTried = true
        }
        return npuError
    }

    /** The NPU is proven or no longer in use: a later death of the process is not its doing. Engine thread. */
    private fun disarmNpu() {
        npuUnproven = false
        crashGuard?.disarm()
    }

    override suspend fun load(modelPath: String, config: EngineConfig) {
        stop()
        mutex.withLock {
            withContext(dispatcher) {
                _state.value = EngineState.Loading
                try {
                    if (!nativeReady) {
                        System.loadLibrary("lpengine")
                        LlamaNative.nativeInit(nativeLibDir)
                        nativeReady = true
                    }
                    if (loaded != null) {
                        loaded = null
                        _capabilities.value = Capabilities.NONE
                        LlamaNative.nativeUnload()
                    }
                    requireReadable(modelPath, "Model")
                    config.mmprojPath?.let { requireReadable(it, "Projector") }
                    val threads = ThreadPolicy.threadsFor(Runtime.getRuntime().availableProcessors())
                    // Armed before the NPU is touched: starting it or loading on it can abort the process too.
                    val initNpuGuarded = { crashGuard?.arm(); initNpu() }
                    val outcome = AcceleratorFallback.load(
                        config.accelerator, npuAvailable, initNpuGuarded, npuCrashedLastRun,
                    ) { acc ->
                        LlamaNative.nativeLoad(
                            modelPath, config.mmprojPath, config.contextSize, threads, acc == Accelerator.NPU,
                        )
                    }
                    if (outcome.accelerator == Accelerator.NPU) npuUnproven = true else disarmNpu()
                    outcome.note?.let { Log.w(TAG, it) }
                    val caps = NativeCapabilities.of(LlamaNative.nativeCapabilities())
                    val ready = EngineState.Ready(modelPath, outcome.value, outcome.accelerator, outcome.note)
                    loaded = ready
                    loadedConfig = config
                    _capabilities.value = caps
                    _state.value = ready
                } catch (e: CancellationException) {
                    disarmNpu()
                    throw e
                } catch (e: Throwable) {
                    // The process survived, and no model is left on the NPU.
                    disarmNpu()
                    // Throwable: System.loadLibrary fails with UnsatisfiedLinkError.
                    throw fail("Could not load the model", e)
                }
            }
        }
    }

    override suspend fun unload() {
        stop()
        mutex.withLock {
            withContext(dispatcher) {
                if (loaded != null) {
                    loaded = null
                    _capabilities.value = Capabilities.NONE
                    LlamaNative.nativeUnload()
                }
                disarmNpu()
                _state.value = EngineState.Idle
            }
        }
    }

    override suspend fun benchmark(chargeMicroAh: () -> Long?): BenchResult {
        val (start, config) = current() ?: throw EngineException("No model loaded")
        val statsBefore = _lastStats.value
        var traced = false
        try {
            val cpu = benchOn(Accelerator.CPU, start.modelPath, config, chargeMicroAh = chargeMicroAh)
            if (!npuAvailable) return BenchResult(cpu.decode ?: 0.0, null, NO_NPU_BUILD, cpuPrefillTokPerSec = cpu.prefill, cpuMahPerReply = cpu.mah)
            traced = true
            val npu = benchOn(Accelerator.NPU, start.modelPath, config, trace = true, chargeMicroAh = chargeMicroAh)
            return BenchResult(
                cpu.decode ?: 0.0, npu.decode, npu.note, npuReport = report,
                cpuPrefillTokPerSec = cpu.prefill, npuPrefillTokPerSec = npu.prefill,
                cpuMahPerReply = cpu.mah, npuMahPerReply = npu.mah,
            )
        } finally {
            withContext(NonCancellable) {
                // Leave the model loaded the way the user asked for, untraced, and their last reply's stats.
                if (traced || current()?.second != config) load(start.modelPath, config)
                _lastStats.value = statsBefore
            }
        }
    }

    /** One bench reply's speeds and battery use; [note] says why the model could not run there. */
    private class BenchRun(val decode: Double?, val prefill: Double?, val mah: Double?, val note: String? = null)

    /**
     * Speeds and battery use of the bench reply on [acc], or why the model could not run there.
     * With [trace], the model is reloaded with op tracing on and [report] holds what the trace found.
     */
    private suspend fun benchOn(
        acc: Accelerator,
        modelPath: String,
        config: EngineConfig,
        trace: Boolean = false,
        chargeMicroAh: () -> Long? = { null },
    ): BenchRun {
        report = null
        if (trace) {
            withContext(dispatcher) { LlamaNative.nativeSetOpTrace(true) }
            try {
                load(modelPath, config.copy(accelerator = acc))
            } finally {
                withContext(NonCancellable + dispatcher) { LlamaNative.nativeSetOpTrace(false) }
            }
        } else if (current()?.first?.accelerator != acc) {
            load(modelPath, config.copy(accelerator = acc))
        }
        val ready = current()?.first ?: throw EngineException("No model loaded")
        if (ready.accelerator != acc) return BenchRun(null, null, null, ready.acceleratorNote)
        val before = chargeMicroAh()
        chat(BENCH_PROMPT, BENCH_PARAMS).collect {}
        val after = chargeMicroAh()
        if (trace) report = mutex.withLock { withContext(dispatcher) { LlamaNative.nativeAcceleratorReport() } }
        val stats = _lastStats.value
        return BenchRun(stats?.decodeTokensPerSecond, stats?.prefillTokensPerSecond, BenchResult.drainMah(before, after))
    }

    /** What the last traced benchmark run found; see [LlamaNative.nativeAcceleratorReport]. */
    private var report: String? = null

    /** The loaded model and the config it was loaded with, null when idle. */
    private suspend fun current(): Pair<EngineState.Ready, EngineConfig>? =
        mutex.withLock {
            withContext(dispatcher) {
                val ready = loaded
                val config = loadedConfig
                if (ready != null && config != null) ready to config else null
            }
        }

    override suspend fun countTokens(messages: List<ChatMessage>, params: GenerationParams): Int =
        mutex.withLock {
            withContext(dispatcher) {
                if (loaded == null) throw EngineException("No model loaded")
                val p = NativePrompt.of(messages, params.tools, placeholders = true)
                try {
                    LlamaNative.nativeCountPromptTokens(
                        p.roles, p.contents, p.toolNames, p.toolCalls,
                        p.mediaMsg, p.mediaKind, p.mediaDims, p.mediaData, p.tools, params.think,
                    )
                } catch (e: IllegalStateException) {
                    // A template or media error: the model itself is fine, so the state stays as it is.
                    throw EngineException("Could not count tokens: ${e.message}", e)
                }
            }
        }

    override fun chat(messages: List<ChatMessage>, params: GenerationParams): Flow<ReplyChunk> = flow {
        val startNanos = System.nanoTime()
        mutex.withLock {
            val ready = loaded ?: throw EngineException("No model loaded")
            val last = messages.lastOrNull()?.role
            if (last != Role.USER && last != Role.TOOL) {
                throw EngineException("The conversation must end with a user message or a tool result")
            }
            NativePrompt.checkMedia(messages, _capabilities.value)
            stopRequested = false
            _state.value = EngineState.Generating
            var promptTokens = 0
            var prefillTokens = 0
            var prefillNanos = 0L
            var prefillDone = false
            var generated = 0
            var thinkingTokens = 0
            var cutOff = false
            var firstTokenMs: Long? = null
            var decodeNanos = 0L
            var failure: EngineException? = null
            try {
                val budget = ContextBudget.of(ready.contextSize, minOf(params.maxNewTokens, GenerationParams.REPLY_ROOM))
                val trimmed = ConversationTrimmer.trim(messages, budget) { msgs ->
                    val p = NativePrompt.of(msgs, params.tools, placeholders = true)
                    native {
                        LlamaNative.nativeCountPromptTokens(
                            p.roles, p.contents, p.toolNames, p.toolCalls,
                            p.mediaMsg, p.mediaKind, p.mediaDims, p.mediaData, p.tools, params.think,
                        )
                    }
                }.messages
                val p = NativePrompt.of(trimmed, params.tools)
                val markers = native {
                    LlamaNative.nativeReplyMarkers(p.roles, p.contents, p.toolNames, p.toolCalls, p.tools, params.think)
                }
                val filter = ReplyFilter(
                    thinkStart = markers[0].firstOrNull()?.let(::utf8),
                    thinkEnds = markers[1].map(::utf8),
                    // A template whose triggers are patterns reports no words: fall back to the LFM markers.
                    toolStarts = markers[2].map(::utf8).ifEmpty {
                        if (params.tools.isEmpty()) emptyList() else LFM_TOOL_STARTS
                    },
                    startInThink = markers.getOrNull(3)?.isNotEmpty() == true,
                )
                val replyParser = ReplyParser(filter)
                // Without speech output the reply is text only, as GenerationParams.speak allows.
                val speaking = params.speak && _capabilities.value.audioOut
                val counts = native {
                    LlamaNative.nativeBeginReply(
                        p.roles, p.contents, p.toolNames, p.toolCalls,
                        p.mediaMsg, p.mediaKind, p.mediaDims, p.mediaData, p.tools,
                        params.temperature, speaking, params.think,
                        params.sampling.let { floatArrayOf(it.topP, it.minP, it.repeatPenalty) },
                        params.sampling.let { intArrayOf(it.topK, it.seed) },
                    )
                }
                promptTokens = counts[0]
                prefillTokens = counts[1]

                var remaining = prefillTokens
                while (remaining > 0) {
                    if (stopRequested) return@withLock
                    currentCoroutineContext().ensureActive()
                    val t0 = System.nanoTime()
                    remaining = native { LlamaNative.nativePrefillStep() }
                    prefillNanos += System.nanoTime() - t0
                }
                prefillDone = true

                val utf8 = Utf8Accumulator()
                // Audio frames come about two per text token; the cap ends a reply whose speech never stops.
                var audioSteps = 0
                val maxAudioSteps = (params.maxNewTokens.toLong() * 4).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                // Past its budget the think block is closed for the model, once, so the answer gets the rest.
                val thinkEnd = markers[1].firstOrNull()?.let { String(it, Charsets.UTF_8) }
                var thinkClosed = false
                var ended = false
                while (generated < params.maxNewTokens && audioSteps <= maxAudioSteps && !stopRequested) {
                    currentCoroutineContext().ensureActive()
                    val t0 = System.nanoTime()
                    val bytes = native { LlamaNative.nativeNextToken() }
                    decodeNanos += System.nanoTime() - t0
                    // A spoken reply's audio comes with the step that made it, the last one too.
                    if (speaking) native { LlamaNative.nativeTakeAudio(flush = false) }?.let { emit(ReplyChunk.Audio(it)) }
                    if (bytes == null) {
                        ended = true
                        break
                    }
                    // Steps that only made audio return no text and do not count as generated tokens.
                    if (speaking && bytes.isEmpty()) {
                        audioSteps++
                        continue
                    }
                    generated++
                    val wasThinking = replyParser.thinking
                    val chunks = replyParser.append(utf8.append(bytes))
                    if (wasThinking || replyParser.thinking) thinkingTokens++
                    for (chunk in chunks) {
                        if (firstTokenMs == null && (chunk is ReplyChunk.Thinking || chunk is ReplyChunk.Text)) {
                            firstTokenMs = (System.nanoTime() - startNanos) / 1_000_000
                        }
                        emit(chunk)
                    }
                    if (replyParser.thinking && thinkingTokens >= params.thinkBudget && !thinkClosed && !speaking && thinkEnd != null) {
                        thinkClosed = true
                        val close = "\n$thinkEnd\n\n"
                        if (native { LlamaNative.nativeForceText(close.toByteArray()) }) {
                            for (c in replyParser.append(utf8.append(close.toByteArray()))) emit(c)
                        }
                    }
                }
                // The native side also ends the reply when the context is full: that is a cut too.
                cutOff = !ended || promptTokens + generated + CONTEXT_FULL_SLACK >= ready.contextSize
                // After stop() the reply is cut short: no tool calls, and no held-back text or audio.
                if (stopRequested) return@withLock
                // Prefill and decoding ran to the end on the loaded accelerator: the NPU, if that, works.
                if (npuUnproven) disarmNpu()
                // The speech decoder holds back the end of the last frame, also when maxNewTokens cut the reply.
                if (speaking) native { LlamaNative.nativeTakeAudio(flush = true) }?.let { emit(ReplyChunk.Audio(it)) }

                val rawParsed = native { LlamaNative.nativeParseReply() }?.map(::utf8)
                val parsedReply = ParsedReply.of(rawParsed)
                for (chunk in replyParser.complete(parsedReply)) {
                    if (firstTokenMs == null && (chunk is ReplyChunk.Thinking || chunk is ReplyChunk.Text)) {
                        firstTokenMs = (System.nanoTime() - startNanos) / 1_000_000
                    }
                    emit(chunk)
                }
            } catch (e: EngineException) {
                // Raised before decoding started (e.g. the message is too long): the model is fine.
                throw e
            } catch (e: NativeFailure) {
                val f = fail("Generation failed", e.cause ?: e)
                failure = f
                throw f
            } finally {
                if (prefillDone) {
                    _lastStats.value = GenerationStats(
                        promptTokens = promptTokens,
                        prefillTokensPerSecond = perSecond(prefillTokens, prefillNanos),
                        generatedTokens = generated,
                        decodeTokensPerSecond = perSecond(generated, decodeNanos),
                        thinkingTokens = thinkingTokens,
                        cutOff = cutOff,
                        timeToFirstTokenMs = firstTokenMs,
                    )
                }
                if (failure == null) _state.value = loaded ?: EngineState.Idle
            }
        }
    }.flowOn(dispatcher)

    override fun stop() {
        stopRequested = true
    }

    /** Native errors arrive as IllegalStateException; tag them so they are not confused with ours. */
    private class NativeFailure(cause: Throwable) : Exception(cause)

    private inline fun <T> native(block: () -> T): T =
        try {
            block()
        } catch (e: IllegalStateException) {
            throw NativeFailure(e)
        }

    private fun fail(what: String, cause: Throwable): EngineException {
        val message = "$what: ${cause.message ?: cause.javaClass.simpleName}"
        Log.e(TAG, message, cause)
        _state.value = EngineState.Error(message)
        return cause as? EngineException ?: EngineException(message, cause)
    }

    private fun requireReadable(path: String, what: String) {
        val file = File(path)
        if (!file.isFile || !file.canRead()) throw EngineException("$what file not readable: $path")
    }

    private fun utf8(bytes: ByteArray) = String(bytes, Charsets.UTF_8)

    private fun perSecond(tokens: Int, nanos: Long): Double = if (nanos <= 0) 0.0 else tokens * 1e9 / nanos

    private companion object {
        const val TAG = "LlamaEngine"
        /** A reply that ends this close to the context size ran out of room rather than finishing. */
        const val CONTEXT_FULL_SLACK = 8

        /** The NPU backend's file name (OUTPUT_NAME in CMakeLists.txt, NPU_LIB in lpengine.cpp). */
        const val NPU_LIB = "liblp-hexagon.so"
        const val NO_NPU_BUILD = AcceleratorFallback.NO_NPU_BUILD

        /** [benchmark]: a fixed prompt with a long answer, 32 greedy tokens without thinking. */
        val BENCH_PROMPT = listOf(ChatMessage(Role.USER, "Count from 1 to 100, separated by commas."))
        val BENCH_PARAMS = GenerationParams(temperature = 0f, maxNewTokens = 32, think = false)

        /** LFM3 (`<tool_call>`) and LFM2/2.5 (`<|tool_call_start|>`) tool-call openers. */
        val LFM_TOOL_STARTS = listOf("<tool_call>", "<|tool_call_start|>")
    }
}
