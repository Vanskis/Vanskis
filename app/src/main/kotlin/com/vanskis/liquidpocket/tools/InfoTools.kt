package com.vanskis.liquidpocket.tools

import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Open-Meteo's free, keyless geocoding and forecast APIs. */
object OpenMeteo {
    fun geocodeUrl(place: String) =
        "https://geocoding-api.open-meteo.com/v1/search?name=${WebText.encode(place)}&count=1&language=en&format=json"

    fun forecastUrl(latitude: Double, longitude: Double, days: Int, fahrenheit: Boolean): String =
        "https://api.open-meteo.com/v1/forecast?latitude=$latitude&longitude=$longitude" +
            "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m,precipitation" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,sunrise,sunset" +
            "&timezone=auto&forecast_days=$days" +
            if (fahrenheit) "&temperature_unit=fahrenheit&wind_speed_unit=mph&precipitation_unit=inch" else ""

    class Place(val label: String, val latitude: Double, val longitude: Double)

    /** The first place in a geocoding answer; null when there is none. */
    fun place(json: String): Place? {
        val first = (jsonObject(json)?.get("results") as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
        val lat = first.number("latitude") ?: return null
        val lon = first.number("longitude") ?: return null
        val label = listOfNotNull(first.text("name"), first.text("admin1"), first.text("country")).distinct().joinToString(", ")
        return Place(label, lat, lon)
    }

    /** The forecast answer as the model reads it: the weather now, then each day. */
    fun forecast(json: String, place: String): String? {
        val root = jsonObject(json) ?: return null
        val current = root["current"] as? JsonObject
        val units = root["current_units"] as? JsonObject
        val daily = root["daily"] as? JsonObject
        if (current == null && daily == null) return null
        fun withUnit(key: String) = current?.number(key)?.let { "${trim(it)} ${units?.text(key).orEmpty()}".trim() }
        val dailyUnits = root["daily_units"] as? JsonObject
        return kotlinx.serialization.json.buildJsonObject {
            put("place", place)
            if (current != null) putJsonObject("now") {
                current.text("time")?.let { put("time", it) }
                current.number("weather_code")?.let { put("conditions", conditions(it.toInt())) }
                withUnit("temperature_2m")?.let { put("temperature", it) }
                withUnit("apparent_temperature")?.let { put("feels_like", it) }
                withUnit("relative_humidity_2m")?.let { put("humidity", it) }
                withUnit("wind_speed_10m")?.let { put("wind", it) }
                withUnit("precipitation")?.let { put("precipitation", it) }
            }
            val dates = (daily?.get("time") as? JsonArray).orEmpty()
            if (daily != null && dates.isNotEmpty()) putJsonArray("days") {
                fun at(key: String, i: Int) = ((daily[key] as? JsonArray)?.getOrNull(i) as? JsonPrimitive)
                val temp = dailyUnits?.text("temperature_2m_max").orEmpty()
                dates.indices.forEach { i ->
                    addJsonObject {
                        at("time", i)?.contentOrNull?.let { put("date", it) }
                        at("weather_code", i)?.doubleOrNull?.let { put("conditions", conditions(it.toInt())) }
                        at("temperature_2m_max", i)?.doubleOrNull?.let { put("high", "${trim(it)} $temp".trim()) }
                        at("temperature_2m_min", i)?.doubleOrNull?.let { put("low", "${trim(it)} $temp".trim()) }
                        at("precipitation_probability_max", i)?.doubleOrNull?.let { put("rain_chance", "${trim(it)} %") }
                        at("sunrise", i)?.contentOrNull?.let { put("sunrise", it.substringAfter('T')) }
                        at("sunset", i)?.contentOrNull?.let { put("sunset", it.substringAfter('T')) }
                    }
                }
            }
        }.toString()
    }

    /** WMO weather interpretation codes, as Open-Meteo gives them. */
    fun conditions(code: Int): String = when (code) {
        0 -> "Clear sky"
        1 -> "Mainly clear"
        2 -> "Partly cloudy"
        3 -> "Overcast"
        45, 48 -> "Fog"
        51, 53, 55 -> "Drizzle"
        56, 57 -> "Freezing drizzle"
        61 -> "Light rain"
        63 -> "Rain"
        65 -> "Heavy rain"
        66, 67 -> "Freezing rain"
        71 -> "Light snow"
        73 -> "Snow"
        75 -> "Heavy snow"
        77 -> "Snow grains"
        80, 81, 82 -> "Rain showers"
        85, 86 -> "Snow showers"
        95 -> "Thunderstorm"
        96, 99 -> "Thunderstorm with hail"
        else -> "Unknown ($code)"
    }
}

/** Frankfurter's free, keyless exchange rates (the European Central Bank's reference rates). */
object Frankfurter {
    private val CODE = Regex("[A-Z]{3}")

