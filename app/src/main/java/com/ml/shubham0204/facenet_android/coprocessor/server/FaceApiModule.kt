package com.ml.shubham0204.facenet_android.coprocessor.server

import com.ml.shubham0204.facenet_android.coprocessor.RequestLogEntry
import com.ml.shubham0204.facenet_android.coprocessor.TokenUtils
import com.ml.shubham0204.facenet_android.engine.backend.BackendUnavailableException
import com.ml.shubham0204.facenet_android.engine.backend.InferenceBackend
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.content.streamProvider
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.contentType
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveMultipart
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.put
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.util.AttributeKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream

const val AUTH_BEARER = "api-token"

private const val MAX_JSON_BYTES = 4096L

private val InferenceMsKey = AttributeKey<Long>("inference_ms")
private val FaceCountKey = AttributeKey<Int>("face_count")
private val RecognizedCountKey = AttributeKey<Int>("recognized_count")

private class MultipartPayload(
    val fields: Map<String, String>,
    /** (part name, bytes) in upload order. */
    val files: List<Pair<String?, ByteArray>>,
)

/**
 * The REST API. Pure Ktor (no Android types), so it is tested with `testApplication`.
 *
 * @param tokenProvider read on every request, so regenerating the token takes effect immediately.
 */
fun Application.faceApiModule(
    backend: CoprocessorBackend,
    tokenProvider: () -> String,
    limits: ApiLimits = ApiLimits(),
    onRequest: (RequestLogEntry) -> Unit = {},
    onError: (Throwable) -> Unit = {},
) {
    install(ContentNegotiation) { json(ApiJson) }
    install(StatusPages) {
        exception<PayloadTooLargeException> { call, cause ->
            call.respond(HttpStatusCode.PayloadTooLarge, ApiError("payload_too_large", cause.message ?: ""))
        }
        exception<UnsupportedMediaTypeApiException> { call, cause ->
            call.respond(HttpStatusCode.UnsupportedMediaType, ApiError("unsupported_media_type", cause.message ?: ""))
        }
        exception<InvalidImageException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ApiError("invalid_image", cause.message ?: ""))
        }
        exception<BackendUnavailableException> { call, cause ->
            call.respond(HttpStatusCode.Conflict, ApiError("backend_unavailable", cause.message ?: ""))
        }
        exception<BadRequestException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ApiError("bad_request", "Malformed request"))
        }
        exception<Throwable> { call, cause ->
            onError(cause)
            call.respond(HttpStatusCode.InternalServerError, ApiError("internal_error", "Internal server error"))
        }
    }
    install(Authentication) {
        bearer(AUTH_BEARER) {
            realm = "facenet-coprocessor"
            authenticate { credential ->
                if (TokenUtils.matches(credential.token, tokenProvider())) UserIdPrincipal("client") else null
            }
        }
    }

    intercept(ApplicationCallPipeline.Monitoring) {
        val startNanos = System.nanoTime()
        SecurityHeaders.apply(call)
        try {
            proceed()
        } finally {
            onRequest(
                RequestLogEntry(
                    timeMillis = System.currentTimeMillis(),
                    method = call.request.httpMethod.value,
                    path = call.request.path(),
                    status = call.response.status()?.value ?: 0,
                    durationMs = (System.nanoTime() - startNanos) / 1_000_000,
                    inferenceMs = call.attributes.getOrNull(InferenceMsKey),
                    faceCount = call.attributes.getOrNull(FaceCountKey),
                    recognizedCount = call.attributes.getOrNull(RecognizedCountKey),
                    remoteHost = call.request.origin.remoteHost,
                ),
            )
        }
    }

    routing {
        // Liveness probe only: no token, no device details.
        get("/api/health") { call.respond(HealthResponse()) }

        // Built-in test page: static, no token needed to load it; every API call it makes
        // sends the token the user pastes (kept in sessionStorage).
        WebAssets.routes.forEach { (path, asset) ->
            get(path) {
                val bytes = WebAssets.load(asset)
                if (bytes == null) {
                    call.respond(HttpStatusCode.NotFound, ApiError("not_found", "Asset missing"))
                } else {
                    call.response.header(HttpHeaders.CacheControl, "no-cache")
                    call.respondBytes(bytes, asset.contentType)
                }
            }
        }

        authenticate(AUTH_BEARER) {
            get("/api/status") { call.respond(backend.status()) }

            post("/api/recognize") {
                val payload = call.readMultipart(limits)
                val image = payload.imageParts().firstOrNull()
                if (image == null) {
                    call.respond(HttpStatusCode.BadRequest, ApiError("missing_image", "Send the image as multipart field 'image'"))
                    return@post
                }
                if (image.isEmpty()) throw InvalidImageException("Image is empty")
                val liveness = call.request.queryParameters["liveness"]?.toBooleanStrictOrNull() ?: false
                val outcome = backend.recognize(image, liveness)
                call.attributes.put(InferenceMsKey, outcome.result.inferenceTimeMs)
                call.attributes.put(FaceCountKey, outcome.result.faces.size)
                call.attributes.put(RecognizedCountKey, outcome.result.faces.count { it.isRecognized })
                call.respond(outcome.toResponse())
            }

            post("/api/enroll") {
                val payload = call.readMultipart(limits)
                val name = payload.fields["name"]?.trim().orEmpty()
                if (name.isEmpty() || name.length > limits.maxNameLength) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ApiError("invalid_name", "Field 'name' is required (1..${limits.maxNameLength} chars)"),
                    )
                    return@post
                }
                val images = call.enrollImagesOrRespond(payload, limits) ?: return@post
                val result = backend.enroll(name, images)
                val status = if (result.personId != null) HttpStatusCode.Created else HttpStatusCode.UnprocessableEntity
                call.respond(status, result.toResponse())
            }

            post("/api/persons/{id}/images") {
                val id = call.personIdOrRespond() ?: return@post
                val payload = call.readMultipart(limits)
                val images = call.enrollImagesOrRespond(payload, limits) ?: return@post
                val result = backend.addImages(id, images)
                if (result == null) {
                    call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No person with id $id"))
                    return@post
                }
                val status = if (result.imagesAccepted > 0) HttpStatusCode.OK else HttpStatusCode.UnprocessableEntity
                call.respond(status, result.toResponse())
            }

            get("/api/settings/threshold") { call.respond(ThresholdResponse(backend.getThreshold())) }

            put("/api/settings/threshold") {
                val value =
                    call.receiveSmallJson(ThresholdRequest.serializer())?.threshold
                if (value == null || value.isNaN() || value < 0f || value > 1f) {
                    call.respond(HttpStatusCode.BadRequest, ApiError("invalid_threshold", "Send JSON {\"threshold\": <0..1>}"))
                    return@put
                }
                call.respond(ThresholdResponse(backend.setThreshold(value)))
            }

            get("/api/settings/backend") { call.respond(backend.getBackendSettings()) }

            // Switching is asynchronous (a GPU model may need a ~94 MB download first): 202, then poll GET.
            put("/api/settings/backend") {
                val requested = InferenceBackend.fromWire(call.receiveSmallJson(BackendRequest.serializer())?.backend)
                if (requested == null) {
                    val ids = InferenceBackend.entries.joinToString("|") { it.wireName }
                    call.respond(HttpStatusCode.BadRequest, ApiError("invalid_backend", "Send JSON {\"backend\": \"$ids\"}"))
                    return@put
                }
                call.respond(HttpStatusCode.Accepted, backend.selectBackend(requested))
            }

            get("/api/persons") {
                val persons = backend.listPersons().map { it.toDto() }
                call.respond(PersonsResponse(persons, persons.size))
            }

            delete("/api/persons/{id}") {
                val id = call.personIdOrRespond() ?: return@delete
                if (backend.deletePerson(id)) {
                    call.respond(DeleteResponse(id))
                } else {
                    call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No person with id $id"))
                }
            }
        }
    }
}

