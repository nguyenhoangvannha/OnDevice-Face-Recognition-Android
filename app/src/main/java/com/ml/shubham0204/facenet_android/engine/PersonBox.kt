package com.ml.shubham0204.facenet_android.engine

/**
 * A person box in Frigate's convention: `[x, y, w, h]`, each normalized to 0..1 of the frame
 * (Frigate event `data.box`).
 */
data class NormalizedBox(
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
) {
    companion object {
        private const val EPS = 1e-6f

        /** Parses `"x,y,w,h"`; null when it is not 4 finite numbers describing a box inside the frame. */
        fun parse(text: String): NormalizedBox? {
            val parts = text.split(',').map { it.trim() }
            if (parts.size != 4) return null
            val v = parts.map { it.toFloatOrNull() ?: return null }
            if (v.any { !it.isFinite() }) return null
            val (x, y, w, h) = v
            if (x < 0f || y < 0f || x > 1f || y > 1f) return null
            if (w <= 0f || h <= 0f || w > 1f || h > 1f) return null
            if (x + w > 1f + EPS || y + h > 1f + EPS) return null
            return NormalizedBox(x, y, w, h)
        }
    }
}

/**
 * Keeps a face only when it belongs to the person: the face box center lies inside the person box
 * and at least [MIN_FACE_AREA_INSIDE] of the face box area lies inside it.
 */
object PersonBoxFilter {
    const val MIN_FACE_AREA_INSIDE = 0.5f

    fun keeps(
        face: FaceBox,
        personBox: NormalizedBox,
        imageWidth: Int,
        imageHeight: Int,
    ): Boolean {
        val faceW = (face.right - face.left).toDouble()
        val faceH = (face.bottom - face.top).toDouble()
        if (faceW <= 0 || faceH <= 0 || imageWidth <= 0 || imageHeight <= 0) return false
        val left = personBox.x.toDouble() * imageWidth
        val top = personBox.y.toDouble() * imageHeight
        val right = (personBox.x + personBox.w).toDouble() * imageWidth
        val bottom = (personBox.y + personBox.h).toDouble() * imageHeight

        val cx = face.left + faceW / 2
        val cy = face.top + faceH / 2
        if (cx < left || cx > right || cy < top || cy > bottom) return false

        val ix = minOf(face.right.toDouble(), right) - maxOf(face.left.toDouble(), left)
        val iy = minOf(face.bottom.toDouble(), bottom) - maxOf(face.top.toDouble(), top)
        if (ix <= 0 || iy <= 0) return false
        return ix * iy >= MIN_FACE_AREA_INSIDE * faceW * faceH
    }
}

/** Optional behaviour of a recognize call; the defaults match the v1 API. */
data class RecognizeOptions(
    val withLiveness: Boolean = false,
    val personBox: NormalizedBox? = null,
    /** Match unknown faces against stranger clusters and create "Person_NN" clusters. */
    val clusterStrangers: Boolean = false,
    /** Client event id (e.g. Frigate event id); repeated frames of one event are not counted twice. */
    val eventId: String? = null,
)
