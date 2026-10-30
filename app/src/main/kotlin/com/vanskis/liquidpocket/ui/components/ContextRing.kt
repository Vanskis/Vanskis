package com.vanskis.liquidpocket.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * A small gauge of how much of the context window the conversation fills: the accent gradient,
 * amber from 80%, red from 95% ([contextLevel]). A tap shows "3.1K / 8K tokens used" and Compact now.
 * [compactEnabled] is false while Compact cannot run (no compaction yet, or a reply is streaming).
 */
@Composable
fun ContextRing(
    used: Int,
    total: Int,
    onCompact: () -> Unit,
    modifier: Modifier = Modifier,
    compactEnabled: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }
    val fraction by animateFloatAsState(contextFraction(used, total), label = "contextFill")
    val level = contextLevel(used, total)
    val track = MaterialTheme.colorScheme.outlineVariant
    val accent = LocalAppStyle.current.brush
    val percent = (contextFraction(used, total) * 100).roundToInt()

    Box(modifier = modifier) {
        IconButton(
            onClick = { open = true },
            modifier = Modifier.size(40.dp).semantics { contentDescription = "Context $percent% full" },
        ) {
            Canvas(modifier = Modifier.size(20.dp)) {
                val stroke = 2.5.dp.toPx()
                val inset = stroke / 2
                val arcSize = Size(size.width - stroke, size.height - stroke)
                val topLeft = Offset(inset, inset)
                drawArc(track, 0f, 360f, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(stroke))
                if (fraction > 0f) {
                    val sweep = 360f * fraction
                    val style = Stroke(stroke, cap = StrokeCap.Round)
                    when (level) {
                        ContextLevel.NORMAL ->
                            drawArc(accent, -90f, sweep, useCenter = false, topLeft = topLeft, size = arcSize, style = style)
                        ContextLevel.WARNING ->
                            drawArc(Accent.Warning, -90f, sweep, useCenter = false, topLeft = topLeft, size = arcSize, style = style)
                        ContextLevel.CRITICAL ->
                            drawArc(Accent.Critical, -90f, sweep, useCenter = false, topLeft = topLeft, size = arcSize, style = style)
                    }
                }
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Column(modifier = Modifier.widthIn(min = 200.dp, max = 280.dp).padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(
                    "${formatTokenCount(used)} / ${formatTokenCount(total)} tokens used",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    when (level) {
                        ContextLevel.NORMAL -> "$percent% of the context window."
                        ContextLevel.WARNING -> "$percent% full. Older messages will soon be summarized."
                        ContextLevel.CRITICAL -> "$percent% full. Compact now to keep the conversation going."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                FilledTonalButton(
                    onClick = { open = false; onCompact() },
                    enabled = compactEnabled,
                    modifier = Modifier.padding(top = 12.dp).align(Alignment.End),
                ) {
                    Text("Compact now")
                }
            }
        }
    }
}
