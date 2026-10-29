package com.vanskis.liquidpocket.data

import com.vanskis.liquidpocket.chat.Attachment
import com.vanskis.liquidpocket.chat.AttachmentKind
import com.vanskis.liquidpocket.engine.ToolCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The JSON columns of a saved message, through the JsonElement API (no serialization plugin).
 * An empty list is stored as null.
 */
object MessageJson {
    /** `[{"path": "...", "kind": "IMAGE"|"AUDIO"}, ...]` */
    fun encodeAttachments(attachments: List<Attachment>): String? =
        if (attachments.isEmpty()) null else buildJsonArray {
            attachments.forEach { add(buildJsonObject { put("path", it.path); put("kind", it.kind.name) }) }
        }.toString()

    fun decodeAttachments(json: String?): List<Attachment> =
        objects(json).map { Attachment(it.string("path"), AttachmentKind.valueOf(it.string("kind"))) }

    /** `[{"name": "...", "arguments": "<JSON object as a string>"}, ...]`, keeping the model's text as is. */
    fun encodeToolCalls(calls: List<ToolCall>): String? =
        if (calls.isEmpty()) null else buildJsonArray {
            calls.forEach { add(buildJsonObject { put("name", it.name); put("arguments", it.argumentsJson) }) }
        }.toString()

    fun decodeToolCalls(json: String?): List<ToolCall> =
        objects(json).map { ToolCall(it.string("name"), it.string("arguments")) }

    private fun objects(json: String?): List<JsonObject> =
        if (json.isNullOrEmpty()) emptyList() else Json.parseToJsonElement(json).jsonArray.map { it.jsonObject }

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content
}
