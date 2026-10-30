package com.vanskis.liquidpocket.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownAnimations
import com.mikepenz.markdown.model.rememberMarkdownState

/**
 * A reply rendered as Markdown: headings, lists, emphasis, links, tables, inline code and code
 * blocks with their language and a Copy button ([onCopyCode]).
 *
 * Safe while [text] is still streaming: the parser takes any prefix (an unclosed fence is a code
 * block that is still growing), parsing runs off the main thread, and retainState keeps the last
 * rendering on screen until the next one is ready, so the reply does not flash between tokens.
 */
@Composable
fun MarkdownReply(text: String, onCopyCode: (String) -> Unit, modifier: Modifier = Modifier) {
    val markdown = rememberMarkdownState(text, retainState = true)
    val colors = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    val copy by rememberUpdatedState(onCopyCode)
    val components = remember {
        markdownComponents(
            codeFence = { model ->
                MarkdownCodeFence(model.content, model.node, model.typography.code) { code, language, style ->
                    CodeBlock(code, language, style, onCopy = { copy(code) })
                }
            },
            codeBlock = { model ->
                MarkdownCodeBlock(model.content, model.node, model.typography.code) { code, language, style ->
                    CodeBlock(code, language, style, onCopy = { copy(code) })
                }
            },
        )
    }
    Markdown(
        markdownState = markdown,
        modifier = modifier,
        colors = markdownColor(
            text = colors.onSurface,
            codeBackground = colors.surfaceContainerHighest,
            inlineCodeBackground = colors.surfaceContainerHigh,
            dividerColor = colors.outlineVariant,
            tableBackground = colors.surfaceContainerLow,
        ),
        typography = markdownTypography(
            h1 = type.headlineSmall,
            h2 = type.titleLarge,
            h3 = type.titleMedium,
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
        components = components,
        // No size animation: a reply grows every token, and animating each step makes it wobble.
        animations = markdownAnimations(animateTextSize = { this }),
    )
}

/** A fenced or indented code block: its language and Copy above, the code scrolling sideways below. */
@Composable
fun CodeBlock(code: String, language: String?, style: TextStyle, onCopy: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = colors.surfaceContainerHighest,
        contentColor = colors.onSurface,
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 2.dp),
            ) {
                Text(
                    language?.takeIf { it.isNotBlank() } ?: "code",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onCopy, modifier = Modifier.height(36.dp)) {
                    Icon(AppIcons.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text("Copy", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 6.dp))
                }
            }
            Text(
                code.trimEnd('\n'),
                style = style,
                softWrap = false,
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, bottom = 14.dp, top = 2.dp),
            )
        }
    }
}
