package com.vanskis.liquidpocket.tools

import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolArgsTest {
    @Test fun `arguments are read from an object or from an object sent as a string`() {
        assertEquals("milk", ToolArgs.parse("""{"text":" milk "}""").string("text"))
        assertEquals("milk", ToolArgs.parse(""""{\"text\":\"milk\"}"""").string("text"))
        assertTrue(ToolArgs.parse("not json").isEmpty())
        assertTrue(ToolArgs.parse("[1]").isEmpty())
        assertNull(ToolArgs.parse("""{"text":"  "}""").string("text"))
    }

    @Test fun `numbers, booleans and lists may come as text`() {
        val args = ToolArgs.parse("""{"n":"3","b":"true","a":[1,"2"],"s":"[4, 5]","c":"6, 7","one":8}""")
        assertEquals(3, args.int("n"))
        assertEquals(true, args.bool("b"))
        assertEquals(listOf("1", "2"), args.strings("a"))
        assertEquals(listOf("4", "5"), args.strings("s"))
        assertEquals(listOf("6", "7"), args.strings("c"))
        assertEquals(listOf("8"), args.strings("one"))
        assertEquals(emptyList<String>(), args.strings("missing"))
    }

    @Test fun `dates and times are read in the forms the model writes`() {
        val zone = ZoneId.of("Europe/Paris")
        val at = LocalDateTime.of(2026, 10, 30, 10, 0)
        assertEquals(at, ToolTime.dateTime("2026-10-30T10:00", zone))
        assertEquals(at, ToolTime.dateTime("2026-10-30 10:00:00", zone))
        assertEquals(at, ToolTime.dateTime("2026-10-30T09:00:00Z", zone))
        assertEquals(LocalDateTime.of(2026, 10, 30, 0, 0), ToolTime.dateTime("2026-10-30", zone))
        assertTrue(ToolTime.isDateOnly("2026-10-30"))
        assertNull(ToolTime.dateTime("tomorrow", zone))
        assertEquals(LocalTime.of(7, 30), ToolTime.time("7:30"))
        assertEquals(LocalTime.of(19, 5), ToolTime.time("7:05 pm"))
    }

    @Test fun `photos are fitted within the long side and packed as rgb`() {
        assertEquals(768 to 576, PhotoPixels.fitWithin(4000, 3000))
        assertEquals(432 to 768, PhotoPixels.fitWithin(2160, 3840))
        assertEquals(640 to 480, PhotoPixels.fitWithin(640, 480))
        val rgb = PhotoPixels.argbToRgb(intArrayOf(0xFF102030.toInt(), 0x00FFFFFF))
        assertEquals(listOf(0x10, 0x20, 0x30, 0xFF, 0xFF, 0xFF), rgb.map { it.toInt() and 0xFF })
    }
}
