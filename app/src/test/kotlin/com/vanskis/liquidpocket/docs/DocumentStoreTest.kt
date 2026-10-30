package com.vanskis.liquidpocket.docs

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentStoreTest {
    private val root: File = Files.createTempDirectory("docs-test").toFile()
    private val dir = File(root, "docs")

    @After fun cleanUp() {
        root.deleteRecursively()
    }

    @Test fun `a saved document loads back the same and can be deleted`() {
        val store = DocumentStore(dir)
        val doc = ExtractedDocument(
            "Rapport \"final\" é.pdf",
            DocumentFormat.PDF_MIME,
            listOf("Page one\nwith a line break", "", "Ünïcödé, quotes \" and \\ backslash \t tab"),
            ocrPages = 2,
        )
        val id = store.save(doc)
        assertEquals(doc, DocumentStore(dir).load(id))
        assertEquals(listOf("$id.json"), dir.list()!!.toList())
        store.delete(id)
        assertNull(store.load(id))
        assertFalse(File(dir, "$id.json").exists())
    }

    @Test fun `unknown and non-uuid ids load nothing and delete nothing`() {
        val store = DocumentStore(dir)
        assertNull(store.load("0b6f0f4e-2a4b-4c4e-9a59-6c0e3f9d8f11"))
        val outside = File(root, "secret.json").apply { writeText("{}") }
        assertNull(store.load("../secret"))
        store.delete("../secret")
        assertTrue(outside.exists())
    }

    @Test fun `format detection uses the type, then the name`() {
        assertEquals(DocumentFormat.Format(DocumentFormat.Kind.PDF, "application/pdf"), DocumentFormat.detect("application/pdf", "x"))
        assertEquals(DocumentFormat.Kind.PDF, DocumentFormat.detect("application/octet-stream", "Scan.PDF")?.kind)
        assertEquals(DocumentFormat.Format(DocumentFormat.Kind.TEXT, "text/csv"), DocumentFormat.detect("text/csv; charset=utf-8", "a.csv"))
        assertEquals(DocumentFormat.Format(DocumentFormat.Kind.TEXT, "text/x-log"), DocumentFormat.detect("text/x-log", "a"))
        assertEquals(DocumentFormat.Format(DocumentFormat.Kind.TEXT, "text/markdown"), DocumentFormat.detect(null, "README.md"))
        assertNull(DocumentFormat.detect("image/jpeg", "photo.jpg"))
        assertNull(DocumentFormat.detect(null, "archive.zip"))
    }

    @Test fun `text is read as utf-8, capped, without a byte-order mark or a split character`() {
        val bytes = "﻿line one\r\nnaïve é".toByteArray()
        assertEquals("line one\nnaïve é", DocumentFormat.readUtf8(ByteArrayInputStream(bytes)))
        // The cap falls inside the two bytes of the final "é": that character is dropped, not garbled.
        assertEquals("line one\nnaïve ", DocumentFormat.readUtf8(ByteArrayInputStream(bytes), maxBytes = bytes.size - 1))
    }

    @Test fun `scanned pages and render sizes`() {
        assertTrue(DocumentFormat.needsOcr(""))
        assertTrue(DocumentFormat.needsOcr("  12 \n - 3 -  "))
        assertFalse(DocumentFormat.needsOcr("Invoice number 2026-0042 for services"))
        // A4 at 200 dpi, and a large poster capped at 2400 px on its long side.
        assertEquals(1653 to 2339, DocumentFormat.renderSize(595, 842))
        assertEquals(1697 to 2400, DocumentFormat.renderSize(1684, 2381))
    }
}
