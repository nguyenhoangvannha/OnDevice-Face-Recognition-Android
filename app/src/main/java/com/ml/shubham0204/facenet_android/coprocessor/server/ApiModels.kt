package com.ml.shubham0204.facenet_android.coprocessor.server

import com.ml.shubham0204.facenet_android.engine.EnrollResult
import com.ml.shubham0204.facenet_android.engine.PersonSummary
import com.ml.shubham0204.facenet_android.engine.RecognitionResult
import com.ml.shubham0204.facenet_android.engine.backend.BackendStatus
import com.ml.shubham0204.facenet_android.engine.backend.ModelSource
import com.ml.shubham0204.facenet_android.engine.backend.ModelSpec
import com.ml.shubham0204.facenet_android.engine.strangers.StrangerCluster
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val API_VERSION = 1

@OptIn(ExperimentalSerializationApi::class)
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
    /** Hardware embeddings run on now (cpu/gpu/npu); null before the model is loaded. */
    @SerialName("inference_backend") val inferenceBackend: String? = null,
    @SerialName("inference_backend_requested") val inferenceBackendRequested: String? = null,
    @SerialName("inference_backend_fallback_reason") val inferenceBackendFallbackReason: String? = null,
    /** Stranger clusters stored (cluster_strangers). */
    val strangers: Long? = null,
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
    /** Variance of the Laplacian on the 112x112 grayscale face crop, 1 decimal. */
    val sharpness: Float? = null,
    /** Null unless cluster_strangers=true; false for household matches. */
    @SerialName("is_stranger") val isStranger: Boolean? = null,
    @SerialName("stranger_id") val strangerId: Long? = null,
    /** new | seen | too_small */
    @SerialName("stranger_status") val strangerStatus: String? = null,
    val appearances: Long? = null,
    @SerialName("stranger_similarity") val strangerSimilarity: Float? = null,
)

@Serializable
data class TimingsDto(
    @SerialName("decode_ms") val decodeMs: Long,
    @SerialName("detection_ms") val detectionMs: Long,
    @SerialName("embedding_ms") val embeddingMs: Long,
    @SerialName("search_ms") val searchMs: Long,
    @SerialName("spoof_ms") val spoofMs: Long,
    @SerialName("sharpness_ms") val sharpnessMs: Long = 0,
    @SerialName("stranger_ms") val strangerMs: Long = 0,
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
    /** Echo of `person_box` as [x, y, w, h]; null when none was sent. */
    @SerialName("person_box") val personBox: List<Float>? = null,
    @SerialName("faces_outside_person_box") val facesOutsidePersonBox: Int = 0,
    /** Echo of `event_id`; null when none was sent. */
    @SerialName("event_id") val eventId: String? = null,
    /** True when this `event_id` was already seen: stranger appearances were not counted again. */
    @SerialName("event_repeat") val eventRepeat: Boolean = false,
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
data class ThresholdResponse(
    val threshold: Float,
)

@Serializable
data class ThresholdRequest(
    val threshold: Float,
)

@Serializable
data class BackendOptionDto(
    val id: String,
    val label: String,
    val available: Boolean,
    /** Why it cannot be selected, when [available] is false. */
    val reason: String?,
    /** bundled | download | none */
    val source: String,
    /** Model file present on the phone (always true for bundled). */
    val downloaded: Boolean,
    @SerialName("size_bytes") val sizeBytes: Long?,
    val precision: String,
)

@Serializable
data class BackendSettingsResponse(
    val requested: String,
    val active: String?,
    /** idle | downloading | loading | ready | failed */
    val phase: String,
    @SerialName("downloaded_bytes") val downloadedBytes: Long,
    @SerialName("total_bytes") val totalBytes: Long?,
    @SerialName("fallback_reason") val fallbackReason: String?,
    val backends: List<BackendOptionDto>,
)

@Serializable
data class BackendRequest(
    val backend: String,
)

fun backendSettingsResponse(
    status: BackendStatus,
    catalog: List<ModelSpec>,
    isLocal: (ModelSpec) -> Boolean,
): BackendSettingsResponse =
    BackendSettingsResponse(
        requested = status.requested.wireName,
        active = status.active?.wireName,
        phase = status.phase.name.lowercase(),
        downloadedBytes = status.downloadedBytes,
        totalBytes = status.totalBytes,
        fallbackReason = status.fallbackReason,
        backends =
            catalog.map { spec ->
                val source = spec.source
                BackendOptionDto(
                    id = spec.backend.wireName,
                    label = spec.backend.label,
                    available = spec.available,
                    reason = (source as? ModelSource.Unavailable)?.reason,
                    source =
                        when (source) {
                            is ModelSource.Asset -> "bundled"
                            is ModelSource.Remote -> "download"
                            is ModelSource.Unavailable -> "none"
                        },
                    downloaded = source is ModelSource.Asset || (spec.available && isLocal(spec)),
                    sizeBytes = spec.sizeBytes,
                    precision = spec.precision,
                )
            },
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
                    // Strangers are named after their cluster; household fields keep their v1 meaning.
                    name = it.name ?: it.stranger?.label,
                    similarity = it.similarity,
                    recognized = it.isRecognized,
                    spoof = it.spoof?.let { s -> SpoofDto(s.isSpoof, s.score) },
                    sharpness = it.sharpness?.let { v -> Math.round(v * 10.0) / 10.0f },
                    isStranger = it.stranger?.isStranger,
                    strangerId = it.stranger?.strangerId,
                    strangerStatus = it.stranger?.status,
                    appearances = it.stranger?.appearances,
                    strangerSimilarity = it.stranger?.similarity,
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
                sharpnessMs = result.timings.sharpnessMs,
                strangerMs = result.timings.strangerMs,
            ),
        imageWidth = result.imageWidth,
        imageHeight = result.imageHeight,
        personBox = result.personBox?.let { listOf(it.x, it.y, it.w, it.h) },
        facesOutsidePersonBox = result.facesOutsidePersonBox,
        eventId = result.eventId,
        eventRepeat = result.eventRepeat,
    )

