package com.vanskis.liquidpocket.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * The model's reasoning above its reply, collapsed to a "Thinking…" / "Thought for 12s" header that
 * expands on tap. Nothing is shown when [thinking] is null. [seconds] is how long it thought, once known;
 * until then a timer counts while [streaming]. [tokens] is how many tokens it has thought.
 */
@Composable
fun ThinkingSection(thinking: String?, seconds: Int?, streaming: Boolean, modifier: Modifier = Modifier, tokens: Int? = null) {
    if (thinking == null) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    // Counts while it thinks and stops when the reply starts, until the turn brings its own time.
    var liveSeconds by remember { mutableStateOf<Int?>(null) }
    if (streaming) {
        LaunchedEffect(Unit) {
            val start = System.nanoTime()
            while (true) {
                val elapsed = (System.nanoTime() - start) / 1_000_000
                liveSeconds = (elapsed / 1000).toInt()
                delay(1000 - elapsed % 1000)
            }
        }
    }
    val colors = MaterialTheme.colorScheme

    Column(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .clickable(role = Role.Button, onClickLabel = if (expanded) "Hide thinking" else "Show thinking") {
                    expanded = !expanded
                }
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            // Read in the draw phase, so the pulse does not recompose the reply every frame.
            val pulse: () -> Float = if (streaming) {
                val transition = rememberInfiniteTransition(label = "thinking")
                val value = transition.animateFloat(
                    initialValue = 0.45f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(durationMillis = 800), RepeatMode.Reverse),
                    label = "thinkingAlpha",
                )
                val read: () -> Float = { value.value }
                read
            } else {
                { 1f }
            }
            Text(
                thinkingLabel(seconds ?: liveSeconds, streaming, tokens),
                style = MaterialTheme.typography.labelLarge,
                color = colors.onSurfaceVariant,
                modifier = Modifier.graphicsLayer { alpha = pulse() },
            )
            Icon(
                if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = colors.onSurfaceVariant,
                modifier = Modifier.padding(start = 2.dp).size(18.dp),
            )
        }
        AnimatedVisibility(visible = expanded, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Row(modifier = Modifier.height(IntrinsicSize.Min).padding(start = 8.dp, top = 4.dp, bottom = 8.dp)) {
                Box(Modifier.width(2.dp).fillMaxHeight().background(colors.outlineVariant, RoundedCornerShape(1.dp)))
                SelectionContainer(modifier = Modifier.padding(start = 12.dp)) {
                    Text(thinking, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
            }
        }
    }
}
