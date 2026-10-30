package com.vanskis.liquidpocket.tools

import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.Settings
import android.view.KeyEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put

/** Starts [intent] from the app as a new task; false when no app on the phone handles it. */
private suspend fun Context.launch(intent: Intent): Boolean = try {
    withContext(Dispatchers.Main) { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    true
} catch (e: ActivityNotFoundException) {
    false
}

private fun percent(args: JsonObject) = args.int("percent")?.takeIf { it in 0..100 }

class FlashlightTool(private val context: Context) : JsonTool(
    "set_flashlight",
    "Turn the phone's flashlight (torch) on or off.",
    """{"type": "object", "properties": {"on": {"type": "boolean", "description": "true to turn it on"}}, "required": ["on"]}""",
    needsConfirmation = false,
) {
    override fun check(args: JsonObject) = if (args.bool("on") == null) "on must be true or false" else null

    override suspend fun run(args: JsonObject): ToolResult {
        val on = args.bool("on") ?: return ToolResult(ToolResults.error("on must be true or false"))
        val cameras = context.getSystemService(CameraManager::class.java)
        val torch = cameras.cameraIdList.firstOrNull {
            cameras.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return ToolResult(ToolResults.error("This phone has no flashlight."))
        cameras.setTorchMode(torch, on)
        return ToolResults.ok { put("flashlight", if (on) "on" else "off") }
    }
}

class VolumeTool(private val context: Context) : JsonTool(
    "set_volume",
    "Set the volume of media, the ringtone, alarms or notifications, in percent.",
    """
    {"type": "object", "properties": {
      "percent": {"type": "integer", "description": "0 to 100"},
      "stream": {"type": "string", "enum": ["media", "ring", "alarm", "notification"], "description": "Which volume (default media)"}
    }, "required": ["percent"]}
    """,
    needsConfirmation = false,
) {
    private fun stream(args: JsonObject) = when (args.string("stream")?.lowercase()) {
        null, "media", "music" -> AudioManager.STREAM_MUSIC
        "ring", "ringtone", "ringer" -> AudioManager.STREAM_RING
        "alarm" -> AudioManager.STREAM_ALARM
        "notification", "notifications" -> AudioManager.STREAM_NOTIFICATION
        else -> null
    }

    override fun check(args: JsonObject) = when {
        percent(args) == null -> "percent must be 0 to 100"
        stream(args) == null -> "stream must be media, ring, alarm or notification"
        else -> null
    }

    override suspend fun run(args: JsonObject): ToolResult {
        val stream = stream(args) ?: return ToolResult(ToolResults.error("stream must be media, ring, alarm or notification"))
        val percent = percent(args) ?: return ToolResult(ToolResults.error("percent must be 0 to 100"))
        val audio = context.getSystemService(AudioManager::class.java)
        val max = audio.getStreamMaxVolume(stream)
        return try {
            audio.setStreamVolume(stream, Math.round(max * percent / 100f), 0)
            ToolResults.ok {
                put("stream", args.string("stream") ?: "media")
                put("percent", audio.getStreamVolume(stream) * 100 / max.coerceAtLeast(1))
            }
        } catch (e: SecurityException) {
            ToolResult(ToolResults.error("Android does not let apps change this volume while Do Not Disturb is on."))
        }
    }
}

class RingerTool(private val context: Context) : JsonTool(
    "set_ringer_mode",
    "Set the phone's ringer to ring, vibrate or silent.",
    """{"type": "object", "properties": {"mode": {"type": "string", "enum": ["normal", "vibrate", "silent"]}}, "required": ["mode"]}""",
    needsConfirmation = false,
) {
    private fun mode(args: JsonObject) = when (args.string("mode")?.lowercase()) {
        "normal", "ring", "sound" -> AudioManager.RINGER_MODE_NORMAL
        "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
        "silent", "mute" -> AudioManager.RINGER_MODE_SILENT
        else -> null
    }

    override fun check(args: JsonObject) = if (mode(args) == null) "mode must be normal, vibrate or silent" else null

    override suspend fun run(args: JsonObject): ToolResult {
        val mode = mode(args) ?: return ToolResult(ToolResults.error("mode must be normal, vibrate or silent"))
        return try {
            context.getSystemService(AudioManager::class.java).ringerMode = mode
            ToolResults.ok { put("ringer", args.string("mode")!!.lowercase()) }
        } catch (e: SecurityException) {
            context.launch(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            ToolResult(ToolResults.error(DND_ACCESS))
        }
    }
}

private const val DND_ACCESS =
    "Liquid Pocket needs Do Not Disturb access for this. The settings page for it is now open: tell the user to allow Liquid Pocket there, then ask again."

class DoNotDisturbTool(private val context: Context) : JsonTool(
    "set_do_not_disturb",
    "Turn Do Not Disturb on (only priority interruptions) or off. Needs the user's approval.",
    """{"type": "object", "properties": {"on": {"type": "boolean", "description": "true to turn it on"}}, "required": ["on"]}""",
    needsConfirmation = true,
) {
    override fun check(args: JsonObject) = if (args.bool("on") == null) "on must be true or false" else null

    override fun describe(args: JsonObject) = if (args.bool("on") == true) "Turn on Do Not Disturb" else "Turn off Do Not Disturb"

    override suspend fun run(args: JsonObject): ToolResult {
        val on = args.bool("on") ?: return ToolResult(ToolResults.error("on must be true or false"))
        val notifications = context.getSystemService(NotificationManager::class.java)
        if (!notifications.isNotificationPolicyAccessGranted) {
            context.launch(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            return ToolResult(ToolResults.error(DND_ACCESS))
        }
        notifications.setInterruptionFilter(if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
        return ToolResults.ok { put("do_not_disturb", if (on) "on" else "off") }
    }
}

class BrightnessTool(private val context: Context) : JsonTool(
    "set_brightness",
    "Set the screen brightness in percent, or turn automatic brightness on.",
    """
    {"type": "object", "properties": {
      "percent": {"type": "integer", "description": "0 to 100; turns automatic brightness off"},
      "auto": {"type": "boolean", "description": "true for automatic brightness"}
    }}
    """,
    needsConfirmation = false,
) {
    override fun check(args: JsonObject) =
        if (args.bool("auto") != true && percent(args) == null) "give percent (0 to 100) or auto: true" else null

    override suspend fun run(args: JsonObject): ToolResult {
        if (!Settings.System.canWrite(context)) {
            context.launch(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}")))
            return ToolResult(
                ToolResults.error("Liquid Pocket needs permission to change system settings. That page is now open: tell the user to allow it, then ask again."),
            )
        }
        val resolver = context.contentResolver
        if (args.bool("auto") == true) {
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
            return ToolResults.ok { put("brightness", "automatic") }
        }
        val percent = percent(args) ?: return ToolResult(ToolResults.error("percent must be 0 to 100"))
        Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, Math.round(percent * 2.55f))
        return ToolResults.ok { put("brightness", "$percent %") }
    }
}

class MediaTool(private val context: Context) : JsonTool(
    "control_media",
    "Play, pause or skip whatever music or video is playing on the phone.",
    """{"type": "object", "properties": {"action": {"type": "string", "enum": ["play", "pause", "play_pause", "next", "previous", "stop"]}}, "required": ["action"]}""",
    needsConfirmation = false,
) {
    private fun key(args: JsonObject) = when (args.string("action")?.lowercase()) {
        "play", "resume" -> KeyEvent.KEYCODE_MEDIA_PLAY
        "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
        "play_pause", "toggle" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        "next", "skip" -> KeyEvent.KEYCODE_MEDIA_NEXT
        "previous", "back" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
        "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
        else -> null
    }

    override fun check(args: JsonObject) = if (key(args) == null) "action must be play, pause, play_pause, next, previous or stop" else null

    override suspend fun run(args: JsonObject): ToolResult {
        val key = key(args) ?: return ToolResult(ToolResults.error("unknown action"))
        val audio = context.getSystemService(AudioManager::class.java)
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
        return ToolResults.ok {
            put("sent", args.string("action")!!.lowercase())
            put("playing", audio.isMusicActive)
        }
    }
}

class OpenAppTool(private val context: Context) : JsonTool(
    "open_app",
    "Open an app on the phone by its name. Needs the user's approval.",
    """{"type": "object", "properties": {"name": {"type": "string", "description": "The app's name as on the home screen"}}, "required": ["name"]}""",
    needsConfirmation = true,
) {
    override fun check(args: JsonObject) = if (args.string("name") == null) "name is missing" else null

    override fun describe(args: JsonObject) = "Open ${args.string("name")}"

    override suspend fun run(args: JsonObject): ToolResult {
        val name = args.string("name")?.lowercase() ?: return ToolResult(ToolResults.error("name is missing"))
        val pm = context.packageManager
        val apps = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            PackageManager.ResolveInfoFlags.of(0),
        ).map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
        val match = apps.firstOrNull { it.first.lowercase() == name }
            ?: apps.filter { name in it.first.lowercase() }.minByOrNull { it.first.length }
            ?: return ToolResult(ToolResults.error("No app called “${args.string("name")}” is installed."))
        val intent = pm.getLaunchIntentForPackage(match.second)
            ?: return ToolResult(ToolResults.error("${match.first} cannot be opened."))
        return if (context.launch(intent)) ToolResults.ok { put("opened", match.first) }
        else ToolResult(ToolResults.error("${match.first} cannot be opened."))
    }
}

class OpenSettingsTool(private val context: Context) : JsonTool(
    "open_settings",
    "Open a page of the phone's settings, like Wi-Fi or Bluetooth, for the user to change.",
    """{"type": "object", "properties": {"page": {"type": "string", "enum": [${PAGES.keys.joinToString { "\"$it\"" }}]}}, "required": ["page"]}""",
    needsConfirmation = false,
) {
    override fun check(args: JsonObject) = if (args.string("page")?.lowercase() !in PAGES) "page must be one of ${PAGES.keys.joinToString()}" else null

    override suspend fun run(args: JsonObject): ToolResult {
        val page = args.string("page")?.lowercase() ?: return ToolResult(ToolResults.error("page is missing"))
        val action = PAGES[page] ?: return ToolResult(ToolResults.error("unknown page"))
        return if (context.launch(Intent(action))) ToolResults.ok { put("opened", "$page settings") }
        else ToolResult(ToolResults.error("This phone has no $page settings page."))
    }

    private companion object {
        val PAGES = linkedMapOf(
            "main" to Settings.ACTION_SETTINGS,
            "wifi" to Settings.ACTION_WIFI_SETTINGS,
            "bluetooth" to Settings.ACTION_BLUETOOTH_SETTINGS,
            "mobile_data" to Settings.ACTION_DATA_ROAMING_SETTINGS,
            "airplane_mode" to Settings.ACTION_AIRPLANE_MODE_SETTINGS,
            "display" to Settings.ACTION_DISPLAY_SETTINGS,
            "sound" to Settings.ACTION_SOUND_SETTINGS,
            "battery" to Intent.ACTION_POWER_USAGE_SUMMARY,
            "location" to Settings.ACTION_LOCATION_SOURCE_SETTINGS,
            "apps" to Settings.ACTION_APPLICATION_SETTINGS,
            "notifications" to Settings.ACTION_ALL_APPS_NOTIFICATION_SETTINGS,
            "date_time" to Settings.ACTION_DATE_SETTINGS,
            "language" to Settings.ACTION_LOCALE_SETTINGS,
            "accessibility" to Settings.ACTION_ACCESSIBILITY_SETTINGS,
            "security" to Settings.ACTION_SECURITY_SETTINGS,
            "storage" to Settings.ACTION_INTERNAL_STORAGE_SETTINGS,
        )
    }
}

class TimerTool(private val context: Context) : JsonTool(
    "set_timer",
    "Start a countdown timer in the phone's clock app.",
    """
    {"type": "object", "properties": {
      "seconds": {"type": "integer", "description": "Length in seconds, 1 to 86400"},
      "label": {"type": "string", "description": "Optional timer label"}
    }, "required": ["seconds"]}
    """,
    needsConfirmation = false,
) {
    private fun seconds(args: JsonObject) = args.int("seconds")?.takeIf { it in 1..86_400 }

    override fun check(args: JsonObject) = if (seconds(args) == null) "seconds must be 1 to 86400" else null

    override suspend fun run(args: JsonObject): ToolResult {
        val seconds = seconds(args) ?: return ToolResult(ToolResults.error("seconds must be 1 to 86400"))
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        args.string("label")?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        return if (context.launch(intent)) ToolResults.ok { put("status", "started"); put("seconds", seconds) }
        else ToolResult(ToolResults.error("There is no clock app on this phone that can set timers."))
    }
}

class ShareTool(private val context: Context) : JsonTool(
    "share_text",
    "Open the share sheet with some text, to send it by any app (messages, email, notes). Needs the user's approval.",
    """
    {"type": "object", "properties": {
      "text": {"type": "string", "description": "What to share"},
      "subject": {"type": "string", "description": "Optional subject, for email"}
    }, "required": ["text"]}
    """,
    needsConfirmation = true,
) {
    override fun check(args: JsonObject) = if (args.string("text") == null) "text is missing" else null

    override fun describe(args: JsonObject) = "Share “${args.string("text")?.take(200)}”"

    override suspend fun run(args: JsonObject): ToolResult {
        val text = args.string("text") ?: return ToolResult(ToolResults.error("text is missing"))
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        args.string("subject")?.let { send.putExtra(Intent.EXTRA_SUBJECT, it) }
        return if (context.launch(Intent.createChooser(send, null))) ToolResults.ok { put("status", "share sheet shown") }
        else ToolResult(ToolResults.error("Nothing on this phone can share text."))
    }
}
