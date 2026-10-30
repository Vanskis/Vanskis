package com.vanskis.liquidpocket.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import com.vanskis.liquidpocket.chat.LivePhase
import com.vanskis.liquidpocket.chat.LiveUi
import com.vanskis.liquidpocket.chat.LiveVoiceModel
import com.vanskis.liquidpocket.ui.components.Accent
import kotlin.math.PI
import kotlin.math.sin

/**
 * The full-screen live voice conversation: an orb that follows the mic while listening, turns while
 * the reply is coming and ripples while it is spoken, a caption, and a close button. It covers the
 * chat (and takes its touches); the turns still land in the chat underneath.
 */
@Composable
fun LiveVoiceScreen(live: LiveUi, modelName: String, onClose: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
            FilledTonalIconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "End live voice")
            }
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(32.dp),
            ) {
                LiveOrb(live.phase, live.level, modifier = Modifier.size(280.dp))
                Text(caption(live.phase), style = MaterialTheme.typography.headlineSmall)
            }
            Text(
                modelName + if (live.model == LiveVoiceModel.TEXT) " with Android voice" else "",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp),
            )
        }
    }
}

private fun caption(phase: LivePhase): String = when (phase) {
    LivePhase.LISTENING -> "Listening"
    LivePhase.CAPTURING -> "Listening…"
    LivePhase.WAITING -> "Thinking…"
    LivePhase.SPEAKING -> "Speaking · talk to interrupt"
}

/** Every animated value is read while drawing, so the orb redraws without recomposing. */
@Composable
private fun LiveOrb(phase: LivePhase, level: Float, modifier: Modifier = Modifier) {
    val hearing = phase == LivePhase.LISTENING || phase == LivePhase.CAPTURING
    val mic by animateFloatAsState(if (hearing) level else 0f, animationSpec = tween(90), label = "orbLevel")
    val time = rememberInfiniteTransition(label = "orb")
    val turn by time.animateFloat(0f, 1f, infiniteRepeatable(tween(3_000, easing = LinearEasing)), label = "orbTurn")
    val ripple by time.animateFloat(0f, 1f, infiniteRepeatable(tween(1_400, easing = LinearEasing)), label = "orbRipple")
    Canvas(modifier = modifier) {
        val full = size.minDimension / 2
        val base = full * 0.55f
        val breath = sin(turn * 2 * PI).toFloat()
        val radius = when (phase) {
            LivePhase.LISTENING -> base * (1f + 0.04f * breath + 0.35f * mic)
            LivePhase.CAPTURING -> base * (1.08f + 0.4f * mic)
            LivePhase.WAITING -> base * (0.92f + 0.05f * breath)
            LivePhase.SPEAKING -> base * (1.02f + 0.06f * sin(ripple * 2 * PI).toFloat())
        }.coerceAtMost(full * 0.9f)
        if (phase == LivePhase.SPEAKING) {
            // Rings that leave the orb, as sound does.
            for (k in 0 until 2) {
                val t = (ripple + k * 0.5f) % 1f
                drawCircle(
                    brush = Brush.radialGradient(Accent.colors, center = center, radius = full),
                    radius = radius + (full - radius) * t,
                    alpha = (1f - t) * 0.5f,
                    style = Stroke(width = 6.dp.toPx() * (1f - t) + 1f),
                )
            }
        }
        if (hearing) {
            drawCircle(
                brush = Brush.radialGradient(listOf(Accent.Purple.copy(alpha = 0.35f), Accent.Purple.copy(alpha = 0f)), center, radius * 1.5f),
                radius = radius * 1.5f,
            )
        }
        rotate(degrees = if (phase == LivePhase.WAITING) turn * 360f else turn * 60f) {
            drawCircle(
                brush = Brush.sweepGradient(Accent.colors + Accent.colors.first(), center),
                radius = radius,
            )
        }
        drawCircle(
            brush = Brush.radialGradient(
                listOf(Color.White.copy(alpha = 0.45f), Color.Transparent),
                center = Offset(center.x - radius * 0.3f, center.y - radius * 0.35f),
                radius = radius * 0.9f,
            ),
            radius = radius,
        )
    }
}
