package com.vanskis.liquidpocket.ui.components

/**
 * Finds the LaTeX formulas in a message: `$$…$$` and `\[…\]` on their own (display), `\(…\)` and
 * `$…$` in a line (inline). Code spans and fenced code blocks are left alone, as is an unclosed
 * formula (a reply still streaming), so it shows as typed until its end arrives.
 *
 * A `$` opens a formula only when a non-space follows it, and closes one only after a non-space
 * and not before a digit, so prices ("$5 and $10") stay text. `\$` is a literal dollar.
 */
object MathText {
    data class Formula(val latex: String, val display: Boolean)

    sealed interface Piece {
        data class Plain(val text: String) : Piece
        data class Math(val formula: Formula, val source: String) : Piece
    }

    fun pieces(text: String): List<Piece> {
        val out = mutableListOf<Piece>()
        val plain = StringBuilder()
        fun flush() {
            if (plain.isNotEmpty()) out += Piece.Plain(plain.toString())
            plain.clear()
        }
        var i = 0
        var lineStart = true
        while (i < text.length) {
            val c = text[i]
            // A fenced code block runs to its closing fence (or the end, while it streams).
            if (lineStart && text.startsWith("```", i)) {
                val close = text.indexOf("\n```", i + 3)
                val end = if (close < 0) text.length else (text.indexOf('\n', close + 4).takeIf { it >= 0 } ?: text.length)
                plain.append(text, i, end)
                i = end
                lineStart = false
                continue
            }
            lineStart = c == '\n'
            when {
                c == '`' -> {
                    var run = 1
                    while (i + run < text.length && text[i + run] == '`') run++
                    val fence = "`".repeat(run)
                    val close = text.indexOf(fence, i + run)
                    val end = if (close < 0) i + run else close + run
                    plain.append(text, i, end)
                    i = end
                }
                c == '\\' && i + 1 < text.length && text[i + 1] == '$' -> {
                    plain.append(text, i, i + 2)
                    i += 2
                }
                c == '\\' && i + 1 < text.length && (text[i + 1] == '[' || text[i + 1] == '(') -> {
                    val display = text[i + 1] == '['
                    val close = text.indexOf(if (display) "\\]" else "\\)", i + 2)
                    val latex = if (close < 0) "" else text.substring(i + 2, close)
                    if (latex.isBlank() || (!display && '\n' in latex)) {
                        plain.append(text, i, i + 2)
                        i += 2
                    } else {
                        flush()
                        out += Piece.Math(Formula(latex.trim(), display), text.substring(i, close + 2))
                        i = close + 2
                    }
                }
                c == '$' && text.startsWith("$$", i) -> {
                    val close = text.indexOf("$$", i + 2)
                    val latex = if (close < 0) "" else text.substring(i + 2, close)
                    if (latex.isBlank()) {
                        plain.append("$$")
                        i += 2
                    } else {
                        flush()
                        out += Piece.Math(Formula(latex.trim(), display = true), text.substring(i, close + 2))
                        i = close + 2
                    }
                }
                c == '$' -> {
                    val close = inlineClose(text, i)
                    if (close < 0) {
                        plain.append(c)
                        i++
                    } else {
                        flush()
                        out += Piece.Math(Formula(text.substring(i + 1, close), display = false), text.substring(i, close + 1))
                        i = close + 1
                    }
                }
                else -> {
                    plain.append(c)
                    i++
                }
            }
        }
        flush()
        return out
    }

    /** True when [text] has a formula, so plain text can skip the math rendering. */
    fun hasMath(text: String): Boolean = ('$' in text || '\\' in text) && pieces(text).any { it is Piece.Math }

    /** The `$` closing the inline formula opened at [open], or -1. */
    private fun inlineClose(text: String, open: Int): Int {
        val first = text.getOrNull(open + 1) ?: return -1
        if (first.isWhitespace() || first == '$') return -1
        var j = open + 1
        while (j < text.length) {
            val c = text[j]
            if (c == '\n') return -1
            if (c == '\\') {
                j += 2
                continue
            }
            if (c == '$' && !text[j - 1].isWhitespace() && text.getOrNull(j + 1)?.isDigit() != true) return j
            j++
        }
        return -1
    }
}
