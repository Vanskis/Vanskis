package com.vanskis.liquidpocket.chat

import android.content.Context
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import com.vanskis.liquidpocket.engine.EngineState
import java.time.ZonedDateTime

/** Reads the [PhoneState] a message is sent with: clock, battery, network, device and app state. Never location. */
class PhoneStateProbe(context: Context, private val engine: () -> EngineState) {
    private val app = context.applicationContext

    fun read(): PhoneState {
        val battery = app.getSystemService(BatteryManager::class.java)
        val config = app.resources.configuration
        val ready = engine() as? EngineState.Ready
        return PhoneState(
            now = ZonedDateTime.now(),
            batteryPercent = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 },
            charging = battery?.isCharging == true,
            network = network(),
            device = if (Build.MODEL.startsWith(Build.MANUFACTURER, ignoreCase = true)) Build.MODEL
            else "${Build.MANUFACTURER.replaceFirstChar(Char::uppercaseChar)} ${Build.MODEL}",
            darkMode = config.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES,
            language = config.locales[0].toLanguageTag(),
            accelerator = ready?.accelerator?.name.orEmpty(),
            model = ready?.modelPath?.substringAfterLast('/')?.removeSuffix(".gguf"),
        )
    }

    fun line(): String = EnvironmentAnchor.line(read())

    private fun network(): String? = try {
        val manager = app.getSystemService(ConnectivityManager::class.java) ?: return null
        val caps = manager.activeNetwork?.let(manager::getNetworkCapabilities)
        when {
            caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> "offline"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile data"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "online"
        }
    } catch (_: SecurityException) {
        null
    }
}
