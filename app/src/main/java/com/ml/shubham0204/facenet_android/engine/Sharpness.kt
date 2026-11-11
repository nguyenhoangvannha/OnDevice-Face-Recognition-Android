package com.ml.shubham0204.facenet_android.engine

/**
 * Face sharpness as the variance of the Laplacian, computed like the Mac Mini pipeline
 * (`face_clustering.py`: `cv2.resize(roi, (112, 112))` -> `COLOR_BGR2GRAY` -> `cv2.Laplacian(CV_64F).var()`),
 * so scores from both are comparable:
 * 1. bilinear resize to [SIZE] x [SIZE] (OpenCV `INTER_LINEAR` pixel-center mapping, rounded to 8 bit),
 * 2. grayscale `0.299 R + 0.587 G + 0.114 B` (OpenCV fixed-point weights),
 * 3. 3x3 kernel `[[0,1,0],[1,-4,1],[0,1,0]]` with `BORDER_REFLECT_101`,
 * 4. population variance over all pixels.
 */
object Sharpness {
    const val SIZE = 112

    /** @param argb pixels in `Bitmap.getPixels` order (row-major, 0xAARRGGBB). */
    fun laplacianVariance(
        argb: IntArray,
        width: Int,
        height: Int,
    ): Double {
        require(width > 0 && height > 0 && argb.size >= width * height) { "Bad image size" }
        val gray = toGray(resizeBilinear(argb, width, height, SIZE, SIZE))
        return laplacianVarianceOfGray(gray, SIZE, SIZE)
    }

    internal fun laplacianVarianceOfGray(
        gray: IntArray,
        width: Int,
        height: Int,
    ): Double {
        var sum = 0.0
        var sumSq = 0.0
        for (y in 0 until height) {
            val up = reflect101(y - 1, height)
            val down = reflect101(y + 1, height)
            for (x in 0 until width) {
                val left = reflect101(x - 1, width)
                val right = reflect101(x + 1, width)
                val v =
                    (gray[up * width + x] + gray[down * width + x] + gray[y * width + left] + gray[y * width + right] -
                        4 * gray[y * width + x]).toDouble()
                sum += v
                sumSq += v * v
            }
        }
        val n = (width * height).toDouble()
        val mean = sum / n
        return maxOf(0.0, sumSq / n - mean * mean)
    }

    // OpenCV BORDER_REFLECT_101: gfedcb|abcdefgh|gfedcba
    private fun reflect101(i: Int, n: Int): Int =
        when {
            n == 1 -> 0
            i < 0 -> -i
            i >= n -> 2 * n - 2 - i
            else -> i
        }

    private fun toGray(argb: IntArray): IntArray =
        IntArray(argb.size) {
            val p = argb[it]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            (r * 4899 + g * 9617 + b * 1868 + 8192) shr 14
        }

    internal fun resizeBilinear(
        src: IntArray,
        sw: Int,
        sh: Int,
        dw: Int,
        dh: Int,
    ): IntArray {
        if (sw == dw && sh == dh) return src.copyOf(sw * sh)
        val out = IntArray(dw * dh)
        val sx = sw.toDouble() / dw
        val sy = sh.toDouble() / dh
        for (y in 0 until dh) {
            val fy = ((y + 0.5) * sy - 0.5).coerceAtLeast(0.0)
            val y0 = minOf(fy.toInt(), sh - 1)
            val y1 = minOf(y0 + 1, sh - 1)
            val wy = fy - y0
            for (x in 0 until dw) {
                val fx = ((x + 0.5) * sx - 0.5).coerceAtLeast(0.0)
                val x0 = minOf(fx.toInt(), sw - 1)
                val x1 = minOf(x0 + 1, sw - 1)
                val wx = fx - x0
                val p00 = src[y0 * sw + x0]
                val p01 = src[y0 * sw + x1]
                val p10 = src[y1 * sw + x0]
                val p11 = src[y1 * sw + x1]
                var pixel = 0xFF shl 24
                for (shift in intArrayOf(16, 8, 0)) {
                    val v =
                        ((p00 shr shift) and 0xFF) * (1 - wx) * (1 - wy) +
                            ((p01 shr shift) and 0xFF) * wx * (1 - wy) +
                            ((p10 shr shift) and 0xFF) * (1 - wx) * wy +
                            ((p11 shr shift) and 0xFF) * wx * wy
                    pixel = pixel or ((Math.round(v).toInt().coerceIn(0, 255)) shl shift)
                }
                out[y * dw + x] = pixel
            }
        }
        return out
    }
}
