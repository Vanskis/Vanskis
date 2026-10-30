package com.vanskis.liquidpocket.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Fixed colors. The blue → purple → pink gradient is the Aurora and Wallpaper themes' accent; the
 * current theme's accent is [LocalAppStyle].
 */
object Accent {
    val Blue = Color(0xFF4B8DF8)
    val Purple = Color(0xFF9B72CB)
    val Pink = Color(0xFFD96570)

    /** Amber and red for a context window that is nearly full; fixed so they read as warnings on any wallpaper. */
    val Warning = Color(0xFFF2A900)
    val Critical = Color(0xFFE5484D)
}

/** Paints the content's pixels with [brush] instead of its own colors (for icons). */
fun Modifier.gradientTint(brush: Brush): Modifier = this
    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
    .drawWithContent {
        drawContent()
        drawRect(brush, blendMode = BlendMode.SrcAtop)
    }

/** The assistant's sparkle in the accent gradient; it turns slowly while [busy]. */
@Composable
fun AssistantMark(modifier: Modifier = Modifier, busy: Boolean = false, size: Dp = 22.dp) {
    // Read in the draw phase, so the spin does not recompose the reply every frame.
    val angle: () -> Float = if (busy) {
        val spin = rememberInfiniteTransition(label = "mark")
        val value = spin.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 2400, easing = LinearEasing)),
            label = "markAngle",
        )
        val read: () -> Float = { value.value }
        read
    } else {
        { 0f }
    }
    val brush = LocalAppStyle.current.brush
    Icon(
        AppIcons.Sparkle,
        contentDescription = null,
        modifier = modifier.size(size).graphicsLayer { rotationZ = angle() }.gradientTint(brush),
    )
}
