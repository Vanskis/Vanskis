package com.vanskis.liquidpocket.engine

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * A local LLM backend. M1 ships [Engines.llama]; LEAP or an NPU backend can implement this later.
 *
 * One model is loaded at a time. Calls are safe from any thread; implementations serialize them.
 */
interface Engine {
    val state: StateFlow<EngineState>

    /** Stats of the most recent finished (or stopped) reply, null before the first one. */
    val lastStats: StateFlow<GenerationStats?>

    /** Loads [modelPath], unloading any current model first. Throws [EngineException] on failure. */
    suspend fun load(modelPath: String, config: EngineConfig = EngineConfig())

    suspend fun unload()

    /**
     * Streams the assistant's reply to the whole conversation [messages] (system, user and
     * assistant turns, oldest first, ending with a user turn). The engine reuses its cache for a
     * prefix it has already processed, so passing the full history each turn is cheap.
     * Completes when the reply ends or [stop] is called.
     */
    fun chat(messages: List<ChatMessage>, params: GenerationParams = GenerationParams()): Flow<String>

    /** Ends the current reply early; the text streamed so far stays valid. */
    fun stop()
}

data class EngineConfig(
    /** Clamped by the engine to the model's trained context and to 2048..32768. */
    val contextSize: Int = 8192,
)

data class GenerationParams(
    val temperature: Float = 0.3f,
    val maxNewTokens: Int = 1024,
)

enum class Role { SYSTEM, USER, ASSISTANT }

data class ChatMessage(val role: Role, val content: String)

data class GenerationStats(
    val promptTokens: Int,
    val prefillTokensPerSecond: Double,
    val generatedTokens: Int,
    val decodeTokensPerSecond: Double,
)

sealed interface EngineState {
    data object Idle : EngineState
    data object Loading : EngineState
    data class Ready(val modelPath: String, val contextSize: Int) : EngineState
    data object Generating : EngineState
    data class Error(val message: String) : EngineState
}

class EngineException(message: String, cause: Throwable? = null) : Exception(message, cause)
