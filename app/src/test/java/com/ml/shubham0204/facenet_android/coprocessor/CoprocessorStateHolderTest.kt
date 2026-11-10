package com.ml.shubham0204.facenet_android.coprocessor

import org.junit.Assert.assertEquals
import org.junit.Test

class CoprocessorStateHolderTest {
    @Test
    fun keepsNewestEntriesFirstAndCountsAll() {
        val holder = CoprocessorStateHolder(maxLogEntries = 3)
        repeat(5) { i -> holder.record(RequestLogEntry(i.toLong(), "GET", "/api/health", 200, 1)) }
        assertEquals(listOf(4L, 3L, 2L), holder.recentRequests.value.map { it.timeMillis })
        assertEquals(5L, holder.requestsServed)
    }
}
