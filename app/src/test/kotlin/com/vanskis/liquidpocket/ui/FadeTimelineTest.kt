package com.vanskis.liquidpocket.ui

import com.vanskis.liquidpocket.ui.components.FadeTimeline
import com.vanskis.liquidpocket.ui.components.FadeTimeline.Piece
import org.junit.Assert.assertEquals
import org.junit.Test

class FadeTimelineTest {
    private val ms = 1_000_000L

    @Test fun `each new piece fades in over the duration and is then dropped`() {
        val fade = FadeTimeline(durationNanos = 300 * ms)
        fade.saw(key = 0, length = 5, now = 0)
        fade.saw(key = 0, length = 9, now = 75 * ms)
        assertEquals(listOf(Piece(0, 5, 0.5f), Piece(5, 9, 0.25f)), fade.fading(0, now = 150 * ms))
        assertEquals(listOf(Piece(5, 9, 0.75f)), fade.fading(0, now = 300 * ms))
        assertEquals(emptyList<Piece>(), fade.fading(0, now = 400 * ms))
    }

    @Test fun `blocks fade on their own and an unchanged length adds nothing`() {
        val fade = FadeTimeline(durationNanos = 300 * ms)
        fade.saw(key = 0, length = 4, now = 0)
        fade.saw(key = 7, length = 3, now = 0)
        fade.saw(key = 7, length = 3, now = 50 * ms)
        assertEquals(listOf(Piece(0, 3, 0.5f)), fade.fading(7, now = 150 * ms))
        assertEquals(emptyList<Piece>(), fade.fading(42, now = 0))
    }

    @Test fun `text that got shorter drops the pieces past its end`() {
        val fade = FadeTimeline(durationNanos = 300 * ms)
        fade.saw(key = 0, length = 4, now = 0)
        fade.saw(key = 0, length = 10, now = 0)
        fade.saw(key = 0, length = 6, now = 0)
        assertEquals(listOf(Piece(0, 4, 0f), Piece(4, 6, 0f)), fade.fading(0, now = 0))
    }
}
