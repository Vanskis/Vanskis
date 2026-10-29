package com.vanskis.liquidpocket.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResumePlannerTest {
    @Test fun plan() {
        assertEquals(StartPlan.Fresh, ResumePlanner.plan(0, 100, "a", "a"))
        assertEquals(StartPlan.Resume(40), ResumePlanner.plan(40, 100, "a", "A"))
        assertEquals(StartPlan.Fresh, ResumePlanner.plan(40, 100, "old", "new"))
        assertEquals(StartPlan.Fresh, ResumePlanner.plan(40, 100, null, "new"))
        assertEquals(StartPlan.Resume(40), ResumePlanner.plan(40, 100, null, null))
        assertEquals(StartPlan.Fresh, ResumePlanner.plan(140, 100, "a", "a"))
        assertEquals(StartPlan.AlreadyComplete, ResumePlanner.plan(100, 100, "a", "a"))
    }

    @Test fun rangeHeader() = assertEquals("bytes=40-", ResumePlanner.rangeHeader(40))

    @Test fun responses() {
        assertEquals(ResponseAction.Append(40), ResumePlanner.onResponse(40, 206, "bytes 40-99/100", 100))
        assertEquals(ResponseAction.Append(40), ResumePlanner.onResponse(40, 206, "bytes 40-99/*", 100))
        assertEquals(ResponseAction.Restart, ResumePlanner.onResponse(40, 206, "bytes 0-99/100", 100))
        assertEquals(ResponseAction.Restart, ResumePlanner.onResponse(40, 206, "bytes 40-199/200", 100))
        assertEquals(ResponseAction.Restart, ResumePlanner.onResponse(40, 206, null, 100))
        assertEquals(ResponseAction.WriteFromStart, ResumePlanner.onResponse(40, 200, null, 100))
        assertEquals(ResponseAction.Complete, ResumePlanner.onResponse(100, 416, null, 100))
        assertEquals(ResponseAction.Restart, ResumePlanner.onResponse(40, 416, null, 100))
        assertEquals(ResponseAction.Fail(503), ResumePlanner.onResponse(40, 503, null, 100))
    }

    @Test fun parseContentRange() {
        assertEquals(ResumePlanner.ContentRange(5, 9, 10), ResumePlanner.parseContentRange("bytes 5-9/10"))
        assertNull(ResumePlanner.parseContentRange("garbage"))
    }
}
