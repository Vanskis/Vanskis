package com.vanskis.liquidpocket.chat

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The phone's state when a message was sent, as [EnvironmentAnchor] tells it to the model. No location. */
data class PhoneState(
    val now: ZonedDateTime,
    val batteryPercent: Int? = null,
    val charging: Boolean = false,
    /** "Wi-Fi", "mobile data", "Ethernet" or "offline"; null when unknown. */
    val network: String? = null,
    val device: String = "",
    val darkMode: Boolean = false,
    /** A BCP 47 tag, like "en-US". */
    val language: String = "",
    /** "NPU" or "CPU". */
    val accelerator: String = "",
    val model: String? = null,
)

/**
 * A user turn's anchor: one line on the date, time and phone state when it was sent, saved with the
 * turn ([Turn.context]) and sent in front of its text. Earlier turns keep theirs, so the model can
 * tell how much time passed, and the start of the conversation never changes (the engine keeps its
 * cached prefix).
 */
object EnvironmentAnchor {
    private val DATE = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm", Locale.ENGLISH)

    fun line(state: PhoneState): String {
        val zone = state.now.zone.id
        val offset = state.now.offset.id.let { if (it == "Z") "+00:00" else it }
        val parts = buildList {
            add("${DATE.format(state.now)} $zone (UTC$offset)")
            state.batteryPercent?.let { add("battery $it%" + if (state.charging) ", charging" else "") }
            state.network?.let(::add)
            state.device.takeIf(String::isNotBlank)?.let(::add)
            add(if (state.darkMode) "dark mode" else "light mode")
            state.language.takeIf(String::isNotBlank)?.let { add("language $it") }
            state.model?.takeIf(String::isNotBlank)?.let { model ->
                add("running $model" + (state.accelerator.takeIf(String::isNotBlank)?.let { " on the $it" } ?: ""))
            }
        }
        return "[Sent ${parts.joinToString("; ")}]"
    }

    /** What the model is sent for a user turn's [text] with its [context] line. */
    fun prefixed(context: String?, text: String): String =
        if (context.isNullOrBlank()) text else if (text.isBlank()) context else "$context\n$text"
}
