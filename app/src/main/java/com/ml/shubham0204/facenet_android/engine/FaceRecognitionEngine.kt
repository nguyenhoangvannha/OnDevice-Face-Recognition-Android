package com.ml.shubham0204.facenet_android.engine

import android.graphics.Bitmap
import com.ml.shubham0204.facenet_android.data.FaceImageRecord
import com.ml.shubham0204.facenet_android.data.ImagesVectorDB
import com.ml.shubham0204.facenet_android.data.PersonDB
import com.ml.shubham0204.facenet_android.data.PersonRecord
import com.ml.shubham0204.facenet_android.domain.AppException
import com.ml.shubham0204.facenet_android.domain.embeddings.FaceNet
import com.ml.shubham0204.facenet_android.domain.face_detection.BaseFaceDetector
import com.ml.shubham0204.facenet_android.domain.face_detection.FaceSpoofDetector
import com.ml.shubham0204.facenet_android.engine.strangers.StrangerClusterer
import org.koin.core.annotation.Single
import kotlin.time.DurationUnit
import kotlin.time.measureTimedValue

/**
 * Face recognition decoupled from CameraX: takes a still [Bitmap] (e.g. decoded from an
 * HTTP upload) and reuses the app's existing detector, FaceNet model and ObjectBox store.
 */
