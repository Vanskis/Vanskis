package com.vanskis.liquidpocket.docs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkerTest {
    /** Numbered sentences, about 50 characters each. */
    private fun sentences(from: Int, count: Int) =
        (from until from + count).joinToString(" ") { "Sentence number $it talks about item $it at length." }

    @Test fun `chunks stay within the size, end on sentences and overlap the previous chunk`() {
        val chunks = Chunker.split(listOf(sentences(1, 60)))
        assertTrue(chunks.size > 3)
        chunks.forEach { assertTrue("too long: ${it.text.length}", it.text.length <= Chunker.SIZE) }
        chunks.forEach { assertTrue("not on a sentence end: ${it.text.takeLast(20)}", it.text.endsWith(".")) }
        chunks.zipWithNext { a, b ->
            // b starts with a's last sentence (within the overlap), not with a cut word.
            val shared = (1..minOf(a.text.length, b.text.length)).last { n -> b.text.startsWith(a.text.takeLast(n)) }
            assertTrue("overlap $shared", shared in 1..Chunker.OVERLAP)
            assertTrue(b.text.startsWith("Sentence number"))
        }
        // Every sentence is somewhere.
        (1..60).forEach { n -> assertTrue(chunks.any { "item $n at" in it.text }) }
    }

    @Test fun `chunks keep their page, never span pages, and empty pages give none`() {
        val pages = listOf("Short first page.", "   \n ", sentences(1, 40), "Last page.")
        val chunks = Chunker.split(pages)
        assertEquals(1, chunks.first().page)
        assertEquals("Short first page.", chunks.first().text)
        assertTrue(chunks.none { it.page == 2 })
        assertTrue(chunks.filter { it.page == 3 }.size >= 2)
        assertEquals(Chunk(chunks.size - 1, 4, "Last page."), chunks.last())
        assertEquals(chunks.indices.toList(), chunks.map { it.index })
    }

    @Test fun `a sentence longer than a chunk is cut between words`() {
        val words = (1..300).joinToString(" ") { "word$it" }
        val chunks = Chunker.split(listOf(words))
        assertTrue(chunks.size >= 3)
        chunks.forEach { c ->
            assertTrue(c.text.length <= Chunker.SIZE)
            c.text.split(" ").forEach { assertTrue("cut word: $it", Regex("word\\d+").matches(it)) }
        }
        assertTrue(chunks.last().text.endsWith("word300"))
    }

    @Test fun `line breaks are boundaries, so rows of a table stay whole`() {
        val csv = (1..100).joinToString("\n") { "row$it,alpha,beta,gamma,$it" }
        val chunks = Chunker.split(listOf(csv))
        chunks.forEach { c -> c.text.lines().forEach { assertTrue("cut row: $it", Regex("row\\d+,alpha,beta,gamma,\\d+").matches(it)) } }
    }
}
