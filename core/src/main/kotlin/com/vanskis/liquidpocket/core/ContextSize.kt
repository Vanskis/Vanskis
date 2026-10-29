package com.vanskis.liquidpocket.core

/** Context-window sizes offered in settings. Mirrored by the clamp in the engine's JNI code. */
object ContextSize {
    const val MIN = 2048
    const val MAX = 32768
    const val DEFAULT = 8192
    val OPTIONS: List<Int> = listOf(2048, 4096, 8192, 16384, 32768)

    /** Clamps [requested] to MIN..MAX, then to the model's training context if that is known (> 0). */
    fun clamp(requested: Int, trainedCtx: Int = 0): Int {
        val bounded = requested.coerceIn(MIN, MAX)
        return if (trainedCtx > 0) minOf(bounded, trainedCtx) else bounded
    }
}