    fun isCode(code: String) = CODE.matches(code)

    fun url(from: String, to: List<String>) =
        "https://api.frankfurter.dev/v1/latest?base=$from" + if (to.isEmpty()) "" else "&symbols=" + to.joinToString(",")

    /** [amount] of the answer's base in each of its rates; null when the answer has no rates. */
    fun convert(json: String, amount: Double): String? {
        val root = jsonObject(json) ?: return null
        val rates = root["rates"] as? JsonObject ?: return null
        return kotlinx.serialization.json.buildJsonObject {
            put("amount", amount)
            root.text("base")?.let { put("from", it) }
            root.text("date")?.let { put("rates_date", it) }
            putJsonObject("converted") {
                rates.forEach { (code, rate) -> (rate as? JsonPrimitive)?.doubleOrNull?.let { put(code, round(amount * it)) } }
            }
            putJsonObject("rate") {
                rates.forEach { (code, rate) -> (rate as? JsonPrimitive)?.doubleOrNull?.let { put(code, it) } }
            }
        }.toString()
    }

    private fun round(value: Double) = Math.round(value * 10_000) / 10_000.0
}

/** Wikipedia's page summaries (the lead paragraph). */
object WikipediaSummary {
    fun url(title: String, language: String) =
        "https://$language.wikipedia.org/api/rest_v1/page/summary/${WebText.encode(title.trim().replace(' ', '_'))}?redirect=true"

    /** The model's view of a summary answer; null when it has no text. */
    fun parse(json: String): String? {
        val root = jsonObject(json) ?: return null
        val extract = root.text("extract") ?: return null
        val page = ((root["content_urls"] as? JsonObject)?.get("desktop") as? JsonObject)?.text("page")
        return kotlinx.serialization.json.buildJsonObject {
            root.text("title")?.let { put("title", it) }
            root.text("description")?.let { put("description", it) }
            put("summary", WebText.truncate(extract, 4000))
            if (root.text("type") == "disambiguation") put("note", "This title has several meanings; ask which one, or look up a more specific title.")
            page?.let { put("url", it) }
        }.toString()
    }
}

private fun jsonObject(json: String): JsonObject? = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull()
private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)
private fun JsonObject.number(key: String) = (this[key] as? JsonPrimitive)?.doubleOrNull
private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
private fun trim(value: Double): String = if (value == Math.rint(value)) value.toLong().toString() else String.format(Locale.ROOT, "%.1f", value)

private fun offline(e: IOException) = ToolResult(ToolResults.error("The request failed: ${e.message ?: "no connection"}. The phone may be offline."))

