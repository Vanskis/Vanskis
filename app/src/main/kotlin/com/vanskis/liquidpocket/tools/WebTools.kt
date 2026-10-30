package com.vanskis.liquidpocket.tools

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put

/**
 * The only tools that send anything off the phone: the query, place, topic or address goes to
 * DuckDuckGo, Wikipedia, Jina, Open-Meteo or Frankfurter. They run without asking, and only while [enabled] (the Web search setting);
 * the chat also stops offering them when it is off.
 */
object WebTools {
    const val SEARCH = "web_search"
    const val FETCH = "fetch_page"
    const val WEATHER = "get_weather"
    const val WIKIPEDIA = "wikipedia"
    const val CURRENCY = "convert_currency"
    val NAMES = setOf(SEARCH, FETCH, WEATHER, WIKIPEDIA, CURRENCY)

    const val OFF = "Web search is turned off in Liquid Pocket's settings. Answer without it, or tell the user they can turn it on."

    /** The identity Wikipedia asks API clients to send. */
    fun wikipediaAgent(version: String) =
        "LiquidPocket/$version (personal on-device assistant; https://github.com/Vanskis/Vanskis)"

    /** A common desktop browser; DuckDuckGo's lite page refuses unknown clients more often. */
    const val BROWSER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
}

/** An HTTP answer: [body] is read whatever the status. */
class HttpResponse(val status: Int, val body: String)

/** GETs a URL with the given headers; throws [IOException] when there is no answer. Cancelling it abandons the request. */
fun interface HttpGet {
    suspend fun get(url: String, headers: Map<String, String>): HttpResponse
}

/**
 * [HttpGet] over [HttpURLConnection]: 10 s to connect, 15 s to read, 20 s in all, at most [maxBytes] of body.
 * A blocking read ignores thread interrupts, so cancelling (or the overall limit) disconnects the connection instead.
 */
class UrlConnectionGet(private val maxBytes: Int = 2_000_000) : HttpGet {
    override suspend fun get(url: String, headers: Map<String, String>): HttpResponse {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            return withTimeout(TOTAL_TIMEOUT_MS) {
                coroutineScope {
                    val read = async(Dispatchers.IO) { read(connection, headers) }
                    try {
                        read.await()
                    } catch (e: CancellationException) {
                        connection.disconnect() // unblocks the read so this scope can finish
                        throw e
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            throw SocketTimeoutException("Timed out after ${TOTAL_TIMEOUT_MS / 1000} s")
        } finally {
            connection.disconnect()
        }
    }

    private fun read(connection: HttpURLConnection, headers: Map<String, String>): HttpResponse {
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.instanceFollowRedirects = true
        headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
        val status = connection.responseCode
        val stream = (if (status >= 400) connection.errorStream else connection.inputStream)
            ?: return HttpResponse(status, "")
        val body = ByteArrayOutputStream()
        stream.use { input ->
            val buffer = ByteArray(16 * 1024)
            while (body.size() < maxBytes) {
                val n = input.read(buffer, 0, minOf(buffer.size, maxBytes - body.size()))
                if (n < 0) break
                body.write(buffer, 0, n)
            }
        }
        return HttpResponse(status, body.toString(Charsets.UTF_8.name()))
    }

    private companion object {
        const val TOTAL_TIMEOUT_MS = 20_000L
    }
}

class WebSearchTool(
    private val version: String,
    private val enabled: () -> Boolean,
    private val http: HttpGet = UrlConnectionGet(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : JsonTool(
    WebTools.SEARCH,
    "Search the web for current or factual information. Returns titles, links and snippets; " +
        "use fetch_page to read one. The query leaves the phone.",
    """
    {"type": "object", "properties": {
      "query": {"type": "string", "description": "What to search for"},
      "max_results": {"type": "integer", "description": "How many results, 1 to 10 (default 5)"}
    }, "required": ["query"]}
    """,
    needsConfirmation = false,
) {
    override fun check(args: JsonObject) = if (args.string("query") == null) "query is missing" else null

    override fun describe(args: JsonObject) = "Search the web for “${args.string("query")}”"

    override suspend fun run(args: JsonObject): ToolResult {
        if (!enabled()) return ToolResult(ToolResults.error(WebTools.OFF))
        val query = args.string("query") ?: return ToolResult(ToolResults.error("query is missing"))
        val max = (args.int("max_results") ?: 5).coerceIn(1, 10)
        return withContext(io) {
            val ddg = runCatching {
                http.get(DuckDuckGoLite.url(query), mapOf("User-Agent" to WebTools.BROWSER_AGENT, "Accept" to "text/html"))
            }.getOrNull()?.let { DuckDuckGoLite.results(it.status, it.body) }
            val (source, results) = if (ddg != null) {
                DuckDuckGoLite.NAME to ddg
            } else {
                val wiki = try {
                    http.get(
                        WikipediaSearch.url(query, max),
                        mapOf("User-Agent" to WebTools.wikipediaAgent(version), "Accept" to "application/json"),
                    )
                } catch (e: IOException) {
                    return@withContext ToolResult(ToolResults.error("Web search failed: ${e.message ?: "no connection"}. The phone may be offline."))
                }
                if (wiki.status != 200) {
                    return@withContext ToolResult(ToolResults.error("Web search failed (Wikipedia answered ${wiki.status})."))
                }
                WikipediaSearch.NAME to WikipediaSearch.parse(wiki.body)
            }
            ToolResults.ok {
                put("query", query)
                put("source", source)
                if (results.isEmpty()) put("results", "No results.")
                else put("results", WebSearchResult.list(results.take(max)))
            }
        }
    }
}

class FetchPageTool(
    private val enabled: () -> Boolean,
    private val http: HttpGet = UrlConnectionGet(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : JsonTool(
    WebTools.FETCH,
    "Read a web page as plain text, for example a link from web_search. The address leaves the phone.",
    """
    {"type": "object", "properties": {
      "url": {"type": "string", "description": "The page's full http or https address"}
    }, "required": ["url"]}
    """,
    needsConfirmation = false,
) {
    override fun check(args: JsonObject) =
        if (args.string("url")?.let(WebText::isWebUrl) != true) "url must be a full http or https address" else null

    override fun describe(args: JsonObject) = "Read ${args.string("url")}"

    override suspend fun run(args: JsonObject): ToolResult {
        if (!enabled()) return ToolResult(ToolResults.error(WebTools.OFF))
        val url = args.string("url")?.takeIf(WebText::isWebUrl)
            ?: return ToolResult(ToolResults.error("url must be a full http or https address"))
        return withContext(io) {
            val page = try {
                http.get(JinaReader.url(url), mapOf("Accept" to "text/plain", "User-Agent" to WebTools.BROWSER_AGENT))
            } catch (e: IOException) {
                return@withContext ToolResult(ToolResults.error("Could not read the page: ${e.message ?: "no connection"}. The phone may be offline."))
            }
            val text = page.body.trim()
            when {
                page.status != 200 -> ToolResult(ToolResults.error("Could not read the page (the reader answered ${page.status})."))
                text.isEmpty() -> ToolResult(ToolResults.error("The page has no readable text."))
                else -> ToolResults.ok {
                    put("url", url)
                    put("content", WebText.truncate(text, JinaReader.MAX_CHARS))
                }
            }
        }
    }
}
