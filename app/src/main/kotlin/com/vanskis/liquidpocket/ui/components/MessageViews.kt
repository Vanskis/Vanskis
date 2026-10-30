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
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.vanskis.liquidpocket.chat.Attachment
import com.vanskis.liquidpocket.engine.GenerationStats

/** The user's message, right-aligned in a soft bubble under its [attachments]. [onEdit] and [onRetry] show only when given. */
@Composable
fun UserMessage(
    text: String,
    modifier: Modifier = Modifier,
    attachments: List<Attachment> = emptyList(),
    onEdit: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxWidth().padding(start = 48.dp), horizontalAlignment = Alignment.End) {
        if (attachments.isNotEmpty()) {
            MessageAttachments(attachments, modifier = Modifier.padding(bottom = if (text.isEmpty()) 0.dp else 4.dp))
        }
        if (text.isNotEmpty()) Surface(
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 6.dp, bottomEnd = 24.dp, bottomStart = 24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.widthIn(max = 340.dp),
        ) {
            SelectionContainer {
                Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
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
 * The model's reply: the sparkle mark, then full-width Markdown with no bubble, like Gemini. Above
 * it, the model's [thinking] when it gives one (see [ThinkingSection]). While [streaming] with no
 * text yet it shows the typing indicator. Copy shows once the reply is complete; [onRegenerate] and
 * [onThinkHarder] only when given, [stats] ("23 tok/s · 0.4 s to first token") when given.
 * [onCopy] copies the whole reply, [onCopyCode] one code block.
 */
@Composable
fun AssistantMessage(
    text: String,
    streaming: Boolean,
    stats: GenerationStats?,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
    onCopyCode: (String) -> Unit = {},
    onRegenerate: (() -> Unit)? = null,
    onThinkHarder: (() -> Unit)? = null,
    thinkHarderEnabled: Boolean = true,
    thinking: String? = null,
    thinkingSeconds: Int? = null,
) {
    Row(modifier = modifier.fillMaxWidth()) {
        AssistantMark(busy = streaming, modifier = Modifier.padding(top = 2.dp, end = 12.dp))
        Column(modifier = Modifier.weight(1f)) {
            // Still thinking while it streams and no reply text has come yet.
            ThinkingSection(thinking, thinkingSeconds, streaming = streaming && text.isEmpty())
            if (text.isEmpty() && streaming) {
                if (thinking == null) TypingIndicator(modifier = Modifier.padding(vertical = 8.dp))
            } else {
                SelectionContainer {
                    MarkdownReply(text, onCopyCode = onCopyCode, modifier = Modifier.fillMaxWidth())
                }
            }
            if (!streaming) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                    MessageAction(AppIcons.ContentCopy, "Copy", onCopy)
                    onRegenerate?.let { MessageAction(Icons.Filled.Refresh, "Regenerate", it) }
                    onThinkHarder?.let {
                        TextButton(onClick = it, enabled = thinkHarderEnabled, modifier = Modifier.height(36.dp)) {
                            Text("Think harder", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
                stats?.let { StatsLine(it, modifier = Modifier.padding(start = 10.dp)) }
            }
        }
    }
}

/** "23 tok/s · 0.4 s to first token" under the newest reply. */
@Composable
fun StatsLine(stats: GenerationStats, modifier: Modifier = Modifier) {
    Text(
        statsSummary(stats),
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
                    .background(Accent.colors[i], CircleShape),
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
