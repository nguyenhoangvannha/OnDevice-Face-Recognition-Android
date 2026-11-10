package com.ml.shubham0204.facenet_android.coprocessor.server

import com.ml.shubham0204.facenet_android.engine.EnrollResult
import com.ml.shubham0204.facenet_android.engine.PersonSummary
import com.ml.shubham0204.facenet_android.engine.RecognitionResult
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val API_VERSION = 1

val ApiJson =
    Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = true
    }

data class ApiLimits(
    /** Max request body (Content-Length) and max total bytes read from multipart parts. */
    val maxRequestBytes: Long = 8L * 1024 * 1024,
    val maxEnrollImages: Int = 10,
    val maxNameLength: Int = 64,
)

/** Recognition result plus the time spent decoding the upload. */
data class RecognizeOutcome(
    val result: RecognitionResult,
    val decodeMs: Long,
)

class InvalidImageException(
    message: String = "Image could not be decoded",
) : Exception(message)

class PayloadTooLargeException(
    limitBytes: Long,
) : Exception("Request body exceeds $limitBytes bytes")

class UnsupportedMediaTypeApiException : Exception("Content-Type must be multipart/form-data")

@Serializable
data class ApiError(
    val error: String,
    val message: String,
)

@Serializable
data class HealthResponse(
    val status: String = "ok",
    val service: String = "facenet-coprocessor",
    @SerialName("api_version") val apiVersion: Int = API_VERSION,
)

@Serializable
data class StatusResponse(
    @SerialName("battery_pct") val batteryPct: Int?,
    @SerialName("battery_temp_c") val batteryTempC: Float?,
    val charging: Boolean?,
    @SerialName("thermal_status") val thermalStatus: String?,
    @SerialName("heap_used_mb") val heapUsedMb: Long,
    @SerialName("heap_max_mb") val heapMaxMb: Long,
    @SerialName("ignoring_battery_optimizations") val ignoringBatteryOptimizations: Boolean,
    val persons: Long,
    @SerialName("face_records") val faceRecords: Long,
    val threshold: Float,
    @SerialName("uptime_s") val uptimeSeconds: Long,
    @SerialName("requests_served") val requestsServed: Long,
    @SerialName("android_sdk") val androidSdk: Int,
)

@Serializable
data class BoxDto(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

@Serializable
data class SpoofDto(
    @SerialName("is_spoof") val isSpoof: Boolean,
    val score: Float,
)

@Serializable
data class FaceDto(
    val bbox: BoxDto,
    @SerialName("person_id") val personId: Long?,
    val name: String?,
    val similarity: Float,
    val recognized: Boolean,
    val spoof: SpoofDto? = null,
)

@Serializable
data class TimingsDto(
    @SerialName("decode_ms") val decodeMs: Long,
    @SerialName("detection_ms") val detectionMs: Long,
    @SerialName("embedding_ms") val embeddingMs: Long,
    @SerialName("search_ms") val searchMs: Long,
    @SerialName("spoof_ms") val spoofMs: Long,
)

@Serializable
data class RecognizeResponse(
    val faces: List<FaceDto>,
    @SerialName("face_count") val faceCount: Int,
    @SerialName("recognized_count") val recognizedCount: Int,
    val threshold: Float,
    @SerialName("inference_ms") val inferenceMs: Long,
    val timings: TimingsDto,
    @SerialName("image_width") val imageWidth: Int,
    @SerialName("image_height") val imageHeight: Int,
)

@Serializable
data class RejectedImageDto(
    val index: Int,
    val reason: String,
)

@Serializable
data class EnrollResponse(
    @SerialName("person_id") val personId: Long?,
    val name: String,
    @SerialName("images_accepted") val imagesAccepted: Int,
    @SerialName("images_rejected") val imagesRejected: List<RejectedImageDto>,
)

@Serializable
data class PersonDto(
    @SerialName("person_id") val personId: Long,
    val name: String,
    @SerialName("num_images") val numImages: Long,
    @SerialName("added_at_ms") val addedAtMs: Long,
)

@Serializable
data class PersonsResponse(
    val persons: List<PersonDto>,
    val count: Int,
)

@Serializable
data class DeleteResponse(
    val deleted: Long,
)

fun RecognizeOutcome.toResponse(): RecognizeResponse =
    RecognizeResponse(
        faces =
            result.faces.map {
                FaceDto(
                    bbox = BoxDto(it.box.left, it.box.top, it.box.right, it.box.bottom),
                    personId = it.personId,
                    name = it.name,
                    similarity = it.similarity,
                    recognized = it.isRecognized,
                    spoof = it.spoof?.let { s -> SpoofDto(s.isSpoof, s.score) },
                )
            },
        faceCount = result.faces.size,
        recognizedCount = result.faces.count { it.isRecognized },
        threshold = result.threshold,
        inferenceMs = result.inferenceTimeMs,
        timings =
            TimingsDto(
                decodeMs = decodeMs,
                detectionMs = result.timings.detectionMs,
                embeddingMs = result.timings.embeddingMs,
                searchMs = result.timings.searchMs,
                spoofMs = result.timings.spoofMs,
            ),
        imageWidth = result.imageWidth,
        imageHeight = result.imageHeight,
    )

fun EnrollResult.toResponse(): EnrollResponse =
    EnrollResponse(
        personId = personId,
        name = name,
        imagesAccepted = imagesAccepted,
        imagesRejected = rejected.map { RejectedImageDto(it.index, it.reason) },
    )

fun PersonSummary.toDto(): PersonDto = PersonDto(personId, name, numImages, addTimeMillis)
