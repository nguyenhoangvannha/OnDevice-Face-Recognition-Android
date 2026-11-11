package com.ml.shubham0204.facenet_android.domain.face_detection

/** A face box in image pixels; `right`/`bottom` are exclusive, like `android.graphics.Rect`. */
data class FaceBox(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * Detectors often return boxes that reach past the image edge, e.g. for tight face crops such as
 * Frigate's face library. Instead of dropping those faces, the box is clamped into the image.
 *
 * A clamped box is dropped only when it is no longer a usable face:
 * - either side is shorter than [MIN_SIDE_PX], or
 * - less than [MIN_VISIBLE_FRACTION] of the detected box area lies inside the image.
 */
object FaceBoxClamp {
    const val MIN_SIDE_PX = 20
    const val MIN_VISIBLE_FRACTION = 0.5f

    fun clamp(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        imageWidth: Int,
        imageHeight: Int,
    ): FaceBox? {
        val detectedWidth = right.toLong() - left
        val detectedHeight = bottom.toLong() - top
        if (detectedWidth <= 0 || detectedHeight <= 0 || imageWidth <= 0 || imageHeight <= 0) return null
        val box =
            FaceBox(
                left = left.coerceIn(0, imageWidth),
                top = top.coerceIn(0, imageHeight),
                right = right.coerceIn(0, imageWidth),
                bottom = bottom.coerceIn(0, imageHeight),
            )
        if (box.width < MIN_SIDE_PX || box.height < MIN_SIDE_PX) return null
        val visibleArea = box.width.toLong() * box.height
        if (visibleArea < MIN_VISIBLE_FRACTION * detectedWidth * detectedHeight) return null
        return box
    }
}
