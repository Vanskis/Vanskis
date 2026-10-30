package com.vanskis.liquidpocket.docs

/** A passage of a document: its position among all chunks, the 1-based [page] it is on, and its text. */
data class Chunk(val index: Int, val page: Int, val text: String)

/**
 * Splits pages into passages of about [SIZE] characters for retrieval. A chunk never spans pages, so
 * its page number is exact. Chunks end on a sentence or line break where one fits (a single sentence
 * longer than a chunk is cut between words), and each chunk after the first on a page starts with up
 * to [OVERLAP] characters from the end of the previous one, from a sentence or word start.
 */
object Chunker {
    const val SIZE = 800
    const val OVERLAP = 100

    /** The end of a sentence (with closing quotes or brackets) and the space after it, or a line break. */
    private val boundary = Regex("""[.!?…]["'”’)\]]*\s+|\s*\n\s*""")
    private val space = Regex("""\s""")

    fun split(pages: List<String>, size: Int = SIZE, overlap: Int = OVERLAP): List<Chunk> {
        require(overlap in 0 until size) { "overlap must be smaller than size" }
        val chunks = ArrayList<Chunk>()
        pages.forEachIndexed { i, page ->
            for (text in splitPage(page.replace("\r\n", "\n"), size, overlap)) {
                chunks += Chunk(chunks.size, i + 1, text)
            }
        }
        return chunks
    }

    private fun splitPage(page: String, size: Int, overlap: Int): List<String> {
        val out = ArrayList<String>()
        val current = StringBuilder()
        var fresh = false // whether [current] holds text not already emitted
        for (segment in segments(page, size)) {
            if (fresh && current.length + segment.length > size) {
                out += current.toString().trim()
                val tail = tail(current, overlap)
                current.setLength(0)
                if (tail.length + segment.length <= size) current.append(tail)
            }
            current.append(segment)
            fresh = fresh || segment.isNotBlank()
        }
        if (fresh) out += current.toString().trim()
        return out.filter { it.isNotEmpty() }
    }

    /** [text] cut after each sentence end and line break, with pieces longer than [size] cut between words. */
    private fun segments(text: String, size: Int): List<String> {
        val pieces = ArrayList<String>()
        var start = 0
        for (m in boundary.findAll(text)) {
            pieces += text.substring(start, m.range.last + 1)
            start = m.range.last + 1
        }
        if (start < text.length) pieces += text.substring(start)
        return pieces.flatMap { cutLong(it, size) }
    }

    private fun cutLong(piece: String, size: Int): List<String> {
        if (piece.length <= size) return listOf(piece)
        val out = ArrayList<String>()
        var rest = piece
        while (rest.length > size) {
            val cut = rest.lastIndexOfAny(charArrayOf(' ', '\t', '\n'), size - 1).let { if (it <= 0) size else it + 1 }
            out += rest.substring(0, cut)
            rest = rest.substring(cut)
        }
        if (rest.isNotEmpty()) out += rest
        return out
    }

    /** The last [overlap] characters of [text] or fewer, starting at a sentence start, else at a word start. */
    private fun tail(text: CharSequence, overlap: Int): String {
        if (overlap == 0) return ""
        val window = text.substring(maxOf(0, text.length - overlap))
        if (text.length <= overlap) return window
        val start = boundary.find(window)?.let { it.range.last + 1 }
            ?: space.find(window)?.let { it.range.last + 1 }
            ?: return ""
        return window.substring(start).takeIf { it.isNotBlank() } ?: ""
    }
}
