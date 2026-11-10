package com.ml.shubham0204.facenet_android.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SimilarityMathTest {
    @Test
    fun identicalVectorsHaveSimilarityOne() {
        val v = floatArrayOf(0.3f, -1.2f, 4f)
        assertEquals(1f, SimilarityMath.cosineSimilarity(v, v), 1e-6f)
    }

    @Test
    fun scaleDoesNotChangeSimilarity() {
        val a = floatArrayOf(1f, 2f, 3f)
        val b = floatArrayOf(10f, 20f, 30f)
        assertEquals(1f, SimilarityMath.cosineSimilarity(a, b), 1e-6f)
    }

    @Test
    fun orthogonalAndOppositeVectors() {
        assertEquals(0f, SimilarityMath.cosineSimilarity(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)), 1e-6f)
        assertEquals(-1f, SimilarityMath.cosineSimilarity(floatArrayOf(1f, 0f), floatArrayOf(-1f, 0f)), 1e-6f)
    }

    @Test
    fun zeroOrEmptyVectorsGiveZeroNotNaN() {
        assertEquals(0f, SimilarityMath.cosineSimilarity(floatArrayOf(0f, 0f), floatArrayOf(1f, 1f)), 0f)
        assertEquals(0f, SimilarityMath.cosineSimilarity(floatArrayOf(), floatArrayOf()), 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun mismatchedSizesAreRejected() {
        SimilarityMath.cosineSimilarity(floatArrayOf(1f), floatArrayOf(1f, 2f))
    }

    @Test
    fun thresholdIsInclusiveAndRejectsNaN() {
        assertTrue(SimilarityMath.isMatch(0.70f, 0.70f))
        assertTrue(SimilarityMath.isMatch(0.71f, 0.70f))
        assertFalse(SimilarityMath.isMatch(0.6999f, 0.70f))
        assertFalse(SimilarityMath.isMatch(Float.NaN, 0.0f))
    }

    @Test
    fun bestMatchPicksHighestSimilarity() {
        val query = floatArrayOf(1f, 0f)
        val candidates =
            listOf(
                "far" to floatArrayOf(0f, 1f),
                "near" to floatArrayOf(0.9f, 0.1f),
                "opposite" to floatArrayOf(-1f, 0f),
            )
        val (best, score) = SimilarityMath.bestMatch(query, candidates) { it.second }!!
        assertEquals("near", best.first)
        assertTrue(score > 0.99f)
    }

    @Test
    fun bestMatchOfEmptyListIsNull() {
        assertNull(SimilarityMath.bestMatch(floatArrayOf(1f), emptyList<FloatArray>()) { it })
    }
}
