package com.ml.shubham0204.facenet_android.domain.embeddings

import android.graphics.Bitmap
import androidx.core.graphics.scale
import com.ml.shubham0204.facenet_android.engine.backend.BackendController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Single

// Derived from the original project:
// https://github.com/shubham0204/FaceRecognition_With_FaceNet_Android/blob/master/app/src/main/java/com/ml/quaterion/facenetdetection/model/FaceNetModel.kt
// Utility class for FaceNet model
/**
 * FaceNet embeddings. The model and the hardware it runs on (CPU/GPU/NPU) are chosen by
 * [BackendController]; every backend uses the same weights, so stored embeddings stay valid.
 * Model source and export scripts: tools/export/.
 */
@Single
class FaceNet(
    private val backends: BackendController,
) {
    private val imgSize = 160

    // Gets an face embedding using FaceNet
    suspend fun getFaceEmbedding(image: Bitmap) =
        withContext(Dispatchers.Default) {
            backends.embed(convertBitmapToBuffer(image))
        }

    // Resize the given bitmap to 160x160 and flatten it to RGB floats 0..255 (NHWC)
    private fun convertBitmapToBuffer(image: Bitmap): FloatArray {
        val resizedBitmap = image.scale(imgSize, imgSize, true)
        val pixels = IntArray(imgSize * imgSize)
        resizedBitmap.getPixels(pixels, 0, imgSize, 0, 0, imgSize, imgSize)
        val floats = FloatArray(imgSize * imgSize * 3)
        var i = 0
        for (p in pixels) {
            floats[i++] = ((p shr 16) and 0xFF).toFloat() // R
            floats[i++] = ((p shr 8) and 0xFF).toFloat() // G
            floats[i++] = (p and 0xFF).toFloat() // B
        }
        return floats
    }
}
