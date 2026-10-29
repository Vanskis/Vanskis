package com.vanskis.liquidpocket.tools

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Dates and times as the model writes them ("2026-10-30T10:00", "2026-10-30 10:00", "2026-10-30"). */
object ToolTime {
    /** A local date-time; a bare date is its start. One with an offset is moved to [zone]. */
    fun dateTime(text: String?, zone: ZoneId): LocalDateTime? {
        val s = text?.trim()?.replace(' ', 'T') ?: return null
        runCatching { return LocalDateTime.parse(s) }
        runCatching { return OffsetDateTime.parse(s).atZoneSameInstant(zone).toLocalDateTime() }
        runCatching { return LocalDate.parse(s).atStartOfDay() }
        return null
    }

    /** True when [text] is a date with no time of day. */
    fun isDateOnly(text: String?): Boolean = text != null && runCatching { LocalDate.parse(text.trim()) }.isSuccess

    /** "07:30", "7:30", "19:05:00", or a date-time whose time is taken. */
    fun time(text: String?): LocalTime? {
        val s = text?.trim()?.uppercase(Locale.ROOT) ?: return null
        runCatching { return LocalTime.parse(if (s.length == 4 && s[1] == ':') "0$s" else s) }
        runCatching { return LocalTime.parse(s, DateTimeFormatter.ofPattern("h:mm a", Locale.US)) }
        return dateTime(text, ZoneId.systemDefault())?.toLocalTime()
    }

    /** "Fri 30 Oct, 10:00", as on the confirmation card. */
    fun describe(at: LocalDateTime): String = at.format(CARD)

    fun describeDate(at: LocalDate): String = at.format(CARD_DATE)

    /** ISO local date-time to the minute, as the model reads it. */
    fun iso(at: LocalDateTime): String = at.withSecond(0).withNano(0).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

    private val CARD = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.getDefault())
    private val CARD_DATE = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())
}

/** Photos for the model: fitted within a long side and packed as RGB bytes. */
object PhotoPixels {
    const val MAX_SIDE = 768

    /** [width] x [height] scaled down (never up) so the long side is at most [maxSide]. */
    fun fitWithin(width: Int, height: Int, maxSide: Int = MAX_SIDE): Pair<Int, Int> {
        val long = maxOf(width, height)
        if (long <= maxSide) return width to height
        val scale = maxSide.toDouble() / long
        return maxOf(1, Math.round(width * scale).toInt()) to maxOf(1, Math.round(height * scale).toInt())
    }

    /** ARGB ints (as from Bitmap.getPixels) to packed RGB, 3 bytes per pixel. */
    fun argbToRgb(pixels: IntArray): ByteArray {
        val rgb = ByteArray(pixels.size * 3)
        pixels.forEachIndexed { i, p ->
            rgb[i * 3] = (p shr 16).toByte()
            rgb[i * 3 + 1] = (p shr 8).toByte()
            rgb[i * 3 + 2] = p.toByte()
        }
        return rgb
    }
}
