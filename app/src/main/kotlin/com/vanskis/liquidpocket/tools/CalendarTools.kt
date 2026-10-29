package com.vanskis.liquidpocket.tools

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import android.provider.CalendarContract.Reminders
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

private const val CALENDAR = "the calendar"

/** Events in a time range, from every calendar on the phone. */
class CalendarLookupTool(private val context: Context, private val gate: PermissionGate) : JsonTool(
    "calendar_lookup",
    "List calendar events between two dates or times, optionally only those whose title contains some text.",
    """
    {"type": "object", "properties": {
      "from": {"type": "string", "description": "Start, like 2026-10-30 or 2026-10-30T09:00. Defaults to now"},
      "to": {"type": "string", "description": "End, like 2026-10-31. Defaults to a day after from"},
      "query": {"type": "string", "description": "Optional text the title contains"}
    }}
    """,
    needsConfirmation = false,
) {
    override suspend fun run(args: JsonObject): ToolResult {
        if (!gate.ensure(Manifest.permission.READ_CALENDAR)) return ToolResults.permissionDenied(CALENDAR)
        val zone = ZoneId.systemDefault()
        val from = ToolTime.dateTime(args.string("from"), zone) ?: LocalDateTime.now()
        val toArg = args.string("to")
        // A bare end date includes that whole day.
        val to = ToolTime.dateTime(toArg, zone)?.let { if (ToolTime.isDateOnly(toArg)) it.plusDays(1) else it }
            ?.takeIf { it.isAfter(from) } ?: from.plusDays(1)
        val uri = Instances.CONTENT_URI.buildUpon()
            .also { ContentUris.appendId(it, from.atZone(zone).toInstant().toEpochMilli()) }
            .also { ContentUris.appendId(it, to.atZone(zone).toInstant().toEpochMilli()) }
            .build()
        val query = args.string("query")
        val projection = arrayOf(Instances.TITLE, Instances.BEGIN, Instances.END, Instances.ALL_DAY, Instances.EVENT_LOCATION)
        val events = context.contentResolver.query(
            uri,
            projection,
            query?.let { "${Instances.TITLE} LIKE ?" },
            query?.let { arrayOf("%$it%") },
            "${Instances.BEGIN} ASC",
        )?.use { c ->
            buildList {
                while (c.moveToNext() && size < MAX_EVENTS) {
                    val allDay = c.getInt(3) == 1
                    // All-day events are stored at UTC midnight.
                    val eventZone = if (allDay) ZoneOffset.UTC else zone
                    add(
                        Event(
                            title = c.getString(0).orEmpty(),
                            begin = LocalDateTime.ofInstant(Instant.ofEpochMilli(c.getLong(1)), eventZone),
                            end = LocalDateTime.ofInstant(Instant.ofEpochMilli(c.getLong(2)), eventZone),
                            allDay = allDay,
                            location = c.getString(4)?.takeIf { it.isNotBlank() },
                        ),
                    )
                }
            }
        }.orEmpty()
        return ToolResults.ok {
            put("from", ToolTime.iso(from))
            put("to", ToolTime.iso(to))
            putJsonArray("events") {
                events.forEach { e ->
                    addJsonObject {
                        put("title", e.title)
                        if (e.allDay) put("date", e.begin.toLocalDate().toString()) else put("start", ToolTime.iso(e.begin))
                        if (!e.allDay) put("end", ToolTime.iso(e.end))
                        put("all_day", e.allDay)
                        e.location?.let { put("location", it) }
                    }
                }
            }
        }
    }

    private class Event(val title: String, val begin: LocalDateTime, val end: LocalDateTime, val allDay: Boolean, val location: String?)

    private companion object {
        const val MAX_EVENTS = 25
    }
}

/** Adds an event to the phone's primary calendar. */
class CreateEventTool(private val context: Context, private val gate: PermissionGate) : JsonTool(
    "create_calendar_event",
    "Add an event to the user's calendar. Needs the user's approval.",
    """
    {"type": "object", "properties": {
      "title": {"type": "string"},
      "start": {"type": "string", "description": "Local start, like 2026-10-30T10:00, or a date for an all-day event"},
      "end": {"type": "string", "description": "Local end. Defaults to an hour after start"},
      "location": {"type": "string"},
      "description": {"type": "string"}
    }, "required": ["title", "start"]}
    """,
    needsConfirmation = true,
) {
    override fun check(args: JsonObject) = when {
        args.string("title") == null -> "title is missing"
        ToolTime.dateTime(args.string("start"), ZoneId.systemDefault()) == null -> "start must be a date-time like 2026-10-30T10:00"
        else -> null
    }

    override fun describe(args: JsonObject): String {
        val zone = ZoneId.systemDefault()
        val start = ToolTime.dateTime(args.string("start"), zone) ?: return "Add “${args.string("title")}” to your calendar"
        val allDay = ToolTime.isDateOnly(args.string("start"))
        val end = endOf(args, start)
        val time = when {
            allDay -> "${ToolTime.describeDate(start.toLocalDate())} (all day)"
            end.toLocalDate() == start.toLocalDate() -> "${ToolTime.describe(start)}–${end.toLocalTime()}"
            else -> "${ToolTime.describe(start)} – ${ToolTime.describe(end)}"
        }
        return "Add “${args.string("title")}” to your calendar on $time" +
            (args.string("location")?.let { " at $it" } ?: "") +
            (args.string("description")?.trim()?.takeIf { it.isNotEmpty() }?.let { ", with the note “${shortened(it)}”" } ?: "")
    }

    /** [text] cut to [MAX_DESCRIBED_CHARS] for the card, ending in "…" when cut. */
    private fun shortened(text: String) =
        if (text.length <= MAX_DESCRIBED_CHARS) text else text.take(MAX_DESCRIBED_CHARS).trimEnd() + "…"

    override suspend fun run(args: JsonObject): ToolResult {
        if (!gate.ensure(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)) {
            return ToolResults.permissionDenied(CALENDAR)
        }
        val zone = ZoneId.systemDefault()
        val start = ToolTime.dateTime(args.string("start"), zone) ?: return ToolResult(ToolResults.error("start is missing"))
        val id = insertEvent(
            context,
            title = args.string("title").orEmpty(),
            start = start,
            end = endOf(args, start),
            allDay = ToolTime.isDateOnly(args.string("start")),
            location = args.string("location"),
            description = args.string("description"),
        ) ?: return ToolResult(ToolResults.error("There is no calendar on this phone that events can be added to."))
        return ToolResults.ok {
            put("status", "created")
            put("event_id", id)
            put("start", ToolTime.iso(start))
        }
    }

    private fun endOf(args: JsonObject, start: LocalDateTime): LocalDateTime {
        if (ToolTime.isDateOnly(args.string("start"))) return start.plusDays(1)
        return ToolTime.dateTime(args.string("end"), ZoneId.systemDefault())?.takeIf { it.isAfter(start) } ?: start.plusHours(1)
    }

    private companion object {
        const val MAX_DESCRIBED_CHARS = 200
    }
}

