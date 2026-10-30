package com.vanskis.liquidpocket.docs

/**
 * The text of an attached document. [pages] holds one entry per PDF page (1-based page `n` is
 * `pages[n - 1]`); a text file is a single page. [ocrPages] counts the pages whose text came from OCR.
 */
data class ExtractedDocument(
    val name: String,
    val mimeType: String,
    val pages: List<String>,
    val ocrPages: Int = 0,
) {
    /** Whether page numbers mean anything to the reader (a PDF, not a text file). */
    val paged: Boolean get() = mimeType == DocumentFormat.PDF_MIME

    val charCount: Int get() = pages.sumOf { it.length }
}
