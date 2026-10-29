package com.vanskis.liquidpocket.engine

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * A local LLM backend. Liquid Pocket ships [Engines.llama]; LEAP or an NPU backend can implement
 * this later.
 *
 * One model is loaded at a time. Calls are safe from any thread; implementations serialize them.
 */
interface Engine {
    val state: StateFlow<EngineState>

    /** What the loaded model (with its projector) accepts and produces. [Capabilities.NONE] when idle. */
    val capabilities: StateFlow<Capabilities>

    /** Stats of the most recent finished (or stopped) reply, null before the first one. */
    val lastStats: StateFlow<GenerationStats?>

    /** Loads [modelPath], unloading any current model first. Throws [EngineException] on failure. */
    suspend fun load(modelPath: String, config: EngineConfig = EngineConfig())

    suspend fun unload()

    /**
     * Streams the assistant's reply to the whole conversation [messages] (system, user, assistant
     * and tool turns, oldest first, ending with a user or tool turn). The engine reuses its cache
     * for a prefix it has already processed, so passing the full history each turn is cheap.
     *
     * Emits [ReplyChunk.Text] as text is generated, [ReplyChunk.Audio] when [GenerationParams.speak]
     * is set and the model speaks, and [ReplyChunk.ToolCall] for each tool call parsed once the reply
     * ends. Completes when the reply ends or [stop] is called. Images and audio on a message require
     * the matching capability, otherwise the flow fails with [EngineException].
     */
    fun chat(messages: List<ChatMessage>, params: GenerationParams = GenerationParams()): Flow<ReplyChunk>

    /** Ends the current reply early; the text streamed so far stays valid. */
    fun stop()
}

data class EngineConfig(
    /** Clamped by the engine to the model's trained context and to 2048..32768. */
    val contextSize: Int = 8192,
    /** The multimodal projector (vision, audio in, speech out) that goes with the model, if any. */
    val mmprojPath: String? = null,
)

data class GenerationParams(
    val temperature: Float = 0.3f,
    val maxNewTokens: Int = 1024,
    /** Tools offered to the model; rendered into the prompt by the model's chat template. */
    val tools: List<ToolSpec> = emptyList(),
    /** Ask the model to answer in speech as well as text (needs [Capabilities.audioOut]). */
    val speak: Boolean = false,
)

data class Capabilities(
    val vision: Boolean,
    val audioIn: Boolean,
    val audioOut: Boolean,
    /** Sample rate [AudioInput.samples] must be at, 0 without [audioIn]. */
    val audioInSampleRate: Int,
    /** Sample rate of [ReplyChunk.Audio.samples], 0 without [audioOut]. */
    val audioOutSampleRate: Int,
) {
    companion object {
        val NONE = Capabilities(false, false, false, 0, 0)
    }
}

enum class Role { SYSTEM, USER, ASSISTANT, TOOL }

data class ChatMessage(
    val role: Role,
    val content: String,
    /** USER turns only: images shown with the text, in order before it. */
    val images: List<ImageInput> = emptyList(),
    /** USER turns only: audio clips heard with the text, in order before it. */
    val audio: List<AudioInput> = emptyList(),
    /** ASSISTANT turns only: the tool calls the model made in this turn. */
    val toolCalls: List<ToolCall> = emptyList(),
    /** TOOL turns only: the name of the tool whose result [content] is. */
    val toolName: String? = null,
)

/** Packed RGB, 3 bytes per pixel, row-major, [width] * [height] * 3 bytes. Callers downscale first. */
class ImageInput(val width: Int, val height: Int, val rgb: ByteArray) {
    init {
        require(width > 0 && height > 0 && rgb.size == width * height * 3) { "rgb must be width*height*3 bytes" }
    }
}

/** Mono PCM float samples in -1..1 at [Capabilities.audioInSampleRate]. */
class AudioInput(val samples: FloatArray)

/** A tool the model may call. [parametersJson] is a JSON Schema object for the arguments. */
data class ToolSpec(val name: String, val description: String, val parametersJson: String)

/** A call the model made. [argumentsJson] is a JSON object. */
data class ToolCall(val name: String, val argumentsJson: String)

sealed interface ReplyChunk {
    data class Text(val text: String) : ReplyChunk

    /** Mono PCM float samples at [Capabilities.audioOutSampleRate]. */
    class Audio(val samples: FloatArray) : ReplyChunk

    data class ToolCall(val call: com.vanskis.liquidpocket.engine.ToolCall) : ReplyChunk
}

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
