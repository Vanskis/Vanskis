package com.vanskis.liquidpocket.engine

/**
 * JNI surface of `liblpengine.so` (src/main/cpp/lpengine.cpp). Call only from the engine thread.
 * Failures surface as [IllegalStateException] thrown by the native side.
 *
 * None of these use `@FastNative`: model loading, prompt decoding and token generation run for
 * a long time and must not block garbage collection.
 */
internal object LlamaNative {
    const val ROLE_SYSTEM = 0
    const val ROLE_USER = 1
    const val ROLE_ASSISTANT = 2

    fun roleCode(role: Role): Int = when (role) {
        Role.SYSTEM -> ROLE_SYSTEM
        Role.USER -> ROLE_USER
        Role.ASSISTANT -> ROLE_ASSISTANT
    }

    /** Sets up logging and loads the ggml CPU backend variants found in [nativeLibDir]. Once per process. */
    external fun nativeInit(nativeLibDir: String)

    /** Loads a model, replacing any loaded one. Returns the context size actually allocated. */
    external fun nativeLoad(modelPath: String, contextSize: Int, threads: Int): Int

    external fun nativeUnload()

    /** Tokens taken by the rendered conversation, including the assistant generation prompt. */
    external fun nativeCountPromptTokens(roles: IntArray, contents: Array<ByteArray>): Int

    /** Starts a reply. Returns `[promptTokens, prefillTokens]`; prefill tokens are still to decode. */
    external fun nativeBeginReply(roles: IntArray, contents: Array<ByteArray>, temperature: Float): IntArray

    /** Decodes one batch of the queued prompt tokens and returns how many remain. */
    external fun nativePrefillStep(): Int

    /** Generates one token: its UTF-8 bytes (maybe part of a character), or null when the reply ends. */
    external fun nativeNextToken(): ByteArray?
}
