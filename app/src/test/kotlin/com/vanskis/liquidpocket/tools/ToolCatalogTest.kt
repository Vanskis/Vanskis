package com.vanskis.liquidpocket.tools

import com.vanskis.liquidpocket.engine.ToolSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCatalogTest {
    private fun spec(name: String, description: String) = ToolSpec(name, description, """{"type":"object"}""")
    private val time = spec("get_time", "Get the current time.")
    private val search = spec(WebTools.SEARCH, "Search the web.")
    private val weather = spec(WebTools.WEATHER, "Get the weather now and the forecast for a place. The place leaves the phone.")
    private val torch = spec("set_flashlight", "Turn the phone's flashlight (torch) on or off.")
    private val all = listOf(time, weather, search, torch)

    @Test fun `only the core tools are shown in full, the rest named in tool_search`() {
        val offered = ToolCatalog.offered(all, loaded = emptySet())
        assertEquals(listOf("get_time", WebTools.SEARCH, ToolCatalog.SEARCH), offered.map { it.name })
        val listing = offered.last().description
        assertTrue(listing, "- get_weather: Get the weather now and the forecast for a place." in listing)
        assertTrue(listing, "- set_flashlight: Turn the phone's flashlight (torch) on or off." in listing)
    }

    @Test fun `loaded tools are shown in full in their place, and tool_search goes once none are left`() {
        assertEquals(listOf("get_time", WebTools.WEATHER, WebTools.SEARCH, ToolCatalog.SEARCH), ToolCatalog.offered(all, setOf(WebTools.WEATHER)).map { it.name })
        assertEquals(all, ToolCatalog.offered(all, setOf(WebTools.WEATHER, "set_flashlight")))
        assertEquals(emptyList<ToolSpec>(), ToolCatalog.offered(emptyList(), emptySet()))
    }

    @Test fun `a search loads the tools named, else the best matches, and reads back as loaded`() {
        val byName = Json.parseToJsonElement(ToolCatalog.search(all, """{"names":["SET_FLASHLIGHT"]}""")).jsonObject
        assertEquals(listOf("set_flashlight"), byName.getValue("loaded").jsonArray.map { it.jsonPrimitive.content })
        val described = byName.getValue("tools").jsonArray.single().jsonObject
        assertEquals(torch.description, described.getValue("description").jsonPrimitive.content)

        val byQuery = ToolCatalog.search(all, """{"query":"what's the forecast in Lyon"}""")
        assertEquals(setOf(WebTools.WEATHER), ToolCatalog.loadedIn(listOf(byQuery)))
        assertEquals(setOf(WebTools.WEATHER, "set_flashlight"), ToolCatalog.loadedIn(listOf(byQuery, byName.toString(), "not json", """{"error":"x"}""")))
    }

    @Test fun `a search that matches nothing lists what can be loaded, and never loads a core tool`() {
        val miss = Json.parseToJsonElement(ToolCatalog.search(all, """{"query":"zzz"}""")).jsonObject
        assertTrue(miss.getValue("error").jsonPrimitive.content.endsWith("get_weather, set_flashlight."))
        assertEquals(emptySet<String>(), ToolCatalog.loadedIn(listOf(ToolCatalog.search(all, """{"names":["get_time"]}"""))))
    }

    @Test fun `math script calls keep commas inside one expression and escape quotes`() {
        val args = ToolArgs.parse("""{"expression":"max(1, 2)\nsqrt(2)"}""")
        assertEquals(listOf("max(1, 2)", "sqrt(2)"), MathScript.expressions(args))
        assertEquals(listOf("a = 1", "a * 3"), MathScript.expressions(ToolArgs.parse("""{"expressions":["a = 1", " a * 3 "]}""")))
        assertEquals("""lpRun(["derivative('x^2', \"x\")"], "exact")""", MathScript.call(listOf("derivative('x^2', \"x\")"), "bogus"))
        assertEquals("""lpRun(["1/3"], "fraction")""", MathScript.call(listOf("1/3"), "fraction"))
        assertTrue(MathScript.expressions(JsonObject(emptyMap())).isEmpty())
    }
}
