package com.vanskis.liquidpocket.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Reads look_at_photos results back for the transcript, which shows the photos the model saw. */
object PhotoResults {
    const val LOOK_TOOL = "look_at_photos"

    /** The MediaStore content URI of the photo with [id]. */
    fun uri(id: Long) = "content://media/external/images/media/$id"

    /** The ids in a look_at_photos result's "shown", in order; empty for anything else. */
    fun shownIds(result: String): List<Long> {
        val json = runCatching { Json.parseToJsonElement(result) }.getOrNull() as? JsonObject ?: return emptyList()
        val shown = json["shown"] as? JsonArray ?: return emptyList()
        return shown.mapNotNull { (it as? JsonPrimitive)?.longOrNull }
    }
}
