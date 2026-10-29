package com.vanskis.liquidpocket.engine

/**
 * [Capabilities] from `LlamaNative.nativeCapabilities()`: `[vision, audioIn, audioInSampleRate,
 * audioOut, audioOutSampleRate]`. Speech output counts only with a sample rate to play it at.
 */
internal object NativeCapabilities {
    const val SIZE = 5

    fun of(caps: IntArray): Capabilities {
        require(caps.size == SIZE) { "Expected $SIZE capability values, got ${caps.size}" }
        val audioOut = caps[3] != 0 && caps[4] > 0
        return Capabilities(
            vision = caps[0] != 0,
            audioIn = caps[1] != 0,
            audioOut = audioOut,
            audioInSampleRate = caps[2],
            audioOutSampleRate = if (audioOut) caps[4] else 0,
        )
    }
}
