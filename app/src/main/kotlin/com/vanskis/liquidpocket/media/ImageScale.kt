package com.vanskis.liquidpocket.media

import kotlin.math.max
import kotlin.math.roundToInt

/** Size math and pixel packing for images sent to the model; the decoding itself is Android's. */
object ImageScale {
    /** The long side of an image the model is given. */
    const val MAX_SIDE = 768

    /** [width] x [height] scaled down (never up) so the long side is at most [maxSide], keeping the aspect. */
    fun fit(width: Int, height: Int, maxSide: Int = MAX_SIDE): Pair<Int, Int> {
        val long = max(width, height)
        if (long <= maxSide) return width to height
        val scale = maxSide.toDouble() / long
        return (width * scale).roundToInt().coerceIn(1, maxSide) to (height * scale).roundToInt().coerceIn(1, maxSide)
    }

    /** The largest power-of-two decode subsampling that keeps the long side at least [maxSide]. */
    fun sampleSize(width: Int, height: Int, maxSide: Int = MAX_SIDE): Int {
        val long = max(width, height)
        var n = 1
        while (long / (n * 2) >= maxSide) n *= 2
        return n
    }

    /** Packs ARGB_8888 pixels (alpha dropped) into row-major RGB, 3 bytes per pixel. */
    fun argbToRgb(pixels: IntArray): ByteArray {
        val out = ByteArray(pixels.size * 3)
        for (i in pixels.indices) {
            val p = pixels[i]
            out[i * 3] = (p shr 16).toByte()
            out[i * 3 + 1] = (p shr 8).toByte()
            out[i * 3 + 2] = p.toByte()
        }
        return out
    }

    /** How to turn a stored image upright: rotate clockwise by [degrees], then mirror horizontally. */
    data class Orientation(val degrees: Int, val mirror: Boolean) {
        val swapsSides: Boolean get() = degrees == 90 || degrees == 270
    }

    /** The EXIF orientation tag (1..8, anything else is upright). */
    fun orientation(exif: Int): Orientation = when (exif) {
        2 -> Orientation(0, true)
        3 -> Orientation(180, false)
        4 -> Orientation(180, true)
        5 -> Orientation(90, true)
        6 -> Orientation(90, false)
        7 -> Orientation(270, true)
        8 -> Orientation(270, false)
        else -> Orientation(0, false)
    }
}
