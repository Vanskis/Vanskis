package com.vanskis.liquidpocket.docs

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Reads the text in a page image. The bitmap stays owned by the caller and valid until this returns. */
fun interface PageOcr {
    suspend fun recognize(bitmap: Bitmap): String
}

/** Extraction progress: [done] of [total] pages through [stage]. */
data class ExtractionProgress(val stage: Stage, val done: Int, val total: Int) {
    enum class Stage { TEXT, OCR }
}

/** A document that cannot be read: not a PDF or text, password-protected, or unreadable. [message] is for the user. */
class UnsupportedDocumentException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Turns an attached or shared document into [ExtractedDocument]. Text files are read as UTF-8 (the
 * first [DocumentFormat.MAX_TEXT_BYTES]). PDFs give their text layer page by page through PdfBox; pages
 * with (almost) no text are rendered with [PdfRenderer] and read by [ocr], at most [MAX_OCR_PAGES] of
 * them. Runs on [Dispatchers.IO] (parsing) and [Dispatchers.Default] (rendering) and stops between
 * pages when cancelled. [onProgress] is called on those worker threads.
 */
class DocumentText(context: Context, private val ocr: PageOcr) {
    private val app = context.applicationContext

    /** Reads a content (or file) [uri] from the picker or a share intent. [mimeType] overrides what the provider reports. */
    suspend fun extract(
        uri: Uri,
        mimeType: String? = null,
        onProgress: (ExtractionProgress) -> Unit = {},
    ): ExtractedDocument = withContext(Dispatchers.IO) {
        val name = displayName(uri)
        val format = DocumentFormat.detect(mimeType ?: app.contentResolver.getType(uri), name)
            ?: throw UnsupportedDocumentException("Only PDF and text files can be attached")
        when (format.kind) {
            DocumentFormat.Kind.TEXT -> {
                val text = open(uri).use { DocumentFormat.readUtf8(it) }
                ExtractedDocument(name, format.mimeType, listOf(text))
            }
            DocumentFormat.Kind.PDF -> {
                // PdfRenderer needs a seekable file, which a content stream is not always: copy it first.
                val copy = File(app.cacheDir, "doc-${UUID.randomUUID()}.pdf")
                try {
                    open(uri).use { src -> copy.outputStream().use { src.copyTo(it) } }
                    readPdf(copy, name, onProgress)
                } finally {
                    copy.delete()
                }
            }
        }
    }

    /** Reads a [file] already on the device; [name] is what the user sees, the file's name by default. */
    suspend fun extract(
        file: File,
        mimeType: String? = null,
        name: String = file.name,
        onProgress: (ExtractionProgress) -> Unit = {},
    ): ExtractedDocument = withContext(Dispatchers.IO) {
        val format = DocumentFormat.detect(mimeType, name)
            ?: throw UnsupportedDocumentException("Only PDF and text files can be attached")
        when (format.kind) {
            DocumentFormat.Kind.TEXT -> ExtractedDocument(name, format.mimeType, listOf(file.inputStream().use { DocumentFormat.readUtf8(it) }))
            DocumentFormat.Kind.PDF -> readPdf(file, name, onProgress)
        }
    }

    private suspend fun readPdf(file: File, name: String, onProgress: (ExtractionProgress) -> Unit): ExtractedDocument {
        val pages = textLayer(file, onProgress).toMutableList()
        val scanned = pages.indices.filter { DocumentFormat.needsOcr(pages[it]) }.take(MAX_OCR_PAGES)
        var ocrPages = 0
        if (scanned.isNotEmpty()) {
            withContext(Dispatchers.Default) {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    PdfRenderer(fd).use { renderer ->
                        scanned.forEachIndexed { done, i ->
                            onProgress(ExtractionProgress(ExtractionProgress.Stage.OCR, done, scanned.size))
                            coroutineContext.ensureActive()
                            val text = ocrPage(renderer, i)
                            // Keep the text layer when OCR finds nothing better (a blank page, a figure).
                            if (text.count { it.isLetterOrDigit() } > pages[i].count { it.isLetterOrDigit() }) {
                                pages[i] = text
                                ocrPages++
                            }
                        }
                        onProgress(ExtractionProgress(ExtractionProgress.Stage.OCR, scanned.size, scanned.size))
                    }
                }
            }
        }
        return ExtractedDocument(name, DocumentFormat.PDF_MIME, pages, ocrPages)
    }

    /** Each page's text layer, at most [MAX_PAGES] pages. */
    private suspend fun textLayer(file: File, onProgress: (ExtractionProgress) -> Unit): List<String> {
        PDFBoxResourceLoader.init(app)
        val document = try {
            PDDocument.load(file)
        } catch (e: InvalidPasswordException) {
            throw UnsupportedDocumentException("This PDF is password-protected", e)
        } catch (e: IOException) {
            throw UnsupportedDocumentException("This PDF could not be read", e)
        }
        return document.use { pdf ->
            val count = minOf(pdf.numberOfPages, MAX_PAGES)
            val stripper = PDFTextStripper()
            (1..count).map { page ->
                coroutineContext.ensureActive()
                onProgress(ExtractionProgress(ExtractionProgress.Stage.TEXT, page - 1, count))
                stripper.startPage = page
                stripper.endPage = page
                stripper.getText(pdf).trim()
            }.also { onProgress(ExtractionProgress(ExtractionProgress.Stage.TEXT, count, count)) }
        }
    }

    /** Renders page [index] at about 200 dpi on white and reads it. */
    private suspend fun ocrPage(renderer: PdfRenderer, index: Int): String {
        val bitmap = renderer.openPage(index).use { page ->
            val (w, h) = DocumentFormat.renderSize(page.width, page.height)
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                it.eraseColor(Color.WHITE) // the renderer leaves the background transparent
                page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
        }
        try {
            return ocr.recognize(bitmap).trim()
        } finally {
            bitmap.recycle()
        }
    }

    private fun open(uri: Uri) = app.contentResolver.openInputStream(uri) ?: throw IOException("Could not open the document")

    private fun displayName(uri: Uri): String {
        val fromProvider = runCatching {
            app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
            }
        }.getOrNull()
        return fromProvider?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "document"
    }

    companion object {
        /** PDF pages read at all; later pages are dropped. */
        const val MAX_PAGES = 500

        /** Scanned pages read with OCR; later scanned pages stay empty. */
        const val MAX_OCR_PAGES = 30
    }
}
