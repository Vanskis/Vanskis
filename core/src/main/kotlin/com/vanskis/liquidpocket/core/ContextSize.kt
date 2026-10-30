package com.vanskis.liquidpocket.core

/** Context-window sizes offered in settings. Mirrored by the clamp in the engine's JNI code. */
object ContextSize {
    const val MIN = 2048
    const val MAX = 262144
    const val DEFAULT = 8192

    /** Above this, Settings warns that replies slow down and the app may run out of memory. */
    const val WARN_ABOVE = 32768

    /** The settings slider's stops: 2K steps up to 32K, then larger jumps up to [MAX]. */
    val OPTIONS: List<Int> = (MIN..WARN_ABOVE step 2048).toList() + listOf(49152, 65536, 98304, 131072, 196608, MAX)

    /** Clamps [requested] to MIN..MAX, then to the model's training context if that is known (> 0). */
    fun clamp(requested: Int, trainedCtx: Int = 0): Int {
        val bounded = requested.coerceIn(MIN, MAX)
        return if (trainedCtx > 0) minOf(bounded, trainedCtx) else bounded
    }

    /** The index in [OPTIONS] of the stop nearest [size]. */
    fun nearestOption(size: Int): Int = OPTIONS.indices.minBy { kotlin.math.abs(OPTIONS[it] - size) }
}
