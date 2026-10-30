package com.vanskis.liquidpocket.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit

/** Lays out LaTeX formulas. The app provides one through [LocalMathRenderer]; without one, formulas show as their source. */
fun interface MathRenderer {
    /** [latex] at [textSizePx] in [color] (ARGB), or null when it does not parse. */
    fun render(latex: String, textSizePx: Float, color: Int): MathImage?
}

/** A laid-out formula: its size in px and how to draw it at that size. */
class MathImage(val width: Float, val height: Float, val draw: DrawScope.() -> Unit)

val LocalMathRenderer = staticCompositionLocalOf<MathRenderer?> { null }

private const val MATH_ID = "math:"

/**
 * The inline images for [formulas], keyed "math:<index>"; a formula that does not parse (or has no
 * renderer) is left out and [appendMath] shows its source instead. Wider than [maxWidthPx], a
 * formula is scaled down to fit.
 */
@Composable
internal fun mathInlineContent(
    formulas: List<MathText.Formula>,
    fontSize: TextUnit,
    color: Color,
    maxWidthPx: Float,
): Map<String, InlineTextContent> {
    val renderer = LocalMathRenderer.current ?: return emptyMap()
    if (formulas.isEmpty()) return emptyMap()
    val density = LocalDensity.current
    val sizePx = with(density) { fontSize.toPx() }
    val argb = color.toArgb()
    // Parsing a formula is slow; a streaming reply recomposes every token, so each is laid out once.
    val cache = remember(renderer, sizePx, argb) { HashMap<MathText.Formula, MathImage?>() }
    val content = HashMap<String, InlineTextContent>()
    formulas.forEachIndexed { i, formula ->
        val image = cache.getOrPut(formula) {
            renderer.render(if (formula.display) "\\displaystyle ${formula.latex}" else formula.latex, sizePx, argb)
        } ?: return@forEachIndexed
        val fit = if (maxWidthPx > 0f && image.width > maxWidthPx) maxWidthPx / image.width else 1f
        val placeholder = with(density) {
            Placeholder((image.width * fit).toSp(), (image.height * fit).toSp(), PlaceholderVerticalAlign.TextCenter)
        }
        content["$MATH_ID$i"] = InlineTextContent(placeholder) {
            Canvas(Modifier.fillMaxSize()) { scale(fit, pivot = Offset.Zero) { image.draw(this) } }
        }
    }
    return content
}

/** Formula [index]: its image from [content], else its [source] in monospace. */
internal fun AnnotatedString.Builder.appendMath(index: Int, source: String, content: Map<String, InlineTextContent>) {
    val id = "$MATH_ID$index"
    if (id in content) appendInlineContent(id, source) else withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(source) }
}