@Single
class FaceRecognitionEngine(
    private val faceDetector: BaseFaceDetector,
    private val faceNet: FaceNet,
    private val imagesVectorDB: ImagesVectorDB,
    private val personDB: PersonDB,
    private val faceSpoofDetector: FaceSpoofDetector,
    val strangers: StrangerClusterer,
) {
    suspend fun recognize(
        bitmap: Bitmap,
        threshold: Float,
        options: RecognizeOptions = RecognizeOptions(),
        strangerThreshold: Float = threshold,
    ): RecognitionResult {
        val withLiveness = options.withLiveness
        val event = options.eventId?.let { strangers.beginEvent(it) }
        val (allDetections, detectionTime) =
            measureTimedValue { faceDetector.getAllCroppedFaces(bitmap, highAccuracy = true) }
        // Drop faces outside the client's person box before paying for an embedding.
        val detections =
            options.personBox?.let { box ->
                allDetections.filter { (_, r) ->
                    PersonBoxFilter.keeps(FaceBox(r.left, r.top, r.right, r.bottom), box, bitmap.width, bitmap.height)
                }
            } ?: allDetections
        var embeddingMs = 0L
        var searchMs = 0L
        var spoofMs = 0L
        var sharpnessMs = 0L
        var strangerMs = 0L
        val faces = ArrayList<RecognizedFace>(detections.size)

        for ((croppedFace, rect) in detections) {
            val (sharpness, tSharp) = measureTimedValue { sharpnessOf(croppedFace) }
            sharpnessMs += tSharp.toLong(DurationUnit.MILLISECONDS)

            val (embedding, tEmbed) = measureTimedValue { faceNet.getFaceEmbedding(croppedFace) }
            embeddingMs += tEmbed.toLong(DurationUnit.MILLISECONDS)

            // Exact (flat) search: the household DB is small, so precision beats ANN speed.
            val (nearest, tSearch) =
                measureTimedValue { imagesVectorDB.getNearestEmbeddingPersonName(embedding, flatSearch = true) }
            searchMs += tSearch.toLong(DurationUnit.MILLISECONDS)

            val similarity =
                nearest?.let { SimilarityMath.cosineSimilarity(embedding, it.faceEmbedding) } ?: 0f
            val recognized = nearest != null && SimilarityMath.isMatch(similarity, threshold)

            val spoof =
                if (withLiveness) {
                    val result = faceSpoofDetector.detectSpoof(bitmap, rect)
                    spoofMs += result.timeMillis
                    SpoofInfo(isSpoof = result.isSpoof, score = result.score)
                } else {
                    null
                }

            val (stranger, tStranger) =
                measureTimedValue {
                    when {
                        !options.clusterStrangers -> null
                        recognized -> StrangerInfo(isStranger = false)
                        else -> {
                            val match = strangers.assign(embedding, minOf(rect.width(), rect.height()), strangerThreshold, sharpness, event)
                            StrangerInfo(
                                isStranger = true,
                                strangerId = match.cluster?.id,
                                label = match.cluster?.label,
                                status = match.status.wireName,
                                appearances = match.cluster?.appearances,
                                similarity = match.similarity,
                            )
                        }
                    }
                }
            strangerMs += tStranger.toLong(DurationUnit.MILLISECONDS)

            faces +=
                RecognizedFace(
                    box = FaceBox(rect.left, rect.top, rect.right, rect.bottom),
                    personId = if (recognized) nearest!!.personID else null,
                    name = if (recognized) nearest!!.personName else null,
                    similarity = similarity,
                    isRecognized = recognized,
                    spoof = spoof,
                    sharpness = sharpness,
                    stranger = stranger,
                )
        }

        val detectionMs = detectionTime.toLong(DurationUnit.MILLISECONDS)
        return RecognitionResult(
            faces = faces,
            inferenceTimeMs = detectionMs + embeddingMs + searchMs + spoofMs + sharpnessMs + strangerMs,
            imageWidth = bitmap.width,
            imageHeight = bitmap.height,
            threshold = threshold,
            timings = RecognitionTimings(detectionMs, embeddingMs, searchMs, spoofMs, sharpnessMs, strangerMs),
            personBox = options.personBox,
            facesOutsidePersonBox = allDetections.size - detections.size,
            eventId = event?.eventId,
            eventRepeat = event?.repeat ?: false,
        )
    }

    private fun sharpnessOf(face: Bitmap): Float {
        val pixels = IntArray(face.width * face.height)
        face.getPixels(pixels, 0, face.width, 0, 0, face.width, face.height)
        return Sharpness.laplacianVariance(pixels, face.width, face.height).toFloat()
    }

    /**
     * Registers a new person. Each image must contain exactly one face. The person is stored
     * only if at least one image is accepted.
     */
    suspend fun enroll(
        name: String,
        images: List<Bitmap>,
    ): EnrollResult {
        val (embeddings, rejected) = embedSingleFaces(images)
        if (embeddings.isEmpty()) {
            return EnrollResult(personId = null, name = name, imagesAccepted = 0, rejected = rejected)
        }
        val personId =
            personDB.addPerson(
                PersonRecord(
                    personName = name,
                    numImages = embeddings.size.toLong(),
                    addTime = System.currentTimeMillis(),
                ),
            )
        embeddings.forEach {
            imagesVectorDB.addFaceImageRecord(
                FaceImageRecord(personID = personId, personName = name, faceEmbedding = it),
            )
        }
        return EnrollResult(personId, name, embeddings.size, rejected)
    }

    /**
     * Adds face images to an existing person (same one-face-per-image rule as [enroll]).
     * Returns null when the person does not exist; imagesAccepted = 0 means nothing was stored.
     */
    suspend fun addImages(
        personId: Long,
        images: List<Bitmap>,
    ): EnrollResult? {
        val person = personDB.get(personId) ?: return null
        val (embeddings, rejected) = embedSingleFaces(images)
        if (embeddings.isNotEmpty()) {
            embeddings.forEach {
                imagesVectorDB.addFaceImageRecord(
                    FaceImageRecord(personID = personId, personName = person.personName, faceEmbedding = it),
                )
            }
            person.numImages += embeddings.size
            personDB.addPerson(person) // put() with an existing id updates the record
        }
        return EnrollResult(personId, person.personName, embeddings.size, rejected)
    }

    private suspend fun embedSingleFaces(images: List<Bitmap>): Pair<List<FloatArray>, List<RejectedImage>> {
        val embeddings = ArrayList<FloatArray>()
        val rejected = ArrayList<RejectedImage>()
        images.forEachIndexed { index, image ->
            faceDetector
                .getCroppedFace(image)
                .onSuccess { embeddings += faceNet.getFaceEmbedding(it) }
                .onFailure {
                    val reason = (it as? AppException)?.errorCode?.message ?: "Face detection failed"
                    rejected += RejectedImage(index, reason)
                }
        }
        return embeddings to rejected
    }

    fun listPersons(): List<PersonSummary> =
        personDB.getAllOnce().map {
            PersonSummary(it.personID, it.personName, it.numImages, it.addTime)
        }

    /** Returns false when the person does not exist. */
    fun deletePerson(personId: Long): Boolean {
        if (personDB.get(personId) == null) return false
        personDB.removePerson(personId)
        imagesVectorDB.removeFaceRecordsWithPersonID(personId)
        return true
    }

    fun countPersons(): Long = personDB.getCount()

    fun countFaceRecords(): Long = imagesVectorDB.count()
}
