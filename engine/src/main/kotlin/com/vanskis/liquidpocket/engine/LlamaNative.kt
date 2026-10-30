package com.vanskis.liquidpocket.engine

/**
 * JNI surface of `liblpengine.so` (src/main/cpp/lpengine.cpp). Call only from the engine thread.
 * Failures surface as [IllegalStateException] thrown by the native side.
 *
 * None of these use `@FastNative`: model loading, prompt decoding and token generation run for
 * a long time and must not block garbage collection.
 *
 * A conversation is passed as the arrays of a [NativePrompt], in its field order (`<prompt>` below).
 * `think` is the template's `enable_thinking` ([GenerationParams.think]); the functions that render a
 * conversation must get the same value for the same reply.
 */
internal object LlamaNative {
    const val ROLE_SYSTEM = 0
    const val ROLE_USER = 1
    const val ROLE_ASSISTANT = 2
    const val ROLE_TOOL = 3

    const val MEDIA_IMAGE = 0
    const val MEDIA_AUDIO = 1

    fun roleCode(role: Role): Int = when (role) {
        Role.SYSTEM -> ROLE_SYSTEM
        Role.USER -> ROLE_USER
        Role.ASSISTANT -> ROLE_ASSISTANT
        Role.TOOL -> ROLE_TOOL
    }

    /** Sets up logging and loads the ggml CPU backend variants found in [nativeLibDir]. Once per process. */
    external fun nativeInit(nativeLibDir: String)

    /**
     * Loads the NPU (Hexagon) backend from [nativeLibDir], after [nativeInit]. Returns null when it
     * is ready, otherwise why the NPU cannot be used. Does not throw.
     */
    external fun nativeInitNpu(nativeLibDir: String): String?

    /**
     * Loads a model and its multimodal projector [mmprojPath] (null for none), replacing any loaded
     * one, with every layer on the NPU when [npu] (after a successful [nativeInitNpu]), else on the
     * CPU only. Returns the context size actually allocated.
     */
    external fun nativeLoad(modelPath: String, mmprojPath: String?, contextSize: Int, threads: Int, npu: Boolean): Int

    /**
     * `[vision, audioIn, audioInSampleRate, audioOut, audioOutSampleRate]` of the loaded projector
     * (0/1 flags), zeros without one. See [NativeCapabilities].
     */
    external fun nativeCapabilities(): IntArray

    external fun nativeUnload()

    /** Tokens taken by the rendered `<prompt>`, including the assistant generation prompt. Media data may be null. */
    external fun nativeCountPromptTokens(
        roles: IntArray,
        contents: Array<ByteArray>,
        toolNames: Array<ByteArray>,
        toolCalls: Array<Array<ByteArray>>,
        mediaMsg: IntArray,
        mediaKind: IntArray,
        mediaDims: IntArray,
        mediaData: Array<Any?>,
        tools: Array<ByteArray>,
        think: Boolean,
    ): Int

    /**
     * Starts a reply to `<prompt>`. Returns `[promptTokens, prefillTokens]`; prefill tokens are still to decode.
     * [speak]: also answer in speech (see [nativeTakeAudio]), ignored when the projector cannot.
     */
    external fun nativeBeginReply(
        roles: IntArray,
        contents: Array<ByteArray>,
        toolNames: Array<ByteArray>,
        toolCalls: Array<Array<ByteArray>>,
        mediaMsg: IntArray,
        mediaKind: IntArray,
        mediaDims: IntArray,
        mediaData: Array<Any?>,
        tools: Array<ByteArray>,
        temperature: Float,
        speak: Boolean,
        think: Boolean,
    ): IntArray

    /**
     * The markers the streamed reply text must be filtered on (see [ReplyFilter]), as UTF-8:
     * `[[thinkStart] or [], thinkEnds, toolStarts, [thinkStart] or []]`; the last is non-empty when the
     * generation prompt already opens the think block (the reply starts inside it).
     */
    external fun nativeReplyMarkers(
        roles: IntArray,
        contents: Array<ByteArray>,
        toolNames: Array<ByteArray>,
        toolCalls: Array<Array<ByteArray>>,
        tools: Array<ByteArray>,
        think: Boolean,
    ): Array<Array<ByteArray>>

    /**
     * Decodes the next step of the queued prompt (up to 512 text tokens or one media chunk) and
     * returns how many prompt tokens remain.
     */
    external fun nativePrefillStep(): Int

    /**
     * Generates one token: its UTF-8 bytes (maybe part of a character), or null when the reply ends.
     * A spoken reply also has steps that only produce audio: they return an empty array.
     */
    external fun nativeNextToken(): ByteArray?

    /**
     * Feeds UTF-8 [text] into a text reply as if the model had written it (closing a think block
     * that ran past its budget). False, feeding nothing, when the context has no room for it.
     */
    external fun nativeForceText(text: ByteArray): Boolean

    /**
     * The speech PCM (mono floats at `audioOutSampleRate`) generated since the last call, or null if
     * none. [flush] once the reply has ended: the speech decoder's held-back tail comes out too.
     */
    external fun nativeTakeAudio(flush: Boolean): FloatArray?

    /**
     * Parses the finished reply with the chat template's parser: `[content, name, arguments, name,
     * arguments, ...]` as UTF-8, or null when the reply does not match the template's format.
     */
    external fun nativeParseReply(): Array<ByteArray>?

    /**
     * Whether the next NPU [nativeLoad] records where each op runs (read by [nativeAcceleratorReport]).
     * It adds one sync per graph split, so it is only for the benchmark; a reload without it clears it.
     */
    external fun nativeSetOpTrace(on: Boolean)

    /**
     * What the traced NPU load and the replies since found: the layers offloaded, the buffer sizes,
     * the graph splits, the NPU's warnings, and the share of ops run on the NPU with the CPU ops
     * named. Null when the loaded model was not traced.
     */
    external fun nativeAcceleratorReport(): String?
}
