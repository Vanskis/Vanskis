package com.vanskis.liquidpocket.docs

import kotlin.math.ln

/** Words for keyword search: lowercased runs of Unicode letters and digits, without stopwords, plurals made singular. */
object Tokenizer {
    private val stopwords = setOf(
        "a", "about", "above", "after", "again", "all", "am", "an", "and", "any", "are", "as", "at",
        "be", "because", "been", "before", "being", "below", "between", "both", "but", "by",
        "can", "could", "did", "do", "does", "doing", "down", "during", "each", "few", "for", "from",
        "further", "had", "has", "have", "having", "he", "her", "here", "hers", "herself", "him",
        "himself", "his", "how", "i", "if", "in", "into", "is", "it", "its", "itself", "just", "me",
        "more", "most", "my", "myself", "no", "nor", "not", "now", "of", "off", "on", "once", "only",
        "or", "other", "our", "ours", "ourselves", "out", "over", "own", "same", "she", "should", "so",
        "some", "such", "than", "that", "the", "their", "theirs", "them", "themselves", "then", "there",
        "these", "they", "this", "those", "through", "to", "too", "under", "until", "up", "very", "was",
        "we", "were", "what", "when", "where", "which", "while", "who", "whom", "why", "will", "with",
        "would", "you", "your", "yours", "yourself", "yourselves",
    )

    fun tokens(text: String): List<String> {
        val out = ArrayList<String>()
        val word = StringBuilder()
        fun flush() {
            if (word.isEmpty()) return
            val w = word.toString()
            word.setLength(0)
            // Single letters (the "s" of "it's") carry nothing; single digits do.
            if ((w.length > 1 || w[0].isDigit()) && w !in stopwords) out += stem(w)
        }
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (Character.isLetterOrDigit(cp)) word.appendCodePoint(Character.toLowerCase(cp)) else flush()
            i += Character.charCount(cp)
        }
        flush()
        return out
    }

    /** Plural to singular only: "batteries" to "battery", "invoices" to "invoice"; "class", "status", "analysis" stay. */
    private fun stem(w: String): String = when {
        w.length > 4 && w.endsWith("ies") -> w.dropLast(3) + "y"
        w.length > 3 && w.endsWith("s") && !w.endsWith("ss") && !w.endsWith("us") && !w.endsWith("is") -> w.dropLast(1)
        else -> w
    }
}

/** A search result: the chunk and its BM25 score (higher is more relevant). */
data class Hit(val chunk: Chunk, val score: Double)

/** Okapi BM25 over a document's chunks. Immutable once built; safe to search from any thread. */
class Bm25Index private constructor(
    private val chunks: List<Chunk>,
    private val termFreqs: List<Map<String, Int>>,
    private val lengths: IntArray,
    private val docFreq: Map<String, Int>,
    private val k1: Double,
    private val b: Double,
) {
    private val avgLength: Double = if (lengths.isEmpty()) 0.0 else lengths.average()

    /**
     * The up to [k] chunks that share words with [query], best first (ties in document order).
     * Empty when the query has no searchable words or none of them occur.
     */
    fun search(query: String, k: Int = 5): List<Hit> {
        val terms = Tokenizer.tokens(query).distinct().filter { it in docFreq }
        if (terms.isEmpty() || k <= 0) return emptyList()
        val n = chunks.size
        val idf = terms.associateWith { val df = docFreq.getValue(it); ln(1 + (n - df + 0.5) / (df + 0.5)) }
        val hits = ArrayList<Hit>()
        for (i in chunks.indices) {
            val tf = termFreqs[i]
            val norm = k1 * (1 - b + b * lengths[i] / avgLength)
            var score = 0.0
            for (t in terms) {
                val f = tf[t] ?: continue
                score += idf.getValue(t) * f * (k1 + 1) / (f + norm)
            }
            if (score > 0) hits += Hit(chunks[i], score)
        }
        return hits.sortedWith(compareByDescending<Hit> { it.score }.thenBy { it.chunk.index }).take(k)
    }

    companion object {
        fun build(chunks: List<Chunk>, k1: Double = 1.2, b: Double = 0.75): Bm25Index {
            val termFreqs = chunks.map { c -> Tokenizer.tokens(c.text).groupingBy { it }.eachCount() }
            val docFreq = HashMap<String, Int>()
            termFreqs.forEach { tf -> tf.keys.forEach { docFreq[it] = (docFreq[it] ?: 0) + 1 } }
            val lengths = IntArray(chunks.size) { termFreqs[it].values.sum() }
            return Bm25Index(chunks, termFreqs, lengths, docFreq, k1, b)
        }
    }
}
