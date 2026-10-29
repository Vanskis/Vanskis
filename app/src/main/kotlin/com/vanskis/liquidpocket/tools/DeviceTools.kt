package com.vanskis.liquidpocket.tools

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.provider.AlarmClock
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put

private const val NO_ARGS = """{"type": "object", "properties": {}}"""

class TimeTool : JsonTool(
    "get_time",
    "Get the current date, time, weekday and time zone on the phone.",
    NO_ARGS,
    needsConfirmation = false,
) {
    override suspend fun run(args: JsonObject): ToolResult {
        val now = ZonedDateTime.now()
        return ToolResults.ok {
            put("date", now.toLocalDate().toString())
            put("weekday", now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH))
            put("time", now.format(DateTimeFormatter.ofPattern("HH:mm")))
            put("timezone", now.zone.id)
            put("iso", now.withNano(0).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
        }
    }
}

class BatteryTool(private val context: Context) : JsonTool(
    "get_battery",
    "Get the phone's battery level in percent and whether it is charging.",
    NO_ARGS,
    needsConfirmation = false,
) {
    override suspend fun run(args: JsonObject): ToolResult {
        val battery = context.getSystemService(BatteryManager::class.java)
        return ToolResults.ok {
            put("percent", battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
            put("charging", battery.isCharging)
        }
    }
}

/** Sets an alarm in the phone's clock app, without opening it. */
class AlarmTool(private val context: Context) : JsonTool(
    "set_alarm",
    "Set an alarm in the phone's clock app. Needs the user's approval.",
    """
    {"type": "object", "properties": {
      "time": {"type": "string", "description": "24-hour time, like 07:30"},
      "label": {"type": "string", "description": "Optional alarm label"}
    }, "required": ["time"]}
    """,
    needsConfirmation = true,
) {
    override fun check(args: JsonObject) =
        if (ToolTime.time(args.string("time")) == null) "time must be a time of day like 07:30" else null

    override fun describe(args: JsonObject): String {
        val time = ToolTime.time(args.string("time"))
        return "Set an alarm for ${time ?: args.string("time")}" + (args.string("label")?.let { " “$it”" } ?: "")
    }

    override suspend fun run(args: JsonObject): ToolResult {
        val time = ToolTime.time(args.string("time")) ?: return ToolResult(ToolResults.error("time is missing"))
        val label = args.string("label")
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, time.hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, time.minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (label != null) intent.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        return try {
            withContext(Dispatchers.Main) { context.startActivity(intent) }
            ToolResults.ok {
                put("status", "set")
                put("time", time.toString())
                label?.let { put("label", it) }
            }
        } catch (e: ActivityNotFoundException) {
            ToolResult(ToolResults.error("There is no clock app on this phone that can set alarms."))
        }
    }
}
