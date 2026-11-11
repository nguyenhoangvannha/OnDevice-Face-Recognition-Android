package com.ml.shubham0204.facenet_android.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonBoxTest {
    @Test
    fun parsesFrigateStyleBox() {
        assertEquals(NormalizedBox(0.25f, 0.1f, 0.3f, 0.65f), NormalizedBox.parse("0.25,0.10,0.30,0.65"))
        assertEquals(NormalizedBox(0f, 0f, 1f, 1f), NormalizedBox.parse(" 0, 0 ,1,1 "))
        // Frigate box from a real event: x + w = 0.9831
        assertTrue(NormalizedBox.parse("0.8849826388888888,0.4992283950617284,0.09809027777777778,0.3395061728395062") != null)
    }

    @Test
    fun rejectsMalformedOrOutOfFrameBoxes() {
        listOf("", "0.1,0.2,0.3", "0.1,0.2,0.3,0.4,0.5", "a,0,1,1", "NaN,0,0.5,0.5", "Infinity,0,0.5,0.5",
            "-0.01,0,0.5,0.5", "0,1.01,0.5,0.5", "0,0,0,0.5", "0,0,0.5,-1", "0.6,0,0.5,0.5", "0,0.6,0.5,0.5")
            .forEach { assertNull(it, NormalizedBox.parse(it)) }
    }

    // 1000 x 500 image, person box covers x 250..550, y 50..375
    private val box = NormalizedBox(0.25f, 0.1f, 0.3f, 0.65f)

    private fun keeps(left: Int, top: Int, right: Int, bottom: Int) =
        PersonBoxFilter.keeps(FaceBox(left, top, right, bottom), box, 1000, 500)

    @Test
    fun keepsFaceInsideThePersonBox() {
        assertTrue(keeps(350, 60, 430, 150))
    }

    @Test
    fun dropsFaceOutsideThePersonBox() {
        assertFalse(keeps(700, 60, 780, 150)) // someone else in the background
        assertFalse(keeps(0, 0, 40, 40))
    }

    @Test
    fun keepsFaceSlightlyCutByTheBoxEdge() {
        // 80 x 90 face, 60 px of its width inside: center inside, 75% inside
        assertTrue(keeps(230, 60, 310, 150))
    }

    @Test
    fun dropsFaceWhoseCenterIsOutside() {
        // center x = 240 < 250
        assertFalse(keeps(200, 60, 280, 150))
    }

    @Test
    fun dropsFaceWithLessThanHalfInsideEvenWithCenterInside() {
        // Tall face 40 x 400 straddling the bottom edge: center y = 360 inside, only (375-160)/400 = 54% inside -> kept
        assertTrue(keeps(300, 160, 340, 560))
        // Same width, 600 tall: center y = 400 outside
        assertFalse(keeps(300, 100, 340, 700))
        // Wide face 400 x 40 inside vertically, center x = 400, inside fraction (550-250)/400 = 75% -> kept
        assertTrue(keeps(200, 100, 600, 140))
        // 800 x 40 face, center x = 400, inside fraction 300/800 = 37.5% -> dropped
        assertFalse(keeps(0, 100, 800, 140))
    }

    @Test
    fun degenerateInputIsDropped() {
        assertFalse(keeps(300, 60, 300, 150))
        assertFalse(PersonBoxFilter.keeps(FaceBox(1, 1, 5, 5), box, 0, 0))
    }
}
