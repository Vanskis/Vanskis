package com.vanskis.liquidpocket.data

import com.vanskis.liquidpocket.chat.Attachment
import com.vanskis.liquidpocket.chat.AttachmentKind
import com.vanskis.liquidpocket.chat.Skill
import com.vanskis.liquidpocket.chat.Skills
import com.vanskis.liquidpocket.engine.ToolCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The JSON columns of a saved message, through the JsonElement API (no serialization plugin).
 * An empty list is stored as null.
 */
object MessageJson {
    /** `[{"path": "...", "kind": "IMAGE"|"AUDIO"|"DOCUMENT", "name": "..."}, ...]`, `name` left out when null. */
    fun encodeAttachments(attachments: List<Attachment>): String? =
        if (attachments.isEmpty()) null else buildJsonArray {
            attachments.forEach { a ->
                add(buildJsonObject { put("path", a.path); put("kind", a.kind.name); a.name?.let { put("name", it) } })
            }
        }.toString()

    fun decodeAttachments(json: String?): List<Attachment> =
        objects(json).map {
            Attachment(it.string("path"), AttachmentKind.valueOf(it.string("kind")), it["name"]?.jsonPrimitive?.content)
        }

    /** `[{"name": "...", "arguments": "<JSON object as a string>"}, ...]`, keeping the model's text as is. */
    fun encodeToolCalls(calls: List<ToolCall>): String? =
        if (calls.isEmpty()) null else buildJsonArray {
            calls.forEach { add(buildJsonObject { put("name", it.name); put("arguments", it.argumentsJson) }) }
        }.toString()

    fun decodeToolCalls(json: String?): List<ToolCall> =
        objects(json).map { ToolCall(it.string("name"), it.string("arguments")) }

    /**
     * `{"thinking": "...", "thinkingMillis": 1234, "thinkingTokens": 340, "summary": true, "caption": "...", "context": "..."}`, a key left out when null (or
     * false, for `summary`). No extras is stored as null.
     */
    fun encodeExtras(extras: MessageExtras): String? =
        if (extras.isEmpty) null else buildJsonObject {
            extras.thinking?.let { put("thinking", it) }
            extras.thinkingMillis?.let { put("thinkingMillis", it) }
            if (extras.summary) put("summary", true)
            extras.caption?.let { put("caption", it) }
            extras.thinkingTokens?.let { put("thinkingTokens", it) }
            extras.context?.let { put("context", it) }
            extras.skill?.let { put("skill", Skills.encodeOne(it)) }
        }.toString()

    /** Malformed or unexpected JSON gives no extras; this never throws. */
    fun decodeExtras(json: String?): MessageExtras {
        if (json.isNullOrEmpty()) return MessageExtras()
        return try {
            val obj = Json.parseToJsonElement(json).jsonObject
            MessageExtras(
                thinking = obj["thinking"]?.jsonPrimitive?.takeIf { it.isString }?.content,
                thinkingMillis = obj["thinkingMillis"]?.jsonPrimitive?.longOrNull,
                summary = obj["summary"]?.jsonPrimitive?.booleanOrNull == true,
                caption = obj["caption"]?.jsonPrimitive?.takeIf { it.isString }?.content,
                thinkingTokens = obj["thinkingTokens"]?.jsonPrimitive?.intOrNull,
                context = obj["context"]?.jsonPrimitive?.takeIf { it.isString }?.content,
                skill = Skills.decodeOne(obj["skill"] as? JsonObject),
            )
        } catch (_: Exception) {
            MessageExtras()
        }
    }

    private fun objects(json: String?): List<JsonObject> =
        if (json.isNullOrEmpty()) emptyList() else Json.parseToJsonElement(json).jsonArray.map { it.jsonObject }

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content
}

/**
 * What a saved message keeps beyond its own columns: the reasoning of an assistant reply and how
 * long it took, whether the message summarizes the turns before it ([com.vanskis.liquidpocket.chat.Turn.summary]),
 * and the hidden caption of a user message's photos ([com.vanskis.liquidpocket.chat.Turn.caption]).
 */
data class MessageExtras(
    val thinking: String? = null,
    val thinkingMillis: Long? = null,
    val summary: Boolean = false,
    val caption: String? = null,
    val thinkingTokens: Int? = null,
    val context: String? = null,
    val skill: Skill? = null,
) {
    val isEmpty: Boolean get() =
        thinking.isNullOrEmpty() && thinkingMillis == null && !summary && caption.isNullOrEmpty() && thinkingTokens == null &&
            context.isNullOrEmpty() && skill == null
}