fun EnrollResult.toResponse(): EnrollResponse =
    EnrollResponse(
        personId = personId,
        name = name,
        imagesAccepted = imagesAccepted,
        imagesRejected = rejected.map { RejectedImageDto(it.index, it.reason) },
    )

fun PersonSummary.toDto(): PersonDto = PersonDto(personId, name, numImages, addTimeMillis)

@Serializable
data class StrangerDto(
    @SerialName("stranger_id") val strangerId: Long,
    val name: String,
    val appearances: Long,
    @SerialName("num_embeddings") val numEmbeddings: Int,
    @SerialName("first_seen_ms") val firstSeenMs: Long,
    @SerialName("last_seen_ms") val lastSeenMs: Long,
)

@Serializable
data class StrangersResponse(
    val strangers: List<StrangerDto>,
    val count: Int,
    @SerialName("max_clusters") val maxClusters: Int,
)

@Serializable
data class PromoteRequest(
    val name: String? = null,
    @SerialName("person_id") val personId: Long? = null,
)

@Serializable
data class PromoteResponse(
    @SerialName("stranger_id") val strangerId: Long,
    @SerialName("stranger_name") val strangerName: String,
    @SerialName("person_id") val personId: Long,
    val name: String,
    val created: Boolean,
    @SerialName("embeddings_moved") val embeddingsMoved: Int,
)

@Serializable
data class StrangerSettingsResponse(
    val threshold: Float,
    @SerialName("max_clusters") val maxClusters: Int,
    @SerialName("max_embeddings_per_cluster") val maxEmbeddingsPerCluster: Int,
    @SerialName("min_face_px") val minFacePx: Int,
)

@Serializable
data class StrangerSettingsRequest(
    val threshold: Float,
)

@Serializable
data class DeleteAllResponse(
    val deleted: Int,
)

fun StrangerCluster.toDto(): StrangerDto = StrangerDto(id, label, appearances, numEmbeddings, firstSeenMs, lastSeenMs)
