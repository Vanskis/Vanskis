package com.vanskis.liquidpocket.chat

import com.vanskis.liquidpocket.data.MessageExtras
import com.vanskis.liquidpocket.data.MessageJson
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class EnvironmentAnchorTest {
    private val now = ZonedDateTime.of(2026, 10, 30, 14, 5, 0, 0, ZoneId.of("Europe/Paris"))

    @Test fun `the line tells the date, time, zone and phone state`() {
        val state = PhoneState(now, 64, charging = true, network = "Wi-Fi", device = "Google Pixel 8", darkMode = true,
            language = "fr-FR", accelerator = "NPU", model = "LFM3-1.7B-Q4_K_M")
        assertEquals(
            "[Sent Friday 30 October 2026, 14:05 Europe/Paris (UTC+01:00); battery 64%, charging; Wi-Fi; Google Pixel 8; " +
                "dark mode; language fr-FR; running LFM3-1.7B-Q4_K_M on the NPU]",
            EnvironmentAnchor.line(state),
        )
        assertEquals("[Sent Friday 30 October 2026, 13:05 UTC (UTC+00:00); light mode]", EnvironmentAnchor.line(PhoneState(now.withZoneSameInstant(ZoneId.of("UTC")))))
    }

    @Test fun `the line goes in front of the text and is saved with the turn`() {
        assertEquals("[ctx]\nhi", EnvironmentAnchor.prefixed("[ctx]", "hi"))
        assertEquals("hi", EnvironmentAnchor.prefixed(null, "hi"))
        assertEquals("[ctx]", EnvironmentAnchor.prefixed("[ctx]", ""))
        val extras = MessageExtras(context = "[ctx]")
        assertEquals(extras, MessageJson.decodeExtras(MessageJson.encodeExtras(extras)))
    }
}
