package com.vanskis.liquidpocket.ui

import android.content.Context
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import com.vanskis.liquidpocket.ui.components.MathImage
import com.vanskis.liquidpocket.ui.components.MathRenderer
import ru.noties.jlatexmath.JLatexMathAndroid
import ru.noties.jlatexmath.JLatexMathDrawable

/** [MathRenderer] on JLaTeXMath, offline: a formula that does not parse comes back null and shows as its source. */
class JLatexMathRenderer(context: Context) : MathRenderer {
    init {
        JLatexMathAndroid.init(context.applicationContext)
    }

    override fun render(latex: String, textSizePx: Float, color: Int): MathImage? {
        val drawable = try {
            JLatexMathDrawable.builder(latex).textSize(textSizePx).color(color).padding(0).align(JLatexMathDrawable.ALIGN_LEFT).build()
        } catch (e: Exception) {
            return null
        }
        val width = drawable.intrinsicWidth
        val height = drawable.intrinsicHeight
        if (width <= 0 || height <= 0) return null
        drawable.setBounds(0, 0, width, height)
        return MathImage(width.toFloat(), height.toFloat()) { drawIntoCanvas { drawable.draw(it.nativeCanvas) } }
    }
}
