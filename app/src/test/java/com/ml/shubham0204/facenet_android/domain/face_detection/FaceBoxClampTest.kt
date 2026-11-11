package com.ml.shubham0204.facenet_android.domain.face_detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FaceBoxClampTest {
    @Test
    fun boxInsideImageIsUnchanged() {
        assertEquals(FaceBox(10, 20, 110, 140), FaceBoxClamp.clamp(10, 20, 110, 140, 200, 200))
    }

    @Test
    fun boxTouchingTheEdgesIsKept() {
        assertEquals(FaceBox(0, 0, 200, 150), FaceBoxClamp.clamp(0, 0, 200, 150, 200, 150))
    }

    @Test
    fun tightCropBoxReachingPastEveryEdgeIsClampedToTheImage() {
        // Frigate face-library crop: ML Kit's box is a bit larger than the crop itself
        assertEquals(FaceBox(0, 0, 160, 200), FaceBoxClamp.clamp(-12, -20, 170, 215, 160, 200))
    }

    @Test
    fun boxPastOneEdgeIsClampedOnThatEdgeOnly() {
        assertEquals(FaceBox(150, 40, 200, 120), FaceBoxClamp.clamp(150, 40, 230, 120, 200, 200))
    }

    @Test
    fun boxWithLessThanHalfInsideIsDropped() {
        // 100 x 100 box, only 40 x 100 inside
        assertNull(FaceBoxClamp.clamp(160, 0, 260, 100, 200, 200))
    }

    @Test
    fun boxWithExactlyHalfInsideIsKept() {
        assertEquals(FaceBox(150, 0, 200, 100), FaceBoxClamp.clamp(150, 0, 250, 100, 200, 200))
    }

    @Test
    fun clampedSideBelowMinimumIsDropped() {
        assertNull(FaceBoxClamp.clamp(10, 10, 10 + FaceBoxClamp.MIN_SIDE_PX - 1, 60, 200, 200))
        assertEquals(
            FaceBox(10, 10, 10 + FaceBoxClamp.MIN_SIDE_PX, 60),
            FaceBoxClamp.clamp(10, 10, 10 + FaceBoxClamp.MIN_SIDE_PX, 60, 200, 200),
        )
    }

    @Test
    fun boxFullyOutsideOrDegenerateIsDropped() {
        assertNull(FaceBoxClamp.clamp(300, 300, 400, 400, 200, 200))
        assertNull(FaceBoxClamp.clamp(50, 50, 50, 90, 200, 200))
        assertNull(FaceBoxClamp.clamp(90, 50, 50, 90, 200, 200))
        assertNull(FaceBoxClamp.clamp(0, 0, 50, 50, 0, 0))
    }

    @Test
    fun hugeBoxDoesNotOverflowAndIsDroppedWhenMostlyOutside() {
        assertNull(FaceBoxClamp.clamp(-1_000_000_000, -1_000_000_000, 200, 200, 200, 200))
    }
}
