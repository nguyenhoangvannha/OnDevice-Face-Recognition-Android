package com.ml.shubham0204.facenet_android.engine.strangers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventTrackerTest {
    @Test
    fun eventIdCharset() {
        assertTrue(EventIds.isValid("1794372518.917899-f7uo07"))
        assertTrue(EventIds.isValid("cam_1:evt-9"))
        assertTrue(EventIds.isValid("x".repeat(64)))
        for (bad in listOf("", "x".repeat(65), "a b", "a/b", "<x>", "é", "a\nb")) assertFalse(bad, EventIds.isValid(bad))
    }

    @Test
    fun repeatWithinTtlThenForgotten() {
        val t = EventTracker(maxEvents = 10, ttlMs = 1_000)
        assertFalse(t.begin("e", 0).repeat)
        assertTrue(t.begin("e", 999).repeat)
        assertTrue(t.begin("e", 1_998).repeat) // TTL counts from last seen
        assertFalse(t.begin("e", 2_998).repeat)
    }

    @Test
    fun leastRecentlyUsedIsDroppedBeyondCapacity() {
        val t = EventTracker(maxEvents = 2, ttlMs = 1_000_000)
        t.begin("a", 1)
        t.begin("b", 2)
        t.begin("a", 3) // a is now most recent
        t.begin("c", 4) // evicts b
        assertEquals(2, t.size())
        assertTrue(t.begin("a", 5).repeat)
        assertFalse(t.begin("b", 6).repeat)
    }
}
