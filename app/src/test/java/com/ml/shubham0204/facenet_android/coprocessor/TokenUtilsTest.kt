package com.ml.shubham0204.facenet_android.coprocessor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenUtilsTest {
    @Test
    fun tokensAreLongUrlSafeAndRandom() {
        val a = TokenUtils.newToken()
        val b = TokenUtils.newToken()
        assertEquals(43, a.length)
        assertTrue(a.matches(Regex("[A-Za-z0-9_-]+")))
        assertNotEquals(a, b)
    }

    @Test
    fun matchingRules() {
        assertTrue(TokenUtils.matches("abc", "abc"))
        assertFalse(TokenUtils.matches("abd", "abc"))
        assertFalse(TokenUtils.matches(null, "abc"))
        assertFalse(TokenUtils.matches("", ""))
    }
}
