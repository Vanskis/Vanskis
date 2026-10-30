package com.vanskis.liquidpocket.settings

/** How many tokens a reply may think before the engine closes its reasoning and the answer starts. */
object ThinkBudget {
    /** No cap: the model thinks until it stops by itself or the context is full. */
    const val NO_LIMIT = Int.MAX_VALUE
    const val DEFAULT = 2048

    /** The settings slider's stops, smallest first; the last is [NO_LIMIT]. */
    val STEPS = listOf(256, 512, 1024, 2048, 4096, 8192, NO_LIMIT)

    /** [value] snapped to the nearest stop at or above it, so a stored value always matches the slider. */
    fun clamp(value: Int): Int = STEPS.firstOrNull { it >= value } ?: NO_LIMIT

    /** "512 tokens", "2K tokens" or "No limit". */
    fun label(value: Int): String = when {
        value == NO_LIMIT -> "No limit"
        value >= 1024 && value % 1024 == 0 -> "${value / 1024}K tokens"
        else -> "$value tokens"
    }
}