class WeatherTool(
    private val enabled: () -> Boolean,
    private val http: HttpGet = UrlConnectionGet(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : JsonTool(
    WebTools.WEATHER,
    "Get the weather now and the forecast for a place. The place name leaves the phone (to Open-Meteo).",
    """
    {"type": "object", "properties": {
      "place": {"type": "string", "description": "A city or town, like \"Lyon\" or \"Austin, Texas\""},
      "days": {"type": "integer", "description": "Days of forecast, 1 to 7 (default 3)"},
      "fahrenheit": {"type": "boolean", "description": "True for °F and mph instead of °C and km/h"}
    }, "required": ["place"]}
    """,
    needsConfirmation = false,
) {
    override fun check(args: JsonObject) = if (args.string("place") == null) "place is missing" else null

    override fun describe(args: JsonObject) = "Weather for ${args.string("place")}"

    override suspend fun run(args: JsonObject): ToolResult {
        if (!enabled()) return ToolResult(ToolResults.error(WebTools.OFF))
        val place = args.string("place") ?: return ToolResult(ToolResults.error("place is missing"))
        val days = (args.int("days") ?: 3).coerceIn(1, 7)
        return withContext(io) {
            try {
                val found = http.get(OpenMeteo.geocodeUrl(place), JSON).takeIf { it.status == 200 }?.let { OpenMeteo.place(it.body) }
                    ?: return@withContext ToolResult(ToolResults.error("No place called “$place” was found. Try the nearest city."))
                val answer = http.get(OpenMeteo.forecastUrl(found.latitude, found.longitude, days, args.bool("fahrenheit") == true), JSON)
                val text = answer.takeIf { it.status == 200 }?.let { OpenMeteo.forecast(it.body, found.label) }
                    ?: return@withContext ToolResult(ToolResults.error("The forecast is unavailable right now (Open-Meteo answered ${answer.status})."))
                ToolResult(text)
            } catch (e: IOException) {
                offline(e)
            }
        }
    }
}

class CurrencyTool(
    private val enabled: () -> Boolean,
    private val http: HttpGet = UrlConnectionGet(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : JsonTool(
    WebTools.CURRENCY,
    "Convert money between currencies at today's exchange rate. The currencies leave the phone (to Frankfurter).",
    """
    {"type": "object", "properties": {
      "amount": {"type": "number", "description": "How much (default 1)"},
      "from": {"type": "string", "description": "ISO currency code, like USD"},
      "to": {"type": "array", "items": {"type": "string"}, "description": "ISO currency codes, like [\"EUR\", \"JPY\"]"}
    }, "required": ["from", "to"]}
    """,
    needsConfirmation = false,
) {
    private fun codes(args: JsonObject) = args.strings("to").map { it.uppercase() }

    override fun check(args: JsonObject): String? {
        val from = args.string("from")?.uppercase()
        return when {
            from == null || !Frankfurter.isCode(from) -> "from must be a three-letter currency code like USD"
            codes(args).isEmpty() || !codes(args).all(Frankfurter::isCode) -> "to must be three-letter currency codes like EUR"
            else -> null
        }
    }

    override fun describe(args: JsonObject) = "Convert ${args.string("from")} to ${codes(args).joinToString()}"

    override suspend fun run(args: JsonObject): ToolResult {
        if (!enabled()) return ToolResult(ToolResults.error(WebTools.OFF))
        check(args)?.let { return ToolResult(ToolResults.error(it)) }
        val from = args.string("from")!!.uppercase()
        val amount = args.string("amount")?.replace(",", "")?.toDoubleOrNull() ?: 1.0
        return withContext(io) {
            try {
                val answer = http.get(Frankfurter.url(from, codes(args).filter { it != from }), JSON)
                val text = answer.takeIf { it.status == 200 }?.let { Frankfurter.convert(it.body, amount) }
                    ?: return@withContext ToolResult(ToolResults.error("No rate was found (the rates service answered ${answer.status}). It covers about 30 major currencies."))
                ToolResult(text)
            } catch (e: IOException) {
                offline(e)
            }
        }
    }
}

class WikipediaTool(
    private val version: String,
    private val enabled: () -> Boolean,
    private val http: HttpGet = UrlConnectionGet(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : JsonTool(
    WebTools.WIKIPEDIA,
    "Look up a topic's Wikipedia summary: a person, place, thing or event. The topic leaves the phone.",
    """
    {"type": "object", "properties": {
      "topic": {"type": "string", "description": "The article title or what to look up"},
      "language": {"type": "string", "description": "Wikipedia language code, like en or fr (default en)"}
    }, "required": ["topic"]}
    """,
    needsConfirmation = false,
) {
    override fun check(args: JsonObject) = if (args.string("topic") == null) "topic is missing" else null

    override fun describe(args: JsonObject) = "Look up “${args.string("topic")}” on Wikipedia"

    override suspend fun run(args: JsonObject): ToolResult {
        if (!enabled()) return ToolResult(ToolResults.error(WebTools.OFF))
        val topic = args.string("topic") ?: return ToolResult(ToolResults.error("topic is missing"))
        val language = args.string("language")?.lowercase()?.takeIf { Regex("[a-z]{2,3}").matches(it) } ?: "en"
        val headers = mapOf("User-Agent" to WebTools.wikipediaAgent(version), "Accept" to "application/json")
        return withContext(io) {
            try {
                val direct = http.get(WikipediaSummary.url(topic, language), headers)
                direct.takeIf { it.status == 200 }?.let(::summaryOf)?.let { return@withContext it }
                // Not a title: search, and summarize the best match (the search covers English Wikipedia).
                val search = http.get(WikipediaSearch.url(topic, 1), headers)
                val best = WikipediaSearch.parse(search.body).firstOrNull()
                    ?: return@withContext ToolResult(ToolResults.error("Wikipedia has no article on “$topic”."))
                val page = http.get(WikipediaSummary.url(best.title, "en"), headers)
                page.takeIf { it.status == 200 }?.let(::summaryOf)
                    ?: ToolResult(ToolResults.error("Wikipedia's summary is unavailable right now (it answered ${page.status})."))
            } catch (e: IOException) {
                offline(e)
            }
        }
    }

    private fun summaryOf(response: HttpResponse) = WikipediaSummary.parse(response.body)?.let(::ToolResult)
}

private val JSON = mapOf("Accept" to "application/json", "User-Agent" to WebTools.BROWSER_AGENT)
