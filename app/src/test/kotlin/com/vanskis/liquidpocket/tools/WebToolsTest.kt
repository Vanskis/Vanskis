package com.vanskis.liquidpocket.tools

import java.io.IOException
import java.util.concurrent.Executors
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebToolsTest {
    /** Shaped like lite.duckduckgo.com's results: an ad, then results whose markup varies in quoting and order. */
    private val ddgPage = """
        <!DOCTYPE html><html><head><title>kotlin coroutines at DuckDuckGo</title></head><body>
        <form action="/lite/" method="post"><input type="text" name="q" value="kotlin coroutines"></form>
        <table border="0">
          <tr class="result-sponsored">
            <td valign="top">1.&nbsp;</td>
            <td><a rel="nofollow" href="https://duckduckgo.com/y.js?ad_domain=ads.example&amp;ad_provider=x" class='result-link'>Buy Kotlin Now</a></td>
          </tr>
          <tr class="result-sponsored"><td>&nbsp;&nbsp;&nbsp;</td><td class='result-snippet'>Sponsored text.</td></tr>
          <tr>
            <td valign="top">1.&nbsp;</td>
            <td>
              <a rel="nofollow" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fkotlinlang.org%2Fdocs%2Fcoroutines%2Doverview.html%3Fa%3D1%26b%3D2&amp;rut=abc123" class='result-link'>Coroutines | <b>Kotlin</b> Documentation</a>
            </td>
          </tr>
          <tr>
            <td>&nbsp;&nbsp;&nbsp;</td>
            <td class='result-snippet'>
              <b>Coroutines</b> are a way to write asynchronous code &amp; keep it &quot;sequential&quot;&#39;s style&#x2026;
            </td>
          </tr>
          <tr><td>&nbsp;&nbsp;&nbsp;</td><td><span class='link-text'>kotlinlang.org/docs/coroutines-overview.html</span></td></tr>
          <tr>
            <td valign="top">2.&nbsp;</td>
            <td><a class="result-link" rel="nofollow" href="https://github.com/Kotlin/kotlinx.coroutines">GitHub - Kotlin/kotlinx.coroutines</a></td>
          </tr>
          <tr><td>&nbsp;&nbsp;&nbsp;</td><td valign="top" class="result-snippet">Library support for <b>Kotlin</b> coroutines.</td></tr>
          <tr>
            <td valign="top">3.&nbsp;</td>
            <td><a href=https://example.org/no-snippet class=result-link>No snippet here</a></td>
          </tr>
        </table>
        <a href="/lite/?q=kotlin&s=30" class="navbutton">Next Page &gt;</a>
        </body></html>
    """.trimIndent()

    private val blockedPage = """
        <html><body><form id="challenge-form" action="//duckduckgo.com/anomaly.js?sv=lite&cc=sre" method="POST">
        <div class="anomaly-modal__title">Unfortunately, bots use DuckDuckGo too.</div>
        <p>Please complete the following challenge to confirm this search was made by a human.</p></form></body></html>
    """.trimIndent()

    private val noResultsPage = """
        <html><body><table border="0"><tr><td>No results.</td></tr></table></body></html>
    """.trimIndent()

    private val wikipediaJson = """
        {"pages":[
          {"id":736,"key":"Albert_Einstein","title":"Albert Einstein",
           "excerpt":"<span class=\"searchmatch\">Albert</span> <span class=\"searchmatch\">Einstein</span> (14 March 1879 &ndash; 18 April 1955) was a German-born theoretical physicist",
           "matched_title":null,"description":"German-born physicist (1879–1955)",
           "thumbnail":{"mimetype":"image/jpeg","width":60,"height":80,"url":"//upload.wikimedia.org/a.jpg"}},
          {"id":9,"key":"Einstein_(crater)","title":"Einstein (crater)","excerpt":"","matched_title":null,"description":"Lunar crater","thumbnail":null},
          {"id":10,"title":"No Key Page","excerpt":null,"description":null}
        ]}
    """.trimIndent()

    @Test fun `lite results are read with their real addresses and ads are skipped`() {
        val results = DuckDuckGoLite.parse(ddgPage)
        assertEquals(3, results.size)
        assertEquals("Coroutines | Kotlin Documentation", results[0].title)
        assertEquals("https://kotlinlang.org/docs/coroutines-overview.html?a=1&b=2", results[0].url)
        assertEquals("GitHub - Kotlin/kotlinx.coroutines", results[1].title)
        assertEquals("https://github.com/Kotlin/kotlinx.coroutines", results[1].url)
        assertEquals("https://example.org/no-snippet", results[2].url)
    }

    @Test fun `each snippet belongs to its own result, as plain text`() {
        val results = DuckDuckGoLite.parse(ddgPage)
        assertEquals("Coroutines are a way to write asynchronous code & keep it \"sequential\"'s style…", results[0].snippet)
        assertEquals("Library support for Kotlin coroutines.", results[1].snippet)
        assertEquals("", results[2].snippet)
    }

    @Test fun `redirect links are unwrapped and non-web targets dropped`() {
        assertEquals("https://a.example/x%20y", DuckDuckGoLite.unwrap("//duckduckgo.com/l/?uddg=https%3A%2F%2Fa.example%2Fx%2520y&rut=1"))
        assertEquals("http://b.example/", DuckDuckGoLite.unwrap("https://duckduckgo.com/l/?kh=-1&uddg=http%3A%2F%2Fb.example%2F"))
        assertNull(DuckDuckGoLite.unwrap("//duckduckgo.com/l/?uddg=javascript%3Aalert(1)"))
        assertNull(DuckDuckGoLite.unwrap("https://duckduckgo.com/y.js?ad_domain=x"))
        assertNull(DuckDuckGoLite.unwrap("/lite/?q=next"))
        assertEquals("https://c.example/p", DuckDuckGoLite.unwrap("https://c.example/p"))
    }

    @Test fun `a soft block, an error status or no results signal the fallback`() {
        assertTrue(DuckDuckGoLite.isBlocked(blockedPage))
        assertFalse(DuckDuckGoLite.isBlocked(ddgPage))
        assertNull(DuckDuckGoLite.results(200, blockedPage))
        assertNull(DuckDuckGoLite.results(202, ddgPage))
        assertNull(DuckDuckGoLite.results(503, ""))
        assertNull(DuckDuckGoLite.results(200, noResultsPage))
        assertEquals(3, DuckDuckGoLite.results(200, ddgPage)?.size)
    }

    @Test fun `wikipedia pages map to title, article address and plain excerpt`() {
        val results = WikipediaSearch.parse(wikipediaJson)
        assertEquals(3, results.size)
        assertEquals(WebResult(
            "Albert Einstein",
            "https://en.wikipedia.org/wiki/Albert_Einstein",
            "Albert Einstein (14 March 1879 – 18 April 1955) was a German-born theoretical physicist",
        ), results[0])
        assertEquals("https://en.wikipedia.org/wiki/Einstein_%28crater%29", results[1].url)
        assertEquals("Lunar crater", results[1].snippet)
        assertEquals("https://en.wikipedia.org/wiki/No_Key_Page", results[2].url)
        assertEquals("", results[2].snippet)
        assertTrue(WikipediaSearch.parse("not json").isEmpty())
        assertTrue(WikipediaSearch.parse("""{"httpCode":400}""").isEmpty())
    }

    @Test fun `html is stripped to one line of text`() {
        assertEquals("a < b & c d", WebText.stripHtml("<p>a &lt; b</p>\n<script>var x = '<b>';</script>&amp;  c&nbsp;<br/>d"))
        assertEquals("café 😀 &bogus;", WebText.stripHtml("caf&#233; &#x1F600; &bogus;"))
    }

    @Test fun `long text is cut at a word with a note`() {
        val text = "word ".repeat(2_000).trim()
        val cut = WebText.truncate(text, JinaReader.MAX_CHARS)
        assertTrue(cut.length <= JinaReader.MAX_CHARS)
        assertTrue(cut.endsWith("word\n[truncated]"))
        assertEquals("short", WebText.truncate("short", JinaReader.MAX_CHARS))
        assertEquals("abcdefg\n[truncated]", WebText.truncate("abcdefghijklmnopqrstuvwxyz", 19))
    }

    @Test fun `the list stays under its budget and its sources read back`() {
        val many = (1..30).map { WebResult("Title $it — part", "https://example.com/$it", "snippet ".repeat(60)) }
        val list = WebSearchResult.list(many)
        assertTrue(list.length <= WebSearchResult.MAX_CHARS)
        assertTrue(list.startsWith("1. Title 1 — part — https://example.com/1\n   snippet"))
        val sources = WebSearchResult.sources(list)
        assertTrue(sources.size in 2..29)
        assertEquals("Title 1 — part", sources[0].title)
        assertEquals("https://example.com/2", sources[1].url)
    }

    @Test fun `a first entry over the budget is cut, not dropped`() {
        val list = WebSearchResult.list(listOf(WebResult("T", "https://example.com/" + "a".repeat(300), ""), WebResult("U", "https://b.example", "")), 100)
        assertTrue(list.isNotEmpty())
        assertTrue(list.length <= 100)
        assertTrue(list.startsWith("1. T — https://example.com/"))
        assertTrue(list.endsWith("…"))
    }

    @Test fun `a results page that merely mentions captcha is not a block`() {
        val page = ddgPage.replace("Library support for", "How reCAPTCHA and captcha work: library support for")
        assertFalse(DuckDuckGoLite.isBlocked(page))
        val results = DuckDuckGoLite.results(200, page)!!
        assertEquals(3, results.size)
        assertTrue(results[1].snippet.contains("captcha"))
        val http = FakeHttp { HttpResponse(200, page) }
        assertEquals("DuckDuckGo", result(WebSearchTool("1", { true }, http), """{"query":"how does reCAPTCHA work"}""").text("source"))
    }

    @Test fun `the transcript summary comes from the tool result and not from an error`() {
        val result = ToolResults.ok {
            put("query", "kotlin")
            put("source", "DuckDuckGo")
            put("results", WebSearchResult.list(DuckDuckGoLite.parse(ddgPage)))
        }.text
        val summary = WebSearchResult.summary(result)!!
        assertEquals("kotlin", summary.query)
        assertEquals("DuckDuckGo", summary.source)
        assertEquals(listOf("https://kotlinlang.org/docs/coroutines-overview.html?a=1&b=2", "https://github.com/Kotlin/kotlinx.coroutines", "https://example.org/no-snippet"), summary.sources.map { it.url })
        assertNull(WebSearchResult.summary(ToolResults.error("Web search failed.")))
    }

    private class FakeHttp(private val answer: (String) -> HttpResponse) : HttpGet {
        val calls = mutableListOf<Pair<String, Map<String, String>>>()
        override suspend fun get(url: String, headers: Map<String, String>): HttpResponse {
            calls += url to headers
            return answer(url)
        }
    }

    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
    private fun result(tool: Tool, args: String) = Json.parseToJsonElement(runBlocking { tool.run(args) }.text) as JsonObject

    @Test fun `search uses duckduckgo with a browser agent when it answers`() {
        val http = FakeHttp { HttpResponse(200, ddgPage) }
        val out = result(WebSearchTool("1.2", { true }, http), """{"query":"kotlin coroutines","max_results":2}""")
        assertEquals(1, http.calls.size)
        assertEquals("https://lite.duckduckgo.com/lite/?q=kotlin%20coroutines", http.calls[0].first)
        assertEquals(WebTools.BROWSER_AGENT, http.calls[0].second["User-Agent"])
        assertEquals("DuckDuckGo", out.text("source"))
        assertEquals(2, WebSearchResult.sources(out.text("results")).size)
    }

    @Test fun `search falls back to wikipedia when duckduckgo blocks or fails`() {
        for (ddg in listOf<(String) -> HttpResponse>({ HttpResponse(202, blockedPage) }, { throw IOException("reset") })) {
            val http = FakeHttp { url -> if ("wikipedia" in url) HttpResponse(200, wikipediaJson) else ddg(url) }
            val out = result(WebSearchTool("1.2", { true }, http), """{"query":"einstein"}""")
            assertEquals(2, http.calls.size)
            assertEquals("https://en.wikipedia.org/w/rest.php/v1/search/page?q=einstein&limit=5", http.calls[1].first)
            assertEquals(
                "LiquidPocket/1.2 (personal on-device assistant; https://github.com/Vanskis/Vanskis)",
                http.calls[1].second["User-Agent"],
            )
            assertEquals("Wikipedia", out.text("source"))
            assertTrue(out.text("results").startsWith("1. Albert Einstein — https://en.wikipedia.org/wiki/Albert_Einstein\n"))
        }
    }

    @Test fun `failures and the setting give error results without a request`() {
        val offline = FakeHttp { throw IOException("unreachable") }
        assertTrue(result(WebSearchTool("1", { true }, offline), """{"query":"x"}""").text("error").contains("offline"))
        val never = FakeHttp { error("no request expected") }
        assertEquals(WebTools.OFF, result(WebSearchTool("1", { false }, never), """{"query":"x"}""").text("error"))
        assertEquals(WebTools.OFF, result(FetchPageTool({ false }, never), """{"url":"https://a.example"}""").text("error"))
        assertTrue(never.calls.isEmpty())
    }

    @Test fun `requests run on the io dispatcher, never the caller's thread`() {
        val executor = Executors.newSingleThreadExecutor { Thread(it, "web-io") }
        val io = executor.asCoroutineDispatcher()
        val threads = mutableListOf<String>()
        val http = HttpGet { url, _ ->
            threads += Thread.currentThread().name.substringBefore(" @")
            if ("jina" in url) HttpResponse(200, "text") else HttpResponse(202, blockedPage)
        }
        try {
            result(WebSearchTool("1", { true }, http, io), """{"query":"x"}""")
            result(FetchPageTool({ true }, http, io), """{"url":"https://a.example"}""")
        } finally {
            executor.shutdown()
        }
        assertEquals(listOf("web-io", "web-io", "web-io"), threads)
    }

    @Test fun `cancelling a request that is never answered disconnects it`() {
        java.net.ServerSocket(0).use { server ->
            val outcome = runBlocking {
                val job = async(kotlinx.coroutines.Dispatchers.Default) {
                    runCatching { UrlConnectionGet().get("http://127.0.0.1:${server.localPort}/", emptyMap()) }
                }
                server.accept() // the request arrived and will not be answered
                kotlinx.coroutines.delay(200)
                job.cancel()
                kotlinx.coroutines.withTimeoutOrNull(5_000) { job.join() }
            }
            assertNotNull("cancel must end the blocked request promptly", outcome)
        }
    }

    @Test fun `fetch_page reads through jina, only for web addresses, truncated`() {
        val tool = FetchPageTool({ true }, FakeHttp { HttpResponse(200, "Title\n\n" + "text ".repeat(3_000)) })
        assertNotNull(tool.check("""{"url":"file:///sdcard/x"}"""))
        assertNotNull(tool.check("""{"url":"kotlinlang.org"}"""))
        assertNull(tool.check("""{"url":"https://kotlinlang.org/docs"}"""))
        assertTrue(result(tool, """{"url":"ftp://a.example/x"}""").containsKey("error"))
        val http = FakeHttp { HttpResponse(200, "Title\n\n" + "text ".repeat(3_000)) }
        val out = result(FetchPageTool({ true }, http), """{"url":"https://a.example/page?x=1"}""")
        assertEquals("https://r.jina.ai/https://a.example/page?x=1", http.calls.single().first)
        assertEquals("text/plain", http.calls.single().second["Accept"])
        assertTrue(out.text("content").length <= JinaReader.MAX_CHARS)
        assertTrue(out.text("content").endsWith("[truncated]"))
        val failing = FetchPageTool({ true }, FakeHttp { HttpResponse(451, "blocked") })
        assertTrue(result(failing, """{"url":"https://a.example"}""").text("error").contains("451"))
    }
}