/** Small JSON body (<= MAX_JSON_BYTES); null when it is not valid JSON for [serializer]. */
private suspend fun <T> ApplicationCall.receiveSmallJson(serializer: kotlinx.serialization.KSerializer<T>): T? {
    val declared = request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
    if (declared != null && declared > MAX_JSON_BYTES) throw PayloadTooLargeException(MAX_JSON_BYTES)
    return try {
        ApiJson.decodeFromString(serializer, receiveText())
    } catch (e: IllegalArgumentException) { // includes SerializationException
        null
    }
}

/** Parses {id}; responds 400 and returns null when it is not a positive integer. */
private suspend fun ApplicationCall.personIdOrRespond(): Long? {
    val id = parameters["id"]?.toLongOrNull()
    if (id == null || id <= 0) {
        respond(HttpStatusCode.BadRequest, ApiError("invalid_id", "Person id must be a positive integer"))
        return null
    }
    return id
}

/** 1..maxEnrollImages non-empty images; responds 400 and returns null otherwise. */
private suspend fun ApplicationCall.enrollImagesOrRespond(
    payload: MultipartPayload,
    limits: ApiLimits,
): List<ByteArray>? {
    val images = payload.imageParts()
    if (images.isEmpty()) {
        respond(HttpStatusCode.BadRequest, ApiError("missing_image", "Send 1..${limits.maxEnrollImages} files in field 'image'"))
        return null
    }
    if (images.size > limits.maxEnrollImages) {
        respond(HttpStatusCode.BadRequest, ApiError("too_many_images", "At most ${limits.maxEnrollImages} images"))
        return null
    }
    images.forEachIndexed { i, bytes -> if (bytes.isEmpty()) throw InvalidImageException("Image #$i is empty") }
    return images
}

/** Files named 'image'; if none, any file parts (lenient for simple clients). */
private fun MultipartPayload.imageParts(): List<ByteArray> {
    val named = files.filter { it.first == "image" }.map { it.second }
    return named.ifEmpty { files.map { it.second } }
}

private suspend fun ApplicationCall.readMultipart(limits: ApiLimits): MultipartPayload {
    val declared = request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
    if (declared != null && declared > limits.maxRequestBytes) throw PayloadTooLargeException(limits.maxRequestBytes)
    if (!request.contentType().match(ContentType.MultiPart.FormData)) throw UnsupportedMediaTypeApiException()

    var remaining = limits.maxRequestBytes
    val fields = LinkedHashMap<String, String>()
    val files = ArrayList<Pair<String?, ByteArray>>()
    receiveMultipart().forEachPart { part ->
        try {
            when (part) {
                is PartData.FileItem -> {
                    val bytes = withContext(Dispatchers.IO) { part.streamProvider().use { readLimited(it, remaining, limits) } }
                    remaining -= bytes.size
                    files += part.name to bytes
                }
                is PartData.FormItem -> {
                    remaining -= part.value.length
                    if (remaining < 0) throw PayloadTooLargeException(limits.maxRequestBytes)
                    part.name?.let { fields[it] = part.value }
                }
                else -> Unit
            }
        } finally {
            part.dispose()
        }
    }
    return MultipartPayload(fields, files)
}

private fun readLimited(
    input: InputStream,
    remaining: Long,
    limits: ApiLimits,
): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    var total = 0L
    while (true) {
        val n = input.read(buffer)
        if (n < 0) break
        total += n
        if (total > remaining) throw PayloadTooLargeException(limits.maxRequestBytes)
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}
