package com.vanskis.liquidpocket.docs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Bm25IndexTest {
    private fun chunks(vararg texts: String) = texts.mapIndexed { i, t -> Chunk(i, i + 1, t) }

    @Test fun `tokens are lowercase letters and digits, without stopwords, plurals made singular`() {
        assertEquals(
            listOf("café", "über", "größe", "42", "battery", "invoice", "class", "status", "7"),
            Tokenizer.tokens("The Café's ÜBER-Größe: 42 batteries, and invoices; class status 7!"),
        )
    }

    @Test fun `the only chunk with the query word ranks first`() {
        val index = Bm25Index.build(
            chunks(
                "The quarterly report covers revenue and costs.",
                "Our warranty lasts two years from the purchase date.",
                "Shipping takes five working days.",
            ),
        )
        val hits = index.search("How long is the warranty?")
        assertEquals(1, hits.size)
        assertEquals(1, hits[0].chunk.index)
        assertTrue(hits[0].score > 0)
    }

    @Test fun `a rare word outweighs a common one`() {
        val index = Bm25Index.build(
            chunks(
                "alpha alpha gamma delta",
                "alpha beta gamma delta",
                "alpha gamma delta epsilon",
                "alpha gamma delta zeta",
            ),
        )
        val hits = index.search("alpha beta", k = 4)
        assertEquals(1, hits[0].chunk.index)
        assertEquals(4, hits.size)
        assertTrue(index.search("beta")[0].score > index.search("alpha")[0].score)
    }

    @Test fun `a shorter chunk ranks above a longer one with the same count`() {
        val index = Bm25Index.build(
            chunks("budget plan with many other words here to make it longer than the next", "budget plan", "unrelated"),
        )
        assertEquals(listOf(1, 0), index.search("budget").map { it.chunk.index })
    }

    @Test fun `an empty, stopword-only or unknown query finds nothing`() {
        val index = Bm25Index.build(chunks("Some text about engines."))
        assertEquals(emptyList<Hit>(), index.search(""))
        assertEquals(emptyList<Hit>(), index.search("what is the of and"))
        assertEquals(emptyList<Hit>(), index.search("submarine"))
        assertEquals(emptyList<Hit>(), Bm25Index.build(emptyList()).search("engine"))
    }

    @Test fun `k limits the hits, best first`() {
        val index = Bm25Index.build(chunks("engine", "engine engine", "engine oil", "oil"))
        val hits = index.search("engine oil", k = 2)
        assertEquals(2, hits.size)
        assertEquals(2, hits[0].chunk.index)
        assertTrue(hits[0].score >= hits[1].score)
    }
}
