package com.vanskis.liquidpocket.docs

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlin.math.max
import kotlin.math.roundToInt

/** Which documents can be read and how, plus the pure parts of extraction. */
object DocumentFormat {
    const val PDF_MIME = "application/pdf"

    /** Text files are read up to this many bytes; the rest is dropped. */
    const val MAX_TEXT_BYTES = 5 * 1024 * 1024

    /** A PDF page with fewer letters and digits than this in its text layer is treated as scanned. */
    const val MIN_PAGE_TEXT = 16

    enum class Kind { TEXT, PDF }

    /** A readable document's kind and the MIME type to record for it. */
    data class Format(val kind: Kind, val mimeType: String)

    private val textExtensions = mapOf(
        "txt" to "text/plain",
        "text" to "text/plain",
        "log" to "text/plain",
        "md" to "text/markdown",
        "markdown" to "text/markdown",
        "csv" to "text/csv",
        "tsv" to "text/tab-separated-values",
        "json" to "application/json",
    )

    /**
     * The format of a document from its reported [mimeType] and file [name], or null when it is not
     * a PDF or text. The name decides when the type is missing or generic (`application/octet-stream`).
     */
    fun detect(mimeType: String?, name: String): Format? {
        val mime = mimeType?.substringBefore(';')?.trim()?.lowercase()
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            mime == PDF_MIME || ext == "pdf" -> Format(Kind.PDF, PDF_MIME)
            mime != null && mime.startsWith("text/") -> Format(Kind.TEXT, mime)
            mime == "application/json" -> Format(Kind.TEXT, mime)
            ext in textExtensions -> Format(Kind.TEXT, textExtensions.getValue(ext))
            else -> null
        }
    }

    /**
     * Reads [input] as UTF-8, at most [maxBytes] of it. Malformed bytes (and a character cut in two at
     * the limit) become nothing rather than U+FFFD; a leading byte-order mark is dropped. Blocks.
     */
    fun readUtf8(input: InputStream, maxBytes: Int = MAX_TEXT_BYTES): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (out.size() < maxBytes) {
            val n = input.read(buf, 0, minOf(buf.size, maxBytes - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        val text = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.IGNORE)
            .onUnmappableCharacter(CodingErrorAction.IGNORE)
            .decode(ByteBuffer.wrap(out.toByteArray()))
            .toString()
        return text.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n')
    }

    /** Whether a PDF page's text layer is (almost) empty, so the page is probably a scan. */
    fun needsOcr(pageText: String): Boolean = pageText.count { it.isLetterOrDigit() } < MIN_PAGE_TEXT

    /**
     * Bitmap size for rendering a PDF page of [widthPt] x [heightPt] points (1/72 inch) at [dpi],
     * scaled down so the long side is at most [maxSide] pixels.
     */
    fun renderSize(widthPt: Int, heightPt: Int, dpi: Int = 200, maxSide: Int = 2400): Pair<Int, Int> {
        val scale = minOf(dpi / 72.0, maxSide.toDouble() / max(1, max(widthPt, heightPt)))
        return max(1, (widthPt * scale).roundToInt()) to max(1, (heightPt * scale).roundToInt())
    }
}
