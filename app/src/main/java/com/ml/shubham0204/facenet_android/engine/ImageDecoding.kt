package com.ml.shubham0204.facenet_android.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream

/** Decodes uploaded image bytes into an upright ARGB_8888 bitmap. */
object ImageDecoding {
    /** Longest side after decode; larger images are subsampled to bound memory use. */
    const val DEFAULT_MAX_SIDE = 1920

    /** Returns null when the bytes are not a decodable image. */
    fun decode(
        bytes: ByteArray,
        maxSide: Int = DEFAULT_MAX_SIDE,
    ): Bitmap? {
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sampleSize > maxSide) sampleSize *= 2

        val options =
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null

        val orientation =
            try {
                ExifInterface(ByteArrayInputStream(bytes))
                    .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)
            } catch (e: Exception) {
                ExifInterface.ORIENTATION_UNDEFINED
            }
        val degrees =
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        if (degrees == 0f) return bitmap
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}
