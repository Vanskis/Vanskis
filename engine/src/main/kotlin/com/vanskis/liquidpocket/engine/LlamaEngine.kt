// Adapted from llama.cpp examples/llama.android/lib/.../InferenceEngineImpl.kt
// (MIT, Copyright (c) 2023-2026 The ggml authors; Android example by Arm).
package com.vanskis.liquidpocket.engine

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
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
            llama ?: LlamaEngine(context.applicationContext.applicationInfo.nativeLibraryDir).also { llama = it }
        }
}

/**
 * [Engine] on llama.cpp. All native calls run on one dedicated thread (llama.cpp state is not
 * thread-safe, and OpenMP keeps one worker pool per calling thread); [mutex] keeps a reply from
 * interleaving with another reply or a load while its flow is suspended in `emit`.
 */
internal class LlamaEngine(private val nativeLibDir: String) : Engine {
    private val dispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "lp-engine").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val mutex = Mutex()

    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
    override val state: StateFlow<EngineState> = _state.asStateFlow()
    private val _lastStats = MutableStateFlow<GenerationStats?>(null)
    override val lastStats: StateFlow<GenerationStats?> = _lastStats.asStateFlow()

    @Volatile
    private var stopRequested = false

    // Engine-thread state.
    private var nativeReady = false
    private var loaded: EngineState.Ready? = null

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
                        LlamaNative.nativeUnload()
                    }
                    val file = File(modelPath)
                    if (!file.isFile || !file.canRead()) throw EngineException("Model file not readable: $modelPath")
                    val threads = ThreadPolicy.threadsFor(Runtime.getRuntime().availableProcessors())
                    val contextSize = LlamaNative.nativeLoad(modelPath, config.contextSize, threads)
                    val ready = EngineState.Ready(modelPath, contextSize)
                    loaded = ready
                    _state.value = ready
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
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
                    LlamaNative.nativeUnload()
                }
                _state.value = EngineState.Idle
            }
        }
    }

    override fun chat(messages: List<ChatMessage>, params: GenerationParams): Flow<String> = flow {
        mutex.withLock {
            val ready = loaded ?: throw EngineException("No model loaded")
            if (messages.lastOrNull()?.role != Role.USER) {
                throw EngineException("The conversation must end with a user message")
            }
            stopRequested = false
            _state.value = EngineState.Generating
            var promptTokens = 0
            var prefillTokens = 0
            var prefillNanos = 0L
            var prefillDone = false
            var generated = 0
            var decodeNanos = 0L
            var failure: EngineException? = null
            try {
                val budget = ContextBudget.of(ready.contextSize, params.maxNewTokens)
                val trimmed = ConversationTrimmer.trim(messages, budget) { msgs ->
                    native { LlamaNative.nativeCountPromptTokens(roles(msgs), contents(msgs)) }
                }.messages
                val counts = native {
                    LlamaNative.nativeBeginReply(roles(trimmed), contents(trimmed), params.temperature)
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
                while (generated < params.maxNewTokens && !stopRequested) {
                    currentCoroutineContext().ensureActive()
                    val t0 = System.nanoTime()
                    val bytes = native { LlamaNative.nativeNextToken() } ?: break
                    decodeNanos += System.nanoTime() - t0
                    generated++
                    val text = utf8.append(bytes)
                    if (text.isNotEmpty()) emit(text)
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

    private fun roles(msgs: List<ChatMessage>) = IntArray(msgs.size) { LlamaNative.roleCode(msgs[it].role) }

    private fun contents(msgs: List<ChatMessage>) = Array(msgs.size) { msgs[it].content.toByteArray(Charsets.UTF_8) }

    private fun perSecond(tokens: Int, nanos: Long): Double = if (nanos <= 0) 0.0 else tokens * 1e9 / nanos

    private companion object {
        const val TAG = "LlamaEngine"
    }
}
