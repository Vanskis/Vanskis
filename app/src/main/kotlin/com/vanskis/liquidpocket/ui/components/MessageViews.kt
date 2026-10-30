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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import com.vanskis.liquidpocket.chat.Attachment
import com.vanskis.liquidpocket.engine.GenerationStats
import com.vanskis.liquidpocket.tools.Choices

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
            SelectionContainer { MathAwareText(text, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) }
        }
        if (onEdit != null || onRetry != null) {
            Row {
                onEdit?.let { MessageAction(Icons.Filled.Edit, "Edit and resend", it) }
                onRetry?.let { MessageAction(Icons.Filled.Refresh, "Retry", it) }
            }
        }
    }
}

/** The bubble's text, its LaTeX formulas drawn inline like a reply's ([MarkdownReply]). */
@Composable
private fun MathAwareText(text: String, modifier: Modifier = Modifier) {
    val style = MaterialTheme.typography.bodyLarge
    val pieces = remember(text) { MathText.pieces(text) }
    val math = pieces.filterIsInstance<MathText.Piece.Math>()
    if (math.isEmpty()) {
        Text(text, style = style, modifier = modifier)
        return
    }
    // The bubble is at most 340 dp wide, less its 16 dp padding each side.
    val inline = mathInlineContent(
        math.map { it.formula },
        style.fontSize,
        MaterialTheme.colorScheme.onSurface,
        maxWidthPx = with(LocalDensity.current) { 308.dp.toPx() },
    )
    val annotated = buildAnnotatedString {
        var i = 0
        for (piece in pieces) when (piece) {
            is MathText.Piece.Plain -> append(piece.text)
            is MathText.Piece.Math -> appendMath(i++, piece.source, inline)
        }
    }
    Text(annotated, style = style, inlineContent = inline, modifier = modifier)
}

/**
 * The model's reply: the sparkle mark, then full-width Markdown with no bubble, like Gemini. Above
 * it, the model's [thinking] when it gives one (see [ThinkingSection]). While [streaming] with no
 * text yet it shows the typing indicator. Copy shows once the reply is complete; [onRegenerate] and
 * [onContinue] (a reply cut off by the context or a Stop) only when given, [stats] ("23 tok/s · 0.4 s
 * to first token") when given. [onCopy] copies the whole reply, [onCopyCode] one code block.
 *
 * [offer] (offer_choices) shows its options as chips under the reply: a tap runs [onChoose], and
 * "Type my own…" runs [onTypeOwn]. With [onChoose] null (answered, or a reply running) they are greyed out.
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
    onContinue: (() -> Unit)? = null,
    offer: Choices.Offer? = null,
    onChoose: ((String) -> Unit)? = null,
    onTypeOwn: () -> Unit = {},
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
            } else if (text.isNotEmpty()) {
                SelectionContainer {
                    MarkdownReply(text, onCopyCode = onCopyCode, modifier = Modifier.fillMaxWidth())
                }
            }
            offer?.let { ChoiceChips(it, showQuestion = it.question !in text, onChoose = onChoose, onTypeOwn = onTypeOwn) }
            if (!streaming) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                    if (text.isNotEmpty()) MessageAction(AppIcons.ContentCopy, "Copy", onCopy)
                    onRegenerate?.let { MessageAction(Icons.Filled.Refresh, "Regenerate", it) }
                    onContinue?.let {
                        TextButton(onClick = it, modifier = Modifier.height(36.dp)) {
                            Text("Continue", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
                stats?.let { StatsLine(it, modifier = Modifier.padding(start = 10.dp)) }
            }
        }
    }
}

/** The offer's [Choices.Offer.question] (unless the reply already says it), then a chip per option and "Type my own…". */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChoiceChips(offer: Choices.Offer, showQuestion: Boolean, onChoose: ((String) -> Unit)?, onTypeOwn: () -> Unit) {
    Column(modifier = Modifier.padding(top = 8.dp)) {
        if (showQuestion) Text(offer.question, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            offer.options.forEach { option ->
                AssistChip(onClick = { onChoose?.invoke(option) }, enabled = onChoose != null, label = { Text(option) })
            }
            AssistChip(
                onClick = onTypeOwn,
                enabled = onChoose != null,
                label = { Text("Type my own…") },
                leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(16.dp)) },
            )
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
