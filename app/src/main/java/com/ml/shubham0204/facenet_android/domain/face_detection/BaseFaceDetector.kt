package com.ml.shubham0204.facenet_android.domain.face_detection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream

abstract class BaseFaceDetector {
    abstract suspend fun getCroppedFace(imageUri: Uri): Result<Bitmap>

    // Crops exactly one face from an already-decoded image (used by the REST API enroll path)
    abstract suspend fun getCroppedFace(imageBitmap: Bitmap): Result<Bitmap>

    // `highAccuracy` trades speed for recall; the live camera keeps the fast mode
    abstract suspend fun getAllCroppedFaces(
        frameBitmap: Bitmap,
        highAccuracy: Boolean = false,
    ): List<Pair<Bitmap, Rect>>

    protected fun getBitmapFromUri(context: Context, imageUri: Uri): Bitmap? {
        var imageInputStream =
            context.contentResolver.openInputStream(imageUri) ?: return null
        var imageBitmap = BitmapFactory.decodeStream(imageInputStream)
        imageInputStream.close()

        // Re-create an input-stream to reset its position
        // InputStream returns false with markSupported(), hence we cannot
        // reset its position
        // Without recreating the inputStream, no exif-data is read
        imageInputStream =
            context.contentResolver.openInputStream(imageUri) ?: return null
        val exifInterface = ExifInterface(imageInputStream)
        imageBitmap =
            when (
                exifInterface.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_UNDEFINED,
                )
            ) {
                ExifInterface.ORIENTATION_ROTATE_90 -> rotateBitmap(imageBitmap, 90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> rotateBitmap(imageBitmap, 180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> rotateBitmap(imageBitmap, 270f)
                else -> imageBitmap
            }
        imageInputStream.close()
        return imageBitmap
    }

    // DEBUG: For testing purpose, saves the Bitmap to the app's private storage
    protected fun saveBitmap(
        context: Context,
        image: Bitmap,
        name: String,
    ) {
        val fileOutputStream = FileOutputStream(File(context.filesDir.absolutePath + "/$name.png"))
        image.compress(Bitmap.CompressFormat.PNG, 100, fileOutputStream)
    }

    protected fun rotateBitmap(
        source: Bitmap,
        degrees: Float,
    ): Bitmap {
        val matrix = Matrix()
        matrix.postRotate(degrees)
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, false)
    }

    // Clamps `boundingBox` into `image`; null when too little of the face is left (see FaceBoxClamp)
    protected fun clampToImage(
        image: Bitmap,
        boundingBox: Rect,
    ): Rect? =
        FaceBoxClamp
            .clamp(boundingBox.left, boundingBox.top, boundingBox.right, boundingBox.bottom, image.width, image.height)
            ?.let { Rect(it.left, it.top, it.right, it.bottom) }
}
