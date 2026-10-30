package com.vanskis.liquidpocket.tools

import com.vanskis.liquidpocket.engine.ToolSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Which tools the model sees in full. The [CORE] ones always; every other one is only named, with a
 * line on what it does, in [SEARCH]'s description until the model loads it with a [SEARCH] call. A
 * small model reads a long tool list poorly, and each schema takes room in the context.
 *
 * What a conversation has loaded is read back from its [SEARCH] results ([loadedIn]), so it stays
 * loaded for the rest of that conversation, after reopening it too, and the prompt does not change
 * between turns (the engine keeps its cached prefix).
 */
object ToolCatalog {
    const val SEARCH = "tool_search"
    val CORE = setOf(WebTools.SEARCH, "get_time", Choices.TOOL)

    /** Most tools one search loads. */
    const val MAX_LOADED = 4

    /** [available]'s core and [loaded] tools in full, in their order, then [SEARCH] while any are left to load. */
    fun offered(available: List<ToolSpec>, loaded: Set<String>): List<ToolSpec> {
        val (shown, deferred) = available.partition { it.name in CORE || it.name in loaded }
        return if (deferred.isEmpty()) shown else shown + searchSpec(deferred)
    }

    fun searchSpec(deferred: List<ToolSpec>): ToolSpec = ToolSpec(
        SEARCH,
        "Load more tools, then call them. Search by what you need, or give their names. Tools you can load:\n" +
            deferred.joinToString("\n") { "- ${it.name}: ${summary(it.description)}" },
        """
        {"type": "object", "properties": {
          "query": {"type": "string", "description": "What the tool should do, like \"weather\" or \"exact math\""},
          "names": {"type": "array", "items": {"type": "string"}, "description": "Tool names from the list to load"}
        }}
        """.trimIndent(),
    )

    /** A description's first sentence. */
    fun summary(description: String): String {
        val end = description.indexOf(". ")
        return (if (end < 0) description else description.substring(0, end + 1)).trim()
    }

    /** Runs a [SEARCH] call against [available]: the tools named, else the best matches for the query. */
    fun search(available: List<ToolSpec>, argumentsJson: String): String {
        val args = ToolArgs.parse(argumentsJson)
        val deferred = available.filter { it.name !in CORE }
        val named = args.strings("names").mapNotNull { name -> deferred.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) } }
        val found = named.ifEmpty { matches(deferred, args.string("query").orEmpty()) }.take(MAX_LOADED)
        if (found.isEmpty()) {
            return ToolResults.error("No tool matched. Tools you can load: ${deferred.joinToString { it.name }}.")
        }
        return kotlinx.serialization.json.buildJsonObject {
            putJsonArray(LOADED) { found.forEach { add(it.name) } }
            putJsonArray("tools") {
                found.forEach { addJsonObject { put("name", it.name); put("description", it.description) } }
            }
            put("message", "These tools are loaded; call them now.")
        }.toString()
    }

    private fun matches(deferred: List<ToolSpec>, query: String): List<ToolSpec> {
        val words = words(query).filter { it.length > 2 && it !in STOP_WORDS }
        if (words.isEmpty()) return emptyList()
        return deferred
            .map { spec ->
                val text = words("${spec.name} ${spec.description}")
                spec to words.count { word -> text.any { it.startsWith(word.trimEnd('s')) } }
            }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    /** The tool names loaded by [searchResults], the texts of the conversation's [SEARCH] tool turns. */
    fun loadedIn(searchResults: List<String>): Set<String> = searchResults.flatMapTo(mutableSetOf()) { text ->
        val loaded = runCatching { (Json.parseToJsonElement(text) as? JsonObject)?.get(LOADED) as? JsonArray }.getOrNull()
        loaded.orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    }

    private fun words(text: String) = text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter(String::isNotEmpty)

    /** Query words too common to say which tool is wanted. */
    private val STOP_WORDS = setOf("the", "and", "for", "what", "whats", "how", "can", "you", "please", "with", "from", "this", "that", "turn", "set", "get", "tool", "tools")

    private const val LOADED = "loaded"
}
