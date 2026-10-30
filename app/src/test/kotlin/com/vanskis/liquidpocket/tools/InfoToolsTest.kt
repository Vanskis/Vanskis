package com.vanskis.liquidpocket.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InfoToolsTest {
    private class FakeHttp(val answers: Map<String, HttpResponse>) : HttpGet {
        val asked = mutableListOf<String>()
        override suspend fun get(url: String, headers: Map<String, String>): HttpResponse {
            asked += url
            return answers.entries.firstOrNull { url.startsWith(it.key) }?.value ?: HttpResponse(404, "{}")
        }
    }

    private val geocode = """{"results":[{"name":"Lyon","latitude":45.75,"longitude":4.85,"country":"France","admin1":"Auvergne-Rhône-Alpes"}]}"""
    private val forecast = """
        {"current_units":{"temperature_2m":"°C","relative_humidity_2m":"%","wind_speed_10m":"km/h"},
         "current":{"time":"2026-10-30T15:00","temperature_2m":14.2,"relative_humidity_2m":71,"weather_code":2,"wind_speed_10m":9.4},
         "daily_units":{"temperature_2m_max":"°C"},
         "daily":{"time":["2026-10-30","2026-10-31"],"weather_code":[2,61],"temperature_2m_max":[16.0,13.5],"temperature_2m_min":[8.1,7.0],
                  "precipitation_probability_max":[10,80],"sunrise":["2026-10-30T07:22","2026-10-31T07:23"],"sunset":["2026-10-30T17:41","2026-10-31T17:39"]}}
    """

    @Test fun `weather geocodes the place, then reads now and each day`() = runBlocking {
        val http = FakeHttp(mapOf("https://geocoding-api.open-meteo.com" to HttpResponse(200, geocode), "https://api.open-meteo.com" to HttpResponse(200, forecast)))
        val result = WeatherTool({ true }, http, Dispatchers.Unconfined).run("""{"place":"Lyon","days":2}""")
        val root = Json.parseToJsonElement(result.text).jsonObject
        assertEquals("Lyon, Auvergne-Rhône-Alpes, France", root.getValue("place").jsonPrimitive.content)
        val now = root.getValue("now").jsonObject
        assertEquals("Partly cloudy", now.getValue("conditions").jsonPrimitive.content)
        assertEquals("14.2 °C", now.getValue("temperature").jsonPrimitive.content)
        assertEquals("71 %", now.getValue("humidity").jsonPrimitive.content)
        val tomorrow = root.getValue("days").jsonArray[1].jsonObject
        assertEquals("Light rain", tomorrow.getValue("conditions").jsonPrimitive.content)
        assertEquals("13.5 °C", tomorrow.getValue("high").jsonPrimitive.content)
        assertEquals("80 %", tomorrow.getValue("rain_chance").jsonPrimitive.content)
        assertEquals("17:39", tomorrow.getValue("sunset").jsonPrimitive.content)
        assertTrue(http.asked[1], "latitude=45.75&longitude=4.85" in http.asked[1] && "forecast_days=2" in http.asked[1])
    }

    @Test fun `weather says so when the place is unknown or the web is off`() = runBlocking {
        val http = FakeHttp(mapOf("https://geocoding-api.open-meteo.com" to HttpResponse(200, """{"generationtime_ms":0.1}""")))
        assertTrue(WeatherTool({ true }, http, Dispatchers.Unconfined).run("""{"place":"Nowhere"}""").text.contains("No place called"))
        assertTrue(WeatherTool({ false }, http, Dispatchers.Unconfined).run("""{"place":"Lyon"}""").text.contains(WebTools.OFF))
    }

    @Test fun `currency multiplies the rates by the amount`() = runBlocking {
        val http = FakeHttp(mapOf("https://api.frankfurter.dev/v1/latest?base=USD&symbols=EUR,JPY" to
            HttpResponse(200, """{"amount":1.0,"base":"USD","date":"2026-10-29","rates":{"EUR":0.9213,"JPY":151.37}}""")))
        val tool = CurrencyTool({ true }, http, Dispatchers.Unconfined)
        assertEquals("to must be three-letter currency codes like EUR", tool.check("""{"from":"usd","to":"euro"}"""))
        val root = Json.parseToJsonElement(tool.run("""{"amount":"1,250","from":"usd","to":["eur","JPY"]}""").text).jsonObject
        val converted = root.getValue("converted").jsonObject
        assertEquals(1151.625, converted.getValue("EUR").jsonPrimitive.content.toDouble(), 1e-9)
        assertEquals(189212.5, converted.getValue("JPY").jsonPrimitive.content.toDouble(), 1e-9)
        assertEquals("2026-10-29", root.getValue("rates_date").jsonPrimitive.content)
    }

    @Test fun `wikipedia falls back to a search when the topic is not a title`() = runBlocking {
        val summary = """{"type":"standard","title":"Marie Curie","description":"Physicist and chemist","extract":"Marie Curie was a physicist.","content_urls":{"desktop":{"page":"https://en.wikipedia.org/wiki/Marie_Curie"}}}"""
        val http = FakeHttp(mapOf(
            "https://en.wikipedia.org/api/rest_v1/page/summary/Marie_Curie" to HttpResponse(200, summary),
            "https://en.wikipedia.org/w/rest.php/v1/search/page" to HttpResponse(200, """{"pages":[{"title":"Marie Curie","key":"Marie_Curie"}]}"""),
        ))
        val root = Json.parseToJsonElement(WikipediaTool("1", { true }, http, Dispatchers.Unconfined).run("""{"topic":"who discovered radium"}""").text).jsonObject
        assertEquals("Marie Curie was a physicist.", root.getValue("summary").jsonPrimitive.content)
        assertEquals("https://en.wikipedia.org/wiki/Marie_Curie", root.getValue("url").jsonPrimitive.content)
        assertEquals(3, http.asked.size)
        assertNull(WikipediaSummary.parse("""{"title":"x"}"""))
    }
}
