package com.vanskis.liquidpocket.tools

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** One search hit: [snippet] is plain text, possibly empty. */
data class WebResult(val title: String, val url: String, val snippet: String)

/** Reads DuckDuckGo's HTML-only "lite" results page (no JavaScript, one table row per part of a result). */
object DuckDuckGoLite {
    const val NAME = "DuckDuckGo"

    fun url(query: String): String = "https://lite.duckduckgo.com/lite/?q=" + WebText.encode(query)

    private val LINK = Regex("""<a\b([^>]*)>(.*?)</a\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val SNIPPET = Regex(
        """<td\b[^>]*\bclass\s*=\s*["']?[^"'>]*\bresult-snippet\b[^>]*>(.*?)</td\s*>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val RESULT_LINK_CLASS = Regex("""\bclass\s*=\s*["']?[^"'>]*\bresult-link\b""", RegexOption.IGNORE_CASE)
    private val HREF = Regex("""\bhref\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""", RegexOption.IGNORE_CASE)

    /** The results on [html], in order; ads and links that cannot be opened are left out. */
    fun parse(html: String): List<WebResult> {
        val links = LINK.findAll(html).filter { RESULT_LINK_CLASS.containsMatchIn(it.groupValues[1]) }.toList()
        return links.mapIndexedNotNull { i, link ->
            val href = HREF.find(link.groupValues[1])?.let { m -> m.groupValues.drop(1).firstOrNull { it.isNotEmpty() } }
                ?: return@mapIndexedNotNull null
            val url = unwrap(WebText.decodeEntities(href)) ?: return@mapIndexedNotNull null
            val title = WebText.stripHtml(link.groupValues[2]).ifEmpty { url }
            // The snippet is the next result-snippet cell before the next result's link.
            val end = links.getOrNull(i + 1)?.range?.first ?: html.length
            val snippet = SNIPPET.find(html.substring(link.range.last + 1, end))?.groupValues?.get(1).orEmpty()
            WebResult(title, url, WebText.stripHtml(snippet))
        }
    }

    /**
     * The real address behind [href]: DuckDuckGo wraps results in `//duckduckgo.com/l/?uddg=<encoded url>`.
     * Null for ads (`/y.js`) and anything that is not an http(s) address.
     */
    fun unwrap(href: String): String? {
        val absolute = if (href.startsWith("//")) "https:$href" else href
        val uri = runCatching { URI(absolute) }.getOrNull() ?: return null
        val host = uri.host.orEmpty()
        if (host.endsWith("duckduckgo.com")) {
            if (uri.path == "/l/" || uri.path == "/l") {
                val target = uri.rawQuery.orEmpty().split('&')
                    .firstOrNull { it.startsWith("uddg=") }?.removePrefix("uddg=")
                    ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }
                return target?.takeIf(WebText::isWebUrl)
            }
            return null // ads (/y.js) and DuckDuckGo's own pages
        }
        return absolute.takeIf(WebText::isWebUrl)
    }

    private val BLOCK_MARKERS = listOf("anomaly-modal", "anomaly.js", "challenge-form")

    /** True for the page DuckDuckGo shows instead of results when it suspects a bot. */
    fun isBlocked(html: String): Boolean = html.lowercase().let { page -> BLOCK_MARKERS.any { it in page } }

    /**
     * The results of a lite search answered with [status] and [html], or null when the search should
     * fall back to another source: an error status, a soft block (202 or a bot check) or no results.
     */
    fun results(status: Int, html: String): List<WebResult>? {
        if (status != 200 || isBlocked(html)) return null
        return parse(html).ifEmpty { null }
    }
}

/** Wikipedia's REST page search, the fallback when DuckDuckGo will not answer. */
object WikipediaSearch {
    const val NAME = "Wikipedia"

    fun url(query: String, limit: Int): String =
        "https://en.wikipedia.org/w/rest.php/v1/search/page?q=${WebText.encode(query)}&limit=$limit"

    /** The pages in a search response; an unreadable response has none. */
    fun parse(json: String): List<WebResult> {
        val root = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return emptyList()
        val pages = root["pages"] as? JsonArray ?: return emptyList()
        return pages.mapNotNull { page ->
            page as? JsonObject ?: return@mapNotNull null
            val title = page.text("title") ?: return@mapNotNull null
            val key = page.text("key") ?: title.replace(' ', '_')
            val excerpt = page.text("excerpt")?.let(WebText::stripHtml).orEmpty()
            val snippet = excerpt.ifEmpty { page.text("description").orEmpty() }
            WebResult(title, "https://en.wikipedia.org/wiki/" + WebText.encode(key), snippet)
        }
    }

