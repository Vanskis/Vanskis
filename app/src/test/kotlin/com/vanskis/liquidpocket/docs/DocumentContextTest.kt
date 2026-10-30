package com.vanskis.liquidpocket.docs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentContextTest {
    /** A page of filler about one topic, about 1,000 characters. */
    private fun filler(page: Int) = (1..20).joinToString(" ") { "Page $page paragraph $it repeats general background." }

    private fun pdf(pages: List<String>) = ExtractedDocument("report.pdf", DocumentFormat.PDF_MIME, pages)

    @Test fun `a document within the budget is given whole`() {
        val doc = pdf(listOf("First page text.", "", "Third page text."))
        val expected = "From report.pdf (full text):\nFirst page text.\n\nThird page text."
        assertEquals(expected, DocumentContext.wholeIfFits(doc))
        assertEquals(expected, DocumentContext.forQuestion(doc, "anything"))
        assertNull(DocumentContext.wholeIfFits(doc, budgetChars = 40))
    }

    @Test fun `the best passages come in page order, within the budget, with their pages`() {
        val pages = (1..10).map { filler(it) }.toMutableList()
        pages[2] = "The engine oil must be changed every 15000 km. " + filler(3)
        pages[6] = "Engine oil capacity is 4.5 litres; use engine oil 5W-30. " + filler(7)
        val context = DocumentContext.forQuestion(pdf(pages), "How much engine oil?", budgetChars = 2_000)
        assertTrue(context.length <= 2_000)
        assertTrue(context, context.startsWith("From report.pdf (pages 3, 7):\n"))
        val page3 = context.indexOf("changed every 15000 km")
        val page7 = context.indexOf("capacity is 4.5 litres")
        // Page 7 scores higher (more mentions) but page 3 comes first.
        assertTrue(page3 in 0 until page7)
    }

    @Test fun `with no matching word the beginning of the document is given`() {
        val doc = pdf((1..10).map { filler(it) })
        val context = DocumentContext.forQuestion(doc, "Summarize this", budgetChars = 1_500)
        assertTrue(context.length <= 1_500)
        assertTrue(context, context.startsWith("From report.pdf (page 1):\nPage 1 paragraph 1 "))
    }

    @Test fun `a text file has no page numbers`() {
        val text = (1..10).joinToString("\n\n") { filler(it) } + "\n\nThe wifi password is hunter2."
        val doc = ExtractedDocument("notes.txt", "text/plain", listOf(text))
        val context = DocumentContext.forQuestion(doc, "wifi password", budgetChars = 1_000)
        assertTrue(context, context.startsWith("From notes.txt:\n"))
        assertTrue("hunter2" in context)
        assertFalse("page" in context.lines().first())
    }
}
