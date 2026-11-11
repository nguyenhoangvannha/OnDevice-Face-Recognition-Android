package com.ml.shubham0204.facenet_android.engine

// Android-free result models, so the REST layer can be unit tested on the JVM.

data class FaceBox(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

data class SpoofInfo(
    val isSpoof: Boolean,
    val score: Float,
)

data class RecognizedFace(
    val box: FaceBox,
    /** Set only when [isRecognized] is true. */
    val personId: Long?,
    /** Set only when [isRecognized] is true. */
    val name: String?,
    /** Cosine similarity of the nearest stored embedding (0 when the DB is empty). */
    val similarity: Float,
    val isRecognized: Boolean,
    val spoof: SpoofInfo? = null,
)

data class RecognitionTimings(
    val detectionMs: Long,
    val embeddingMs: Long,
    val searchMs: Long,
    val spoofMs: Long,
)

data class RecognitionResult(
    val faces: List<RecognizedFace>,
    /** Detection + embedding + search (+ spoof) time inside the engine, excluding image decode. */
    val inferenceTimeMs: Long,
    val imageWidth: Int,
    val imageHeight: Int,
    val threshold: Float,
    val timings: RecognitionTimings,
    /** Echo of the request's person box (null when none was sent). */
    val personBox: NormalizedBox? = null,
    /** Faces ML Kit found but dropped before embedding because they were outside [personBox]. */
    val facesOutsidePersonBox: Int = 0,
)

data class RejectedImage(
    val index: Int,
    val reason: String,
)

data class EnrollResult(
    /** Null when no image was accepted; nothing is stored in that case. */
    val personId: Long?,
    val name: String,
    val imagesAccepted: Int,
    val rejected: List<RejectedImage>,
)

data class PersonSummary(
    val personId: Long,
    val name: String,
    val numImages: Long,
    val addTimeMillis: Long,
)
