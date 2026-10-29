package com.vanskis.liquidpocket.tools

import com.vanskis.liquidpocket.engine.ImageInput
import com.vanskis.liquidpocket.engine.ToolSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * A phone action the model may call. [com.vanskis.liquidpocket.chat.ChatController] runs a call at
 * once unless [needsConfirmation], in which case the user approves it first from a card showing
 * [describe]. Plain Kotlin so the controller is tested with fakes; the phone's tools are in this package.
 */
interface Tool {
    val spec: ToolSpec

    /** True for anything that changes something on the phone (a write). */
    val needsConfirmation: Boolean

    /** An error message when the arguments cannot be used; checked before asking the user. */
    fun check(argumentsJson: String): String? = null

    /** What the call will do, in words, for the confirmation card. */
    fun describe(argumentsJson: String): String

    /** Runs the call. Called off the main thread; failures are reported in the result, not thrown. */
    suspend fun run(argumentsJson: String): ToolResult
}

/** [text] is the JSON the model reads; [images] are shown to it after the tool turns, on a user turn. */
class ToolResult(val text: String, val images: List<ImageInput> = emptyList())

/** A [Tool] whose arguments arrive parsed by [ToolArgs]. */
abstract class JsonTool(
    name: String,
    description: String,
    /** The arguments' JSON Schema object. */
    parametersJson: String,
    final override val needsConfirmation: Boolean,
) : Tool {
    final override val spec = ToolSpec(name, description, parametersJson.trimIndent())

    open fun check(args: JsonObject): String? = null
    open fun describe(args: JsonObject): String = spec.name
    abstract suspend fun run(args: JsonObject): ToolResult

    final override fun check(argumentsJson: String) = check(ToolArgs.parse(argumentsJson))
    final override fun describe(argumentsJson: String) = describe(ToolArgs.parse(argumentsJson))
    final override suspend fun run(argumentsJson: String) = run(ToolArgs.parse(argumentsJson))
}

/** The JSON results the model gets back. */
object ToolResults {
    fun ok(fields: JsonObjectBuilder.() -> Unit): ToolResult = ToolResult(buildJsonObject(fields).toString())

    fun error(message: String): String = buildJsonObject { put("error", message) }.toString()

    fun permissionDenied(what: String): ToolResult =
        ToolResult(error("The user has not allowed Liquid Pocket to access $what. Tell them it can be allowed in the phone's settings."))

    val DECLINED: String = buildJsonObject {
        put("status", "declined")
        put("message", "The user declined this action, so it was not done. Do not retry unless they ask.")
    }.toString()

    val CANCELLED: String = buildJsonObject {
        put("status", "cancelled")
        put("message", "The user stopped the reply before this ran.")
    }.toString()

    /** True for a result the user declined, so the transcript can say so. */
    fun isDeclined(text: String): Boolean = text == DECLINED
}

/**
 * Reads tool-call arguments defensively: the model may send the arguments object itself, or that
 * object as a JSON string, and numbers, booleans and lists as strings.
 */
object ToolArgs {
    private val EMPTY = JsonObject(emptyMap())

    fun parse(argumentsJson: String): JsonObject = when (val element = parseOrNull(argumentsJson)) {
        is JsonObject -> element
        is JsonPrimitive -> if (element.isString) parseOrNull(element.content) as? JsonObject ?: EMPTY else EMPTY
        else -> EMPTY
    }

    private fun parseOrNull(text: String) = runCatching { Json.parseToJsonElement(text) }.getOrNull()
}

/** A non-blank, trimmed string value (numbers and booleans given as text are fine). */
fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

fun JsonObject.int(key: String): Int? = string(key)?.toDoubleOrNull()?.toInt()

fun JsonObject.bool(key: String): Boolean? = when (string(key)?.lowercase()) {
    "true", "yes", "1" -> true
    "false", "no", "0" -> false
    else -> null
}

/** A list given as a JSON array, an array in a string, a comma-separated string or a single value. */
fun JsonObject.strings(key: String): List<String> {
    val value = this[key] ?: return emptyList()
    val array = value as? JsonArray
        ?: (value as? JsonPrimitive)?.contentOrNull?.let { text ->
            runCatching { Json.parseToJsonElement(text) as? JsonArray }.getOrNull()
                ?: JsonArray(text.split(",").map { JsonPrimitive(it) })
        }
        ?: return emptyList()
    return array.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
}