/** A reminder: a short calendar event that alerts at its start. */
class CreateReminderTool(private val context: Context, private val gate: PermissionGate) : JsonTool(
    "create_reminder",
    "Remind the user of something at a time (a calendar event with an alert). Needs the user's approval.",
    """
    {"type": "object", "properties": {
      "text": {"type": "string", "description": "What to remind the user of"},
      "time": {"type": "string", "description": "Local time to remind, like 2026-10-30T18:00"}
    }, "required": ["text", "time"]}
    """,
    needsConfirmation = true,
) {
    override fun check(args: JsonObject) = when {
        args.string("text") == null -> "text is missing"
        ToolTime.dateTime(args.string("time"), ZoneId.systemDefault()) == null -> "time must be a date-time like 2026-10-30T18:00"
        else -> null
    }

    override fun describe(args: JsonObject): String {
        val time = ToolTime.dateTime(args.string("time"), ZoneId.systemDefault())
        return "Remind you “${args.string("text")}”" + (time?.let { " on ${ToolTime.describe(it)}" } ?: "")
    }

    override suspend fun run(args: JsonObject): ToolResult {
        if (!gate.ensure(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)) {
            return ToolResults.permissionDenied(CALENDAR)
        }
        val time = ToolTime.dateTime(args.string("time"), ZoneId.systemDefault())
            ?: return ToolResult(ToolResults.error("time is missing"))
        val id = insertEvent(
            context,
            title = args.string("text").orEmpty(),
            start = time,
            end = time.plusMinutes(15),
            allDay = false,
            location = null,
            description = null,
        )
            ?: return ToolResult(ToolResults.error("There is no calendar on this phone that reminders can be added to."))
        context.contentResolver.insert(
            Reminders.CONTENT_URI,
            ContentValues().apply {
                put(Reminders.EVENT_ID, id)
                put(Reminders.MINUTES, 0)
                put(Reminders.METHOD, Reminders.METHOD_ALERT)
            },
        )
        return ToolResults.ok {
            put("status", "created")
            put("time", ToolTime.iso(time))
        }
    }
}

/** Inserts an event into the primary writable calendar; null when there is none. */
private fun insertEvent(
    context: Context,
    title: String,
    start: LocalDateTime,
    end: LocalDateTime,
    allDay: Boolean,
    location: String?,
    description: String?,
): Long? {
    val resolver = context.contentResolver
    val calendarId = resolver.query(
        Calendars.CONTENT_URI,
        arrayOf(Calendars._ID),
        "${Calendars.VISIBLE} = 1 AND ${Calendars.CALENDAR_ACCESS_LEVEL} >= ${Calendars.CAL_ACCESS_CONTRIBUTOR}",
        null,
        "${Calendars.IS_PRIMARY} DESC, ${Calendars._ID} ASC",
    )?.use { if (it.moveToFirst()) it.getLong(0) else null } ?: return null
    // All-day events must be at UTC midnight in the UTC time zone.
    val zone = if (allDay) ZoneOffset.UTC else ZoneId.systemDefault()
    val values = ContentValues().apply {
        put(Events.CALENDAR_ID, calendarId)
        put(Events.TITLE, title)
        put(Events.DTSTART, start.atZone(zone).toInstant().toEpochMilli())
        put(Events.DTEND, end.atZone(zone).toInstant().toEpochMilli())
        put(Events.EVENT_TIMEZONE, if (allDay) "UTC" else zone.id)
        put(Events.ALL_DAY, if (allDay) 1 else 0)
        location?.let { put(Events.EVENT_LOCATION, it) }
        description?.let { put(Events.DESCRIPTION, it) }
    }
    val uri = resolver.insert(Events.CONTENT_URI, values) ?: return null
    return ContentUris.parseId(uri)
}
