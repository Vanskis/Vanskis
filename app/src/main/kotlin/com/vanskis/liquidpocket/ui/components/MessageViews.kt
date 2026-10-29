package com.vanskis.liquidpocket.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.rememberMarkdownState
import com.vanskis.liquidpocket.engine.GenerationStats
import java.util.Locale

/** The user's message, right-aligned in a bubble. [onEdit] and [onRetry] show only when given. */
@Composable
fun UserMessage(text: String, modifier: Modifier = Modifier, onEdit: (() -> Unit)? = null, onRetry: (() -> Unit)? = null) {
    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        Surface(
            shape = RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.widthIn(max = 340.dp),
        ) {
            SelectionContainer {
                Text(text, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
            }
        }
        if (onEdit != null || onRetry != null) {
            Row {
                onEdit?.let { MessageAction(Icons.Filled.Edit, "Edit and resend", it) }
                onRetry?.let { MessageAction(Icons.Filled.Refresh, "Retry", it) }
            }
        }
    }
}

/**
 * The model's reply, full width, rendered as Markdown. While [streaming] with no text yet it shows the
 * typing indicator. Copy shows once the reply is complete; [onRegenerate] only when given.
 */
@Composable
fun AssistantMessage(
    text: String,
    streaming: Boolean,
    stats: GenerationStats?,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
    onRegenerate: (() -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxWidth().padding(end = 8.dp)) {
        if (text.isEmpty() && streaming) {
            TypingIndicator(modifier = Modifier.padding(vertical = 12.dp))
        } else {
            // retainState keeps the last rendering on screen while each streamed token is re-parsed.
            val markdown = rememberMarkdownState(text, retainState = true)
            val colors = MaterialTheme.colorScheme
            val type = MaterialTheme.typography
            SelectionContainer {
                Markdown(
                    markdownState = markdown,
                    modifier = Modifier.fillMaxWidth(),
                    colors = markdownColor(
                        text = colors.onSurface,
                        codeBackground = colors.surfaceContainerHighest,
                        inlineCodeBackground = colors.surfaceContainerHigh,
                        dividerColor = colors.outlineVariant,
                        tableBackground = colors.surfaceContainerLow,
                    ),
                    typography = markdownTypography(
                        h1 = type.titleLarge,
                        h2 = type.titleMedium,
                        h3 = type.titleSmall,
                        h4 = type.titleSmall,
                        h5 = type.titleSmall,
                        h6 = type.titleSmall,
                        text = type.bodyLarge,
                        code = type.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        inlineCode = type.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                        paragraph = type.bodyLarge,
                        ordered = type.bodyLarge,
                        bullet = type.bodyLarge,
                        list = type.bodyLarge,
                    ),
                )
            }
        }
        if (!streaming) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MessageAction(AppIcons.ContentCopy, "Copy", onCopy)
                onRegenerate?.let { MessageAction(Icons.Filled.Refresh, "Regenerate", it) }
                stats?.let { StatsLine(it, modifier = Modifier.padding(start = 4.dp)) }
            }
        }
    }
}

/** "24.5 tok/s · 312 tokens" under a reply. */
@Composable
fun StatsLine(stats: GenerationStats, modifier: Modifier = Modifier) {
    Text(
        String.format(Locale.ROOT, "%.1f tok/s · %d tokens", stats.decodeTokensPerSecond, stats.generatedTokens),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/** Three dots that pulse in turn while the model has not produced its first token. */
@Composable
fun TypingIndicator(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "typing")
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(3) { i ->
            val alpha by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 500),
                    repeatMode = RepeatMode.Reverse,
                    initialStartOffset = StartOffset(offsetMillis = i * 160),
                ),
                label = "dot$i",
            )
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .alpha(alpha)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape),
            )
        }
    }
}

@Composable
private fun MessageAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
