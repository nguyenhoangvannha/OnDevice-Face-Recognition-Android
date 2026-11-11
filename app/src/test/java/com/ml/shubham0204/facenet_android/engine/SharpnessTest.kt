package com.ml.shubham0204.facenet_android.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SharpnessTest {
    private fun gray(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    @Test
    fun flatImageHasZeroSharpness() {
        assertEquals(0.0, Sharpness.laplacianVariance(IntArray(50 * 70) { gray(128) }, 50, 70), 0.0)
        assertEquals(0.0, Sharpness.laplacianVariance(IntArray(300 * 300) { gray(17) }, 300, 300), 0.0)
    }

    @Test
    fun singleBrightPixelMatchesHandComputedOpenCvValue() {
        // 112 x 112 (no resize), one white pixel in the middle on black:
        // Laplacian = -1020 at the pixel, +255 at its 4 neighbours, 0 elsewhere; mean 0.
        val n = Sharpness.SIZE
        val img = IntArray(n * n) { gray(0) }
        img[56 * n + 56] = gray(255)
        val expected = (1020.0 * 1020.0 + 4 * 255.0 * 255.0) / (n * n)
        assertEquals(expected, Sharpness.laplacianVariance(img, n, n), 1e-9)
    }

    @Test
    fun borderUsesReflect101() {
        // White pixel in the corner: reflect-101 mirrors its neighbours, so (0,0) sees (1,0) twice and (0,1) twice.
        val n = Sharpness.SIZE
        val img = IntArray(n * n) { gray(0) }
        img[0] = gray(255)
        // L(0,0) = -1020; L(1,0) = L(0,1) = 255 (the corner is their left/top neighbour).
        val values = doubleArrayOf(-1020.0, 255.0, 255.0)
        val mean = values.sum() / (n * n)
        val expected = values.sumOf { it * it } / (n * n) - mean * mean
        assertEquals(expected, Sharpness.laplacianVariance(img, n, n), 1e-9)
    }

    @Test
    fun sharpEdgesScoreHigherThanBlurredOnes() {
        val w = 160
        val h = 200
        val sharp = IntArray(w * h) { i -> if ((i % w) / 8 % 2 == 0) gray(30) else gray(220) }
        val blurred =
            IntArray(w * h) { i ->
                val x = i % w
                // smooth ramp with the same period
                gray((125 + 95 * kotlin.math.sin(x * Math.PI / 8)).toInt())
            }
        val s = Sharpness.laplacianVariance(sharp, w, h)
        val b = Sharpness.laplacianVariance(blurred, w, h)
        assertTrue("sharp=$s blurred=$b", s > 3 * b)
    }

    @Test
    fun resizeKeepsSizeIdenticalAndAveragesOnDownscale() {
        val img = IntArray(4) { gray(it * 60) }
        assertArrayEquals(img, Sharpness.resizeBilinear(img, 2, 2, 2, 2))
        // 2x2 -> 1x1 samples the center: mean of 0, 60, 120, 180 = 90
        assertEquals(gray(90), Sharpness.resizeBilinear(img, 2, 2, 1, 1)[0])
    }

    @Test
    fun grayscaleUsesOpenCvWeights() {
        val n = Sharpness.SIZE
        // Pure red pixel: gray = (255 * 4899 + 8192) >> 14 = 76
        val img = IntArray(n * n) { gray(0) }
        img[56 * n + 56] = (0xFF shl 24) or (255 shl 16)
        val expected = (304.0 * 304.0 + 4 * 76.0 * 76.0) / (n * n)
        assertEquals(expected, Sharpness.laplacianVariance(img, n, n), 1e-9)
    }

    // Reference values from OpenCV 5.0.0 on the MacBook:
    // cv2.Laplacian(cv2.cvtColor(cv2.resize(img, (112, 112)), cv2.COLOR_BGR2GRAY), cv2.CV_64F).var()
    private fun pattern(w: Int, h: Int) =
        IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val r = (x * 37 + y * 91 + (x * y) % 53) % 256
            val g = (x * 13 + y * 7) % 256
            val b = (x xor y) % 256
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

    private fun smooth(w: Int, h: Int) =
        IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            gray((128 + 100 * kotlin.math.sin(x / 9.0) * kotlin.math.cos(y / 13.0)).toInt())
        }

    private fun assertClose(expected: Double, actual: Double, relTol: Double) =
        assertTrue("expected=$expected actual=$actual", kotlin.math.abs(actual - expected) <= relTol * expected)

    @Test
    fun matchesOpenCvOnResizedImages() {
        assertClose(8939.089, Sharpness.laplacianVariance(pattern(150, 183), 150, 183), 0.02)
        assertClose(1498.598, Sharpness.laplacianVariance(pattern(60, 75), 60, 75), 0.02)
        assertClose(13576.562, Sharpness.laplacianVariance(pattern(300, 280), 300, 280), 0.02)
        assertClose(15.0817, Sharpness.laplacianVariance(smooth(150, 183), 150, 183), 0.05)
        // Very low values on an upscaled image differ more (OpenCV uses fixed-point interpolation): 2.35 vs 2.48
        assertClose(2.47998, Sharpness.laplacianVariance(smooth(60, 75), 60, 75), 0.1)
    }
}
