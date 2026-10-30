package com.vanskis.liquidpocket.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.annotator.annotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownParagraph
import com.mikepenz.markdown.compose.elements.MarkdownText
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownAnimations
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.model.markdownInlineContent
import com.mikepenz.markdown.model.rememberMarkdownState
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.getTextInNode

/** A streaming reply's [FadeTimeline], and the frame clock that redraws its fading pieces. */
private class TextFade {
    val timeline = FadeTimeline()
    val frame = mutableLongStateOf(0L)
}

/**
 * A paragraph as [MarkdownParagraph] draws it, with the pieces that just arrived faded: each is
 * cut out of the drawn text in proportion to how new it is, so it shows on any background.
 */
@Composable
private fun FadingParagraph(model: MarkdownComponentModel, fade: TextFade) {
    val style = model.typography.paragraph
    val settings = annotatorSettings()
    val styled = remember(model.content, model.node, style, settings) {
        buildAnnotatedString {
            pushStyle(style.toSpanStyle())
            buildMarkdownAnnotatedString(model.content, model.node, settings)
            pop()
        }
    }
    // The paragraph starts at the same place in the reply however much of it has streamed.
    val key = model.node.startOffset
    SideEffect { fade.timeline.saw(key, styled.length, System.nanoTime()) }
    val layout = remember { arrayOfNulls<TextLayoutResult>(1) }
    MarkdownText(
        styled,
        modifier = Modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                fade.frame.longValue // Redraw every frame while the reply streams.
                val text = layout[0] ?: return@drawWithContent
                val length = text.layoutInput.text.length
                for (piece in fade.timeline.fading(key, System.nanoTime())) {
                    val end = minOf(piece.end, length)
                    if (piece.start >= end) continue
                    drawPath(text.getPathForRange(piece.start, end), Color.Black, alpha = 1f - piece.alpha, blendMode = BlendMode.DstOut)
                }
            },
        style = style,
        onTextLayout = { result, _ -> layout[0] = result },
    )
}

/** Where formula <index> goes in the Markdown: an inline code span the annotator swaps for the formula. */
private fun placeholder(index: Int) = "`\uE000$index\uE001`"
private val PLACEHOLDER = Regex("\uE000(\\d+)\uE001")

/** [text] with each formula swapped for its [placeholder]; a display formula gets a paragraph of its own. */
private fun withPlaceholders(pieces: List<MathText.Piece>): String = buildString {
    var i = 0
    for (piece in pieces) when (piece) {
        is MathText.Piece.Plain -> append(piece.text)
        is MathText.Piece.Math -> if (piece.formula.display) append("\n\n").append(placeholder(i++)).append("\n\n") else append(placeholder(i++))
    }
}

/**
 * A reply rendered as Markdown: headings, lists, emphasis, links, tables, inline code and code
 * blocks with their language and a Copy button ([onCopyCode]).
 *
 * Safe while [text] is still streaming: the parser takes any prefix (an unclosed fence is a code
 * block that is still growing), parsing runs off the main thread, and retainState keeps the last
 * rendering on screen until the next one is ready, so the reply does not flash between tokens.
 *
 * LaTeX formulas ([MathText]) are drawn inline by [LocalMathRenderer]; one that does not parse
 * shows as its source in monospace. A formula still streaming (unclosed) stays plain text.
 *
 * While [streaming], each new piece of a paragraph fades in ([FadeTimeline]); a finished reply shows at once.
 */
@Composable
fun MarkdownReply(text: String, onCopyCode: (String) -> Unit, modifier: Modifier = Modifier, streaming: Boolean = false) = BoxWithConstraints(modifier) {
    val pieces = remember(text) { MathText.pieces(text) }
    val math = remember(pieces) { pieces.filterIsInstance<MathText.Piece.Math>() }
    val markdown = rememberMarkdownState(if (math.isEmpty()) text else withPlaceholders(pieces), retainState = true)
    val colors = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    val copy by rememberUpdatedState(onCopyCode)
    val fade = remember(streaming) { if (streaming) TextFade() else null }
    if (fade != null) {
        LaunchedEffect(fade) {
            while (true) withFrameNanos { fade.frame.longValue = it }
        }
    }
    val components = remember(fade) {
        markdownComponents(
            paragraph = { model ->
                if (fade == null) {
                    MarkdownParagraph(model.content, model.node, style = model.typography.paragraph)
                } else {
                    FadingParagraph(model, fade)
                }
            },
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
    val inline = mathInlineContent(
        math.map { it.formula },
        type.bodyLarge.fontSize,
        colors.onSurface,
        maxWidthPx = with(LocalDensity.current) { maxWidth.toPx() },
    )
    val annotator = remember(math, inline.keys) {
        markdownAnnotator { content, node ->
            if (node.type != MarkdownElementTypes.CODE_SPAN) return@markdownAnnotator false
            val code = node.getTextInNode(content).toString().trim('`')
            val index = PLACEHOLDER.matchEntire(code)?.groupValues?.get(1)?.toIntOrNull() ?: return@markdownAnnotator false
            val formula = math.getOrNull(index) ?: return@markdownAnnotator false
            appendMath(index, formula.source, inline)
            true
        }
    }
    Markdown(
        markdownState = markdown,
        modifier = Modifier.fillMaxWidth(),
        annotator = annotator,
        inlineContent = markdownInlineContent(inline),
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
