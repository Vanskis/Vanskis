package com.vanskis.liquidpocket.media

/** Turns a Markdown reply into plain text for text-to-speech, so symbols are not read out. */
object SpeechText {
    private val fence = Regex("^\\s*(```|~~~)")
    private val rule = Regex("^\\s*([-*_]\\s*){3,}$")
    private val tableDivider = Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$")
    private val heading = Regex("^\\s{0,3}#{1,6}\\s+")
    private val quote = Regex("^\\s*(>\\s?)+")
    private val bullet = Regex("^\\s*[-*+]\\s+(\\[[ xX]]\\s+)?")
    private val numbered = Regex("^\\s*\\d+[.)]\\s+")
    private val image = Regex("!\\[([^]]*)]\\([^)]*\\)")
    private val link = Regex("\\[([^]]+)]\\([^)]*\\)")
    private val inlineCode = Regex("`([^`]*)`")
    private val emphasis = Regex("(\\*\\*|\\*|~~)(?=\\S)(.+?)(?<=\\S)\\1")
    // Underscores only at word edges, so snake_case stays as it is.
    private val underscores = Regex("(?<!\\w)(__|_)(?=\\S)(.+?)(?<=\\S)\\1(?!\\w)")
    private val html = Regex("<[^>]+>")
    private val spaces = Regex("[ \\t]+")

    fun from(markdown: String): String {
        val lines = mutableListOf<String>()
        var inCode = false
        for (raw in markdown.lines()) {
            if (fence.containsMatchIn(raw)) {
                inCode = !inCode
                continue
            }
            // Code is not worth listening to; the reply on screen still has it.
            if (inCode || rule.matches(raw) || tableDivider.matches(raw)) continue
            var line = raw
                .replace(heading, "")
                .replace(quote, "")
                .replace(bullet, "")
                .replace(numbered, "")
                .replace(image, "$1")
                .replace(link, "$1")
                .replace(inlineCode, "$1")
                .replace(html, "")
            // Nested emphasis (***bold italic***) needs more than one pass.
            repeat(3) { line = line.replace(emphasis, "$2").replace(underscores, "$2") }
            if ('|' in line) line = line.split('|').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ")
            line = line.replace(spaces, " ").trim()
            lines += line
        }
        return lines.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
    }
}
