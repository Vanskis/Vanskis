package com.vanskis.liquidpocket.engine

import java.util.Locale
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
     * Emits [ReplyChunk.Thinking] as the model reasons before answering (models that think, see
     * [GenerationParams.think]), [ReplyChunk.Text] as the answer is generated, [ReplyChunk.Audio] when [GenerationParams.speak]
     * is set and the model speaks, and [ReplyChunk.ToolCall] for each tool call parsed once the reply
     * ends. Completes when the reply ends or [stop] is called. Images and audio on a message require
     * the matching capability, otherwise the flow fails with [EngineException].
     */
    fun chat(messages: List<ChatMessage>, params: GenerationParams = GenerationParams()): Flow<ReplyChunk>

    /** Ends the current reply early; the text streamed so far stays valid. */
    fun stop()

    /**
     * Tokens [messages] take as the prompt of a [chat] with [params] (tools and [GenerationParams.think]
     * change the rendering), including the assistant generation prompt; media counts without being
     * processed. Waits for a running reply or load. Throws [EngineException] without a loaded model or
     * if the conversation cannot be rendered.
     */
    suspend fun countTokens(messages: List<ChatMessage>, params: GenerationParams = GenerationParams()): Int =
        throw UnsupportedOperationException("countTokens is not implemented by ${javaClass.simpleName}")

    /**
     * Whether this build ships the NPU backend, so [Accelerator.NPU] can be tried. The phone may still
     * lack a usable NPU: [load] then falls back to the CPU and says why in [EngineState.Ready.acceleratorNote].
     */
    val npuAvailable: Boolean get() = false

    /**
     * Whether the NPU took the previous process down. A native NPU error aborts the process, so this
     * is how it is learned; [load] then uses the CPU for [Accelerator.NPU] for the rest of this process.
     */
    val npuCrashedLastRun: Boolean get() = false

    /**
     * Decode speed of the loaded model on each accelerator: a short fixed prompt and 32 generated
     * tokens on the CPU, then on the NPU when [npuAvailable]. Reloads the model as needed and leaves it
     * loaded as it was. Throws [EngineException] without a loaded model.
     */
    suspend fun benchmark(): BenchResult =
        throw UnsupportedOperationException("benchmark is not implemented by ${javaClass.simpleName}")
}

/** Where the model's layers run. */
enum class Accelerator { CPU, NPU }

data class EngineConfig(
    /** Clamped by the engine to the model's trained context and to 2048..262144. */
    val contextSize: Int = 8192,
    /** The multimodal projector (vision, audio in, speech out) that goes with the model, if any. */
    val mmprojPath: String? = null,
    /** Requested accelerator; [Accelerator.NPU] falls back to the CPU when it cannot be used. */
    val accelerator: Accelerator = Accelerator.CPU,
)

/** Decode tokens per second from [Engine.benchmark]. */
data class BenchResult(
    val cpuTokPerSec: Double,
    /** Null when the NPU was not available or could not run the model; [npuNote] says why. */
    val npuTokPerSec: Double?,
    val npuNote: String? = null,
    /** Where the NPU run's layers and ops actually ran, when it was traced. */
    val npuReport: String? = null,
) {
    /** Plainly, whether the NPU speeds the model up; null when it did not run. */
    val verdict: String?
        get() {
            val npu = npuTokPerSec ?: return null
            if (cpuTokPerSec <= 0.0) return null
            val ratio = npu / cpuTokPerSec
            return when {
                ratio >= FASTER -> String.format(Locale.ROOT, "The NPU is %.1f× faster than the CPU.", ratio)
                ratio <= SLOWER -> String.format(Locale.ROOT, "The NPU is slower than the CPU (%.1f×).", ratio)
                else -> "About the same: the NPU is not speeding this model up."
            }
        }

    private companion object {
        /** Within about 15% either way is run-to-run noise, not a speed-up. */
        const val FASTER = 1.15
        const val SLOWER = 0.87
    }
}

data class GenerationParams(
    val temperature: Float = 0.3f,
    /**
     * Most tokens the reply may run to; [UNTIL_CONTEXT_FULL] lets it run until the context is full.
     * Trimming the conversation leaves room for at most [REPLY_ROOM] of them.
     */
    val maxNewTokens: Int = 1024,
    /** Most tokens the model may think for; then the think block is closed and the answer begins. */
    val thinkBudget: Int = 2048,
    /** Tools offered to the model; rendered into the prompt by the model's chat template. */
    val tools: List<ToolSpec> = emptyList(),
    /** Ask the model to answer in speech as well as text (needs [Capabilities.audioOut]). */
    val speak: Boolean = false,
    /**
     * Let a model that thinks reason before answering (the template's `enable_thinking`); its
     * reasoning streams as [ReplyChunk.Thinking]. Ignored by models without thinking.
     */
    val think: Boolean = true,
) {
    companion object {
        const val UNTIL_CONTEXT_FULL = Int.MAX_VALUE
        const val REPLY_ROOM = 1024
    }
}

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

    /** Reasoning the model does before its answer; never part of [Text]. */
    data class Thinking(val text: String) : ReplyChunk

    /** Mono PCM float samples at [Capabilities.audioOutSampleRate]. */
    class Audio(val samples: FloatArray) : ReplyChunk

    data class ToolCall(val call: com.vanskis.liquidpocket.engine.ToolCall) : ReplyChunk
}

data class GenerationStats(
    val promptTokens: Int,
    val prefillTokensPerSecond: Double,
    val generatedTokens: Int,
    val decodeTokensPerSecond: Double,
    /** Of [generatedTokens], those spent inside the think block. */
    val thinkingTokens: Int = 0,
    /** Milliseconds from the start of the reply to its first [ReplyChunk.Text] or [ReplyChunk.Thinking]; null if none came. */
    val timeToFirstTokenMs: Long? = null,
    /** The reply stopped because it hit [GenerationParams.maxNewTokens] or the context filled, not at its end. */
    val cutOff: Boolean = false,
)

sealed interface EngineState {
    data object Idle : EngineState
    data object Loading : EngineState
    data class Ready(
        val modelPath: String,
        val contextSize: Int,
        /** The accelerator the model actually runs on. */
        val accelerator: Accelerator = Accelerator.CPU,
        /** Why the requested accelerator is not the one in use (the NPU fell back to the CPU), else null. */
        val acceleratorNote: String? = null,
    ) : EngineState
    data object Generating : EngineState
    data class Error(val message: String) : EngineState
}

class EngineException(message: String, cause: Throwable? = null) : Exception(message, cause)
