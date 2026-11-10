package com.ml.shubham0204.facenet_android.engine

import kotlin.math.sqrt

/**
 * Pure similarity helpers shared by the camera pipeline and the REST API.
 * No Android dependencies, so it is covered by JVM unit tests.
 */
object SimilarityMath {
    /**
     * Cosine similarity in [-1, 1]. Returns 0 for empty or zero-magnitude vectors
     * (instead of NaN) so a degenerate embedding can never pass a threshold.
     */
    fun cosineSimilarity(
        a: FloatArray,
        b: FloatArray,
    ): Float {
        require(a.size == b.size) { "Embedding sizes differ: ${a.size} vs ${b.size}" }
        if (a.isEmpty()) return 0f
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        if (normA == 0.0 || normB == 0.0) return 0f
        return (dot / (sqrt(normA) * sqrt(normB))).toFloat()
    }

    /** A face is recognized only when its similarity reaches the threshold (inclusive). */
    fun isMatch(
        similarity: Float,
        threshold: Float,
    ): Boolean = !similarity.isNaN() && similarity >= threshold

    /** Exact nearest neighbour by cosine similarity. Returns null for an empty candidate list. */
    fun <T> bestMatch(
        query: FloatArray,
        candidates: List<T>,
        embeddingOf: (T) -> FloatArray,
    ): Pair<T, Float>? {
        var best: T? = null
        var bestScore = Float.NEGATIVE_INFINITY
        for (candidate in candidates) {
            val score = cosineSimilarity(query, embeddingOf(candidate))
            if (score > bestScore) {
                bestScore = score
                best = candidate
            }
        }
        return best?.let { it to bestScore }
    }
}
