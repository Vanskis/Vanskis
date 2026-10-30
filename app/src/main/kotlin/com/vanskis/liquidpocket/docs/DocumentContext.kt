package com.vanskis.liquidpocket.docs

/**
 * The part of an attached document to put in the prompt for one question: all of it when it fits the
 * budget, else the passages BM25 ranks highest for the question, shown in page order.
 */
object DocumentContext {
    /** Default prompt budget for one document's text, in characters. */
    const val BUDGET_CHARS = 6_000

    private const val GAP = "\n\n…\n\n"

    /**
     * The text block for [question], at most [budgetChars] long, headed with where it comes from:
     * `From report.pdf (pages 3, 7):` followed by the passages. When no word of the question occurs in
     * the document (a "summarize this"), the passages are the document's beginning.
     */
    fun forQuestion(doc: ExtractedDocument, question: String, budgetChars: Int = BUDGET_CHARS): String {
        wholeIfFits(doc, budgetChars)?.let { return it }
        val chunks = Chunker.split(doc.pages)
        val ranked = Bm25Index.build(chunks).search(question, k = chunks.size).map { it.chunk }
        val candidates = ranked.ifEmpty { chunks }
        val chosen = ArrayList<Chunk>()
        for (chunk in candidates) {
            val trial = (chosen + chunk).sortedBy { it.index }
            if (render(doc, trial).length <= budgetChars) {
                chosen += chunk
            } else if (ranked.isEmpty()) {
                break // the beginning of the document: stop at the first passage that does not fit
            }
        }
        return render(doc, chosen.sortedBy { it.index })
    }

    /** The whole document under a `From <name> (full text):` header, or null when that exceeds [budgetChars]. */
    fun wholeIfFits(doc: ExtractedDocument, budgetChars: Int = BUDGET_CHARS): String? {
        val header = "From ${doc.name} (full text):\n"
        if (doc.charCount > budgetChars * 2) return null // clearly too long, skip trimming every page
        val parts = doc.pages.map { it.trim() }.filter { it.isNotEmpty() }
        val length = header.length + parts.sumOf { it.length } + 2 * maxOf(0, parts.size - 1)
        return if (length <= budgetChars) header + parts.joinToString("\n\n") else null
    }

    private fun render(doc: ExtractedDocument, chunks: List<Chunk>): String {
        val pages = chunks.map { it.page }.distinct()
        val where = when {
            !doc.paged || pages.isEmpty() -> ""
            pages.size == 1 -> " (page ${pages[0]})"
            else -> " (pages ${pages.joinToString(", ")})"
        }
        return "From ${doc.name}$where:\n" + chunks.joinToString(GAP) { it.text }
    }
}
