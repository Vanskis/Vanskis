package com.vanskis.liquidpocket.tools

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.graphics.ImageDecoder
import android.provider.BaseColumns
import android.provider.MediaStore
import android.provider.MediaStore.MediaColumns
import com.vanskis.liquidpocket.engine.Engine
import com.vanskis.liquidpocket.engine.ImageInput
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

private const val PHOTOS = "the photos"

/**
 * Compares when a photo was taken (ms) with [op], or when it was added (seconds) for one without the
 * date. Two arguments: [millis] and its seconds.
 */
private fun takenClause(op: String, millis: Long, args: MutableList<String>): String {
    args += listOf(millis.toString(), (millis / 1000).toString())
    return "(${MediaColumns.DATE_TAKEN} $op ? OR (${MediaColumns.DATE_TAKEN} IS NULL AND ${MediaColumns.DATE_ADDED} $op ?))"
}

/** Finds photos by their metadata; the model looks at candidates with [LookAtPhotosTool]. */
class SearchPhotosTool(private val context: Context, private val gate: PermissionGate) : JsonTool(
    "search_photos",
    "Search the phone's photos by date taken and by file or album name. Returns photo ids, newest first; " +
        "use look_at_photos to see them.",
    """
    {"type": "object", "properties": {
      "from": {"type": "string", "description": "Taken on or after, like 2026-10-01"},
      "to": {"type": "string", "description": "Taken on or before, like 2026-10-31"},
      "name": {"type": "string", "description": "Text in the file or album name, like Screenshots or Paris"},
      "limit": {"type": "integer", "description": "At most 10, default 8"}
    }}
    """,
    needsConfirmation = false,
) {
    override suspend fun run(args: JsonObject): ToolResult {
        if (!gate.ensure(Manifest.permission.READ_MEDIA_IMAGES)) return ToolResults.permissionDenied(PHOTOS)
        val zone = ZoneId.systemDefault()
        val toArg = args.string("to")
        val from = ToolTime.dateTime(args.string("from"), zone)
        val to = ToolTime.dateTime(toArg, zone)?.let { if (ToolTime.isDateOnly(toArg)) it.plusDays(1) else it }
        val name = args.string("name")
        val where = mutableListOf<String>()
        val whereArgs = mutableListOf<String>()
        from?.let { where += takenClause(">=", it.atZone(zone).toInstant().toEpochMilli(), whereArgs) }
        to?.let { where += takenClause("<", it.atZone(zone).toInstant().toEpochMilli(), whereArgs) }
        name?.let {
            where += "(${MediaColumns.DISPLAY_NAME} LIKE ? OR ${MediaColumns.BUCKET_DISPLAY_NAME} LIKE ?)"
            whereArgs += listOf("%$it%", "%$it%")
        }
        val limit = (args.int("limit") ?: 8).coerceIn(1, 10)
        val photos = context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(
                BaseColumns._ID,
                MediaColumns.DISPLAY_NAME,
                MediaColumns.BUCKET_DISPLAY_NAME,
                MediaColumns.DATE_TAKEN,
                MediaColumns.DATE_ADDED,
                MediaColumns.WIDTH,
                MediaColumns.HEIGHT,
            ),
            where.joinToString(" AND ").ifEmpty { null },
            whereArgs.toTypedArray(),
            "${MediaColumns.DATE_TAKEN} DESC, ${MediaColumns.DATE_ADDED} DESC",
        )?.use { c ->
            buildList {
                while (c.moveToNext() && size < limit) {
                    val takenMs = if (c.isNull(3)) c.getLong(4) * 1000 else c.getLong(3)
                    add(
                        Photo(
                            id = c.getLong(0),
                            name = c.getString(1).orEmpty(),
                            album = c.getString(2),
                            taken = LocalDateTime.ofInstant(Instant.ofEpochMilli(takenMs), zone),
                            width = c.getInt(5),
                            height = c.getInt(6),
                        ),
                    )
                }
            }
        }.orEmpty()
        return ToolResults.ok {
            putJsonArray("photos") {
                photos.forEach { p ->
                    addJsonObject {
                        put("id", p.id)
                        put("name", p.name)
                        p.album?.let { put("album", it) }
                        put("taken", ToolTime.iso(p.taken))
                        if (p.width > 0 && p.height > 0) put("size", "${p.width}x${p.height}")
                    }
                }
            }
        }
    }

    private class Photo(val id: Long, val name: String, val album: String?, val taken: LocalDateTime, val width: Int, val height: Int)
}

/**
 * Shows the model photos by id: each is decoded fitted within [PhotoPixels.MAX_SIDE] and handed
 * over as pixels. Only when the loaded model has vision.
 */
class LookAtPhotosTool(private val context: Context, private val gate: PermissionGate, private val engine: Engine) : JsonTool(
    "look_at_photos",
    "Look at up to $MAX_PHOTOS photos by the ids search_photos returned. The photos are shown to you right after.",
    """
    {"type": "object", "properties": {
      "ids": {"type": "array", "items": {"type": "integer"}, "description": "Photo ids from search_photos"}
    }, "required": ["ids"]}
    """,
    needsConfirmation = false,
) {
    override fun check(args: JsonObject) =
        if (args.strings("ids").none { it.toLongOrNull() != null }) "ids must be photo ids from search_photos" else null

    override suspend fun run(args: JsonObject): ToolResult {
        if (!engine.capabilities.value.vision) {
            return ToolResult(ToolResults.error("Vision is not available: the loaded model cannot see images."))
        }
        if (!gate.ensure(Manifest.permission.READ_MEDIA_IMAGES)) return ToolResults.permissionDenied(PHOTOS)
        val ids = args.strings("ids").mapNotNull { it.toLongOrNull() }.distinct().take(MAX_PHOTOS)
        val shown = mutableListOf<Long>()
        val missing = mutableListOf<Long>()
        val images = mutableListOf<ImageInput>()
        for (id in ids) {
            val image = runCatching { decode(id) }.getOrNull()
            if (image == null) missing += id else { shown += id; images += image }
        }
        val result = ToolResults.ok {
            putJsonArray("shown") { shown.forEach { add(it) } }
            if (missing.isNotEmpty()) putJsonArray("not_found") { missing.forEach { add(it) } }
            put("note", "The photos follow in this order.")
        }
        return ToolResult(result.text, images)
    }

    private fun decode(id: Long): ImageInput {
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val (w, h) = PhotoPixels.fitWithin(info.size.width, info.size.height)
            decoder.setTargetSize(w, h)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            return ImageInput(bitmap.width, bitmap.height, PhotoPixels.argbToRgb(pixels))
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val MAX_PHOTOS = 3
    }
}
