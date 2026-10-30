package com.vanskis.liquidpocket.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntSize
import com.vanskis.liquidpocket.ui.components.MathImage
import com.vanskis.liquidpocket.ui.components.MathRenderer
import ru.noties.jlatexmath.JLatexMathAndroid
import ru.noties.jlatexmath.JLatexMathDrawable

/**
 * [MathRenderer] on JLaTeXMath, offline: a formula that does not parse comes back null and shows as its source.
 *
 * Each formula is drawn once into a software bitmap. Drawn straight onto the hardware canvas, some
 * display formulas came out blank (their space reserved, nothing painted); a bitmap that is still
 * blank after drawing is treated as a formula that did not render, so its source shows instead.
 */
class JLatexMathRenderer(context: Context) : MathRenderer {
    init {
        JLatexMathAndroid.init(context.applicationContext)
    }

    override fun render(latex: String, textSizePx: Float, color: Int): MathImage? {
        val bitmap = try {
            val drawable = JLatexMathDrawable.builder(latex).textSize(textSizePx).color(color).padding(0).align(JLatexMathDrawable.ALIGN_LEFT).build()
            val width = drawable.intrinsicWidth
            val height = drawable.intrinsicHeight
            if (width <= 0 || height <= 0 || width.toLong() * height > MAX_PIXELS) return null
            drawable.setBounds(0, 0, width, height)
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { drawable.draw(Canvas(it)) }
        } catch (e: Exception) {
            return null
        }
        if (blank(bitmap)) {
            bitmap.recycle()
            return null
        }
        val image = bitmap.asImageBitmap()
        val size = IntSize(bitmap.width, bitmap.height)
        // At its own size: the caller scales the canvas when the formula is too wide.
        return MathImage(size.width.toFloat(), size.height.toFloat()) { drawImage(image, dstSize = size, filterQuality = FilterQuality.High) }
    }

    private fun blank(bitmap: Bitmap): Boolean {
        val row = IntArray(bitmap.width)
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            if (row.any { it ushr 24 != 0 }) return false
        }
        return true
    }

    private companion object {
        /** A formula bigger than this (about 16 MB of pixels) is shown as its source. */
        const val MAX_PIXELS = 4_000_000L
    }
}
