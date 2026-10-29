package com.vanskis.liquidpocket.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class ThreadPolicyTest {
    @Test
    fun allButTwoCoresBetweenTwoAndSix() {
        assertEquals(6, ThreadPolicy.threadsFor(8)) // Galaxy S26 Ultra
        assertEquals(2, ThreadPolicy.threadsFor(1))
        assertEquals(2, ThreadPolicy.threadsFor(4))
        assertEquals(4, ThreadPolicy.threadsFor(6))
        assertEquals(6, ThreadPolicy.threadsFor(12))
    }
}
