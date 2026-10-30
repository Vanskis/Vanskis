package com.vanskis.liquidpocket.docs

import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Extracted documents kept as `<id>.json` files in [dir] (the app uses `filesDir/docs`), so a
 * conversation's document survives restarts without extracting it again. Ids are UUIDs. All calls block.
 */
class DocumentStore(private val dir: File) {

    /** Saves [doc] under a new id and returns the id. */
    fun save(doc: ExtractedDocument): String {
        val id = UUID.randomUUID().toString()
        dir.mkdirs()
        val tmp = File(dir, "$id.json.tmp")
        tmp.writeText(DocumentJson.encode(doc))
        if (!tmp.renameTo(file(id))) {
            tmp.delete()
            throw IOException("Could not save the document")
        }
        return id
    }

    /** The document saved under [id], or null when there is none (or [id] is not one of ours). */
    fun load(id: String): ExtractedDocument? {
        if (!isId(id)) return null
        val f = file(id)
        return if (f.isFile) DocumentJson.decode(f.readText()) else null
    }

    fun delete(id: String) {
        if (isId(id)) file(id).delete()
    }

    private fun file(id: String) = File(dir, "$id.json")

    /** Ids come back from the database; only a UUID can name a file, so no path can escape [dir]. */
    private fun isId(id: String) = runCatching { UUID.fromString(id).toString() == id.lowercase() }.getOrDefault(false)
}

/** `{"version": 1, "name": "...", "mimeType": "...", "ocrPages": 0, "pages": ["...", ...]}` */
object DocumentJson {
    private const val VERSION = 1

    fun encode(doc: ExtractedDocument): String = buildJsonObject {
        put("version", VERSION)
        put("name", doc.name)
        put("mimeType", doc.mimeType)
        put("ocrPages", doc.ocrPages)
        putJsonArray("pages") { doc.pages.forEach { add(it) } }
    }.toString()

    fun decode(json: String): ExtractedDocument {
        val o = Json.parseToJsonElement(json).jsonObject
        return ExtractedDocument(
            name = o.getValue("name").jsonPrimitive.content,
            mimeType = o.getValue("mimeType").jsonPrimitive.content,
            pages = o.getValue("pages").jsonArray.map { it.jsonPrimitive.content },
            ocrPages = o["ocrPages"]?.jsonPrimitive?.int ?: 0,
        )
    }
}