    private fun JsonObject.text(key: String) =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)
}

/** Jina's reader, which turns a web page into readable text. */
object JinaReader {
    const val MAX_CHARS = 6_000

    fun url(page: String): String = "https://r.jina.ai/$page"
}

/**
 * The result the model reads for a search: a numbered list ("1. Title — url" then the snippet) under
 * [MAX_CHARS], with the query and the source. The transcript reads the sources back with [sources].
 */
object WebSearchResult {
    const val MAX_CHARS = 2_000
    private const val SNIPPET_CHARS = 240
    private const val SEPARATOR = " — "

    fun list(results: List<WebResult>, maxChars: Int = MAX_CHARS): String {
        val out = StringBuilder()
        results.forEachIndexed { i, r ->
            val title = r.title.replace('\n', ' ')
            val entry = buildString {
                append(i + 1).append(". ").append(title).append(SEPARATOR).append(r.url)
                if (r.snippet.isNotEmpty()) append("\n   ").append(WebText.truncate(r.snippet, SNIPPET_CHARS, "…"))
            }
            val needed = entry.length + if (out.isEmpty()) 0 else 1
            if (out.length + needed > maxChars) {
                // A first entry that alone is over budget is cut rather than dropping every result.
                if (out.isEmpty()) out.append(WebText.truncate(entry, maxChars, "…"))
                return out.toString()
            }
            if (out.isNotEmpty()) out.append('\n')
            out.append(entry)
        }
        return out.toString()
    }

    /** What a search result shows in the transcript. */
    class Summary(val query: String, val source: String, val sources: List<WebResult>)

    /** The [Summary] of a web_search result the tool returned; null for an error result. */
    fun summary(resultJson: String): Summary? {
        val root = runCatching { Json.parseToJsonElement(resultJson) as? JsonObject }.getOrNull() ?: return null
        fun text(key: String) = (root[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        val query = text("query") ?: return null
        return Summary(query, text("source").orEmpty(), sources(text("results").orEmpty()))
    }

    private val ENTRY = Regex("""^\d+\. (.+)$""")

    /** The (title, url) pairs in a [list]; anything else in it is ignored. */
    fun sources(list: String): List<WebResult> = list.lines().mapNotNull { line ->
        val text = ENTRY.find(line)?.groupValues?.get(1) ?: return@mapNotNull null
        val cut = text.lastIndexOf(SEPARATOR).takeIf { it > 0 } ?: return@mapNotNull null
        val url = text.substring(cut + SEPARATOR.length).trim()
        if (WebText.isWebUrl(url)) WebResult(text.substring(0, cut), url, "") else null
    }
}

object WebText {
    private val TAG = Regex("""<[^>]*>""")
    private val SCRIPT = Regex("""<(script|style)\b.*?</\1\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val ENTITY = Regex("""&(#[0-9]+|#[xX][0-9a-fA-F]+|[a-zA-Z]+);""")
    private val SPACE = Regex("""\s+""")
    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "ndash" to "–", "mdash" to "—", "hellip" to "…", "rsquo" to "’", "lsquo" to "‘", "rdquo" to "”", "ldquo" to "“",
    )

    /** [html] as one line of plain text: tags removed, entities decoded, whitespace collapsed. */
    fun stripHtml(html: String): String =
        decodeEntities(html.replace(SCRIPT, " ").replace(TAG, "")).replace(SPACE, " ").trim()

    fun decodeEntities(text: String): String = ENTITY.replace(text) { m ->
        val name = m.groupValues[1]
        when {
            name.startsWith("#x") || name.startsWith("#X") -> name.drop(2).toIntOrNull(16)?.let(::codePoint)
            name.startsWith("#") -> name.drop(1).toIntOrNull()?.let(::codePoint)
            else -> NAMED[name]
        } ?: m.value
    }

    private fun codePoint(cp: Int): String? = if (Character.isValidCodePoint(cp)) String(Character.toChars(cp)) else null

    /** [text] cut to at most [max] characters, at a word break when one is near, ending with [note]. */
    fun truncate(text: String, max: Int, note: String = "\n[truncated]"): String {
        if (text.length <= max) return text
        val room = (max - note.length).coerceAtLeast(0)
        val space = text.lastIndexOf(' ', room).takeIf { it > room * 3 / 4 } ?: room
        return text.substring(0, space).trimEnd() + note
    }

    fun encode(text: String): String = URLEncoder.encode(text, "UTF-8").replace("+", "%20")

    /** True for an absolute http or https address with a host. */
    fun isWebUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        return uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrEmpty()
    }
}
