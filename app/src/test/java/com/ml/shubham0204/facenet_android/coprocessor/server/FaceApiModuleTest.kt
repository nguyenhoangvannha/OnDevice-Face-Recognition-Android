package com.ml.shubham0204.facenet_android.coprocessor.server

import com.ml.shubham0204.facenet_android.coprocessor.RequestLogEntry
import com.ml.shubham0204.facenet_android.engine.EnrollResult
import com.ml.shubham0204.facenet_android.engine.FaceBox
import com.ml.shubham0204.facenet_android.engine.PersonSummary
import com.ml.shubham0204.facenet_android.engine.RecognitionResult
import com.ml.shubham0204.facenet_android.engine.RecognitionTimings
import com.ml.shubham0204.facenet_android.engine.RecognizedFace
import com.ml.shubham0204.facenet_android.engine.RejectedImage
import com.ml.shubham0204.facenet_android.engine.backend.BackendPhase
import com.ml.shubham0204.facenet_android.engine.backend.BackendStatus
import com.ml.shubham0204.facenet_android.engine.backend.BackendUnavailableException
import com.ml.shubham0204.facenet_android.engine.backend.InferenceBackend
import com.ml.shubham0204.facenet_android.engine.backend.ModelCatalog
import io.ktor.client.request.delete
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val TOKEN = "test-token-not-a-secret"

private class FakeBackend : CoprocessorBackend {
    val persons = linkedMapOf(1L to PersonSummary(1, "An", 2, 1000))
    var lastLiveness: Boolean? = null

    override suspend fun recognize(
        image: ByteArray,
        withLiveness: Boolean,
    ): RecognizeOutcome {
        if (image.decodeToString() == "not-an-image") throw InvalidImageException()
        lastLiveness = withLiveness
        val faces =
            listOf(
                RecognizedFace(FaceBox(1, 2, 30, 40), 1, "An", 0.82f, true),
                RecognizedFace(FaceBox(50, 60, 90, 99), null, null, 0.21f, false),
            )
        return RecognizeOutcome(RecognitionResult(faces, 42, 640, 480, 0.7f, RecognitionTimings(10, 25, 7, 0)), 3)
    }

    override suspend fun enroll(
        name: String,
        images: List<ByteArray>,
    ): EnrollResult {
        if (name == "Nobody") return EnrollResult(null, name, 0, images.indices.map { RejectedImage(it, "No faces were in the image") })
        persons[2] = PersonSummary(2, name, images.size.toLong(), 2000)
        return EnrollResult(2, name, images.size, emptyList())
    }

    override suspend fun addImages(
        personId: Long,
        images: List<ByteArray>,
    ): EnrollResult? {
        val person = persons[personId] ?: return null
        if (images.all { it.decodeToString() == "no-face" }) {
            return EnrollResult(personId, person.name, 0, images.indices.map { RejectedImage(it, "No faces were in the image") })
        }
        persons[personId] = person.copy(numImages = person.numImages + images.size)
        return EnrollResult(personId, person.name, images.size, emptyList())
    }

    override suspend fun listPersons(): List<PersonSummary> = persons.values.toList()

    override suspend fun deletePerson(personId: Long): Boolean = persons.remove(personId) != null

    var storedThreshold = 0.7f

    override fun getThreshold(): Float = storedThreshold

    override fun setThreshold(value: Float): Float {
        storedThreshold = value
        return storedThreshold
    }

    var backendStatus = BackendStatus(InferenceBackend.CPU, InferenceBackend.CPU, BackendPhase.READY)

    override fun getBackendSettings(): BackendSettingsResponse =
        backendSettingsResponse(backendStatus, ModelCatalog.all) { false }

    override fun selectBackend(backend: InferenceBackend): BackendSettingsResponse {
        if (!ModelCatalog.spec(backend).available) throw BackendUnavailableException(backend, "not shipped")
        backendStatus = backendStatus.copy(requested = backend, phase = BackendPhase.DOWNLOADING, totalBytes = 100)
        return getBackendSettings()
    }

    override suspend fun status(): StatusResponse = StatusResponse(80, 31.5f, true, "none", 40, 256, true, 1, 2, 0.7f, 5, 9, 33)
}

class FaceApiModuleTest {
    private val backend = FakeBackend()
    private val log = mutableListOf<RequestLogEntry>()

    private fun api(block: suspend ApplicationTestBuilder.() -> Unit) =
        testApplication {
            application {
                faceApiModule(
                    backend = backend,
                    tokenProvider = { TOKEN },
                    limits = ApiLimits(maxRequestBytes = 4096, maxEnrollImages = 2),
                    onRequest = { log += it },
                )
            }
            block()
        }

    private fun imagePart(bytes: ByteArray, field: String = "image") =
        formData {
            append(
                field,
                bytes,
                Headers.build {
                    append(HttpHeaders.ContentType, "image/jpeg")
                    append(HttpHeaders.ContentDisposition, "filename=\"snap.jpg\"")
                },
            )
        }

    private suspend fun HttpResponse.json(): JsonObject = ApiJson.parseToJsonElement(bodyAsText()).jsonObject

    @Test
    fun healthNeedsNoTokenAndLeaksNoDeviceInfo() =
        api {
            val res = client.get("/api/health")
            assertEquals(HttpStatusCode.OK, res.status)
            val body = res.json()
            assertEquals("ok", body["status"]!!.jsonPrimitive.content)
            assertFalse(body.containsKey("battery_pct"))
        }

    @Test
    fun protectedEndpointsRejectMissingOrWrongToken() =
        api {
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/status").status)
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/persons") { header(HttpHeaders.Authorization, "Bearer wrong") }.status)
            assertEquals(HttpStatusCode.Unauthorized, client.post("/api/recognize") { setBody(MultiPartFormDataContent(imagePart(byteArrayOf(1)))) }.status)
            assertEquals(HttpStatusCode.Unauthorized, client.delete("/api/persons/1").status)
            assertEquals(HttpStatusCode.OK, client.get("/api/status") { header(HttpHeaders.Authorization, "Bearer $TOKEN") }.status)
        }

    @Test
    fun recognizeReturnsSnakeCaseResult() =
        api {
            val res =
                client.post("/api/recognize?liveness=true") {
                    header(HttpHeaders.Authorization, "Bearer $TOKEN")
                    setBody(MultiPartFormDataContent(imagePart("jpeg-bytes".toByteArray())))
                }
            assertEquals(HttpStatusCode.OK, res.status)
            val body = res.json()
            assertEquals(42L, body["inference_ms"]!!.jsonPrimitive.long)
            assertEquals(2, body["face_count"]!!.jsonPrimitive.int)
            assertEquals(1, body["recognized_count"]!!.jsonPrimitive.int)
            assertEquals(3L, body["timings"]!!.jsonObject["decode_ms"]!!.jsonPrimitive.long)
            val first = body["faces"]!!.jsonArray[0].jsonObject
            assertEquals("An", first["name"]!!.jsonPrimitive.content)
            assertEquals(1L, first["person_id"]!!.jsonPrimitive.long)
            assertEquals(true, backend.lastLiveness)
            val last = log.last()
            assertEquals("/api/recognize", last.path)
            assertEquals(42L, last.inferenceMs)
            assertEquals(2, last.faceCount)
        }

    @Test
    fun recognizeValidatesInput() =
        api {
            val auth: io.ktor.client.request.HttpRequestBuilder.() -> Unit = { header(HttpHeaders.Authorization, "Bearer $TOKEN") }
            val missing = client.post("/api/recognize") { auth(); setBody(MultiPartFormDataContent(formData { append("name", "x") })) }
            assertEquals(HttpStatusCode.BadRequest, missing.status)
            assertEquals("missing_image", missing.json()["error"]!!.jsonPrimitive.content)

            val bad = client.post("/api/recognize") { auth(); setBody(MultiPartFormDataContent(imagePart("not-an-image".toByteArray()))) }
            assertEquals(HttpStatusCode.BadRequest, bad.status)
            assertEquals("invalid_image", bad.json()["error"]!!.jsonPrimitive.content)

            val empty = client.post("/api/recognize") { auth(); setBody(MultiPartFormDataContent(imagePart(ByteArray(0)))) }
            assertEquals(HttpStatusCode.BadRequest, empty.status)

            val big = client.post("/api/recognize") { auth(); setBody(MultiPartFormDataContent(imagePart(ByteArray(8192)))) }
            assertEquals(HttpStatusCode.PayloadTooLarge, big.status)

            val notMultipart = client.post("/api/recognize") { auth(); contentType(ContentType.Application.Json); setBody("{}") }
            assertEquals(HttpStatusCode.UnsupportedMediaType, notMultipart.status)
        }

    @Test
    fun enrollFlow() =
        api {
            val auth: io.ktor.client.request.HttpRequestBuilder.() -> Unit = { header(HttpHeaders.Authorization, "Bearer $TOKEN") }
            fun form(name: String?, count: Int) =
                MultiPartFormDataContent(
                    formData {
                        if (name != null) append("name", name)
                        repeat(count) {
                            append("image", byteArrayOf(1, 2, 3), Headers.build { append(HttpHeaders.ContentDisposition, "filename=\"$it.jpg\"") })
                        }
                    },
                )
            assertEquals(HttpStatusCode.BadRequest, client.post("/api/enroll") { auth(); setBody(form(null, 1)) }.status)
            assertEquals(HttpStatusCode.BadRequest, client.post("/api/enroll") { auth(); setBody(form("Binh", 0)) }.status)
            val tooMany = client.post("/api/enroll") { auth(); setBody(form("Binh", 3)) }
            assertEquals("too_many_images", tooMany.json()["error"]!!.jsonPrimitive.content)

            val none = client.post("/api/enroll") { auth(); setBody(form("Nobody", 1)) }
            assertEquals(HttpStatusCode.UnprocessableEntity, none.status)
            assertEquals(1, none.json()["images_rejected"]!!.jsonArray.size)

            val ok = client.post("/api/enroll") { auth(); setBody(form("Binh", 2)) }
            assertEquals(HttpStatusCode.Created, ok.status)
            assertEquals(2L, ok.json()["person_id"]!!.jsonPrimitive.long)
        }

    @Test
    fun addImagesToExistingPerson() =
        api {
            val auth: io.ktor.client.request.HttpRequestBuilder.() -> Unit = { header(HttpHeaders.Authorization, "Bearer $TOKEN") }
            fun form(vararg images: String) =
                MultiPartFormDataContent(
                    formData {
                        images.forEachIndexed { i, content ->
                            append("image", content.toByteArray(), Headers.build { append(HttpHeaders.ContentDisposition, "filename=\"$i.jpg\"") })
                        }
                    },
                )
            assertEquals(HttpStatusCode.Unauthorized, client.post("/api/persons/1/images") { setBody(form("a")) }.status)
            assertEquals(HttpStatusCode.BadRequest, client.post("/api/persons/x/images") { auth(); setBody(form("a")) }.status)
            assertEquals(HttpStatusCode.NotFound, client.post("/api/persons/99/images") { auth(); setBody(form("a")) }.status)
            assertEquals("missing_image", client.post("/api/persons/1/images") { auth(); setBody(form()) }.json()["error"]!!.jsonPrimitive.content)
            assertEquals("too_many_images", client.post("/api/persons/1/images") { auth(); setBody(form("a", "b", "c")) }.json()["error"]!!.jsonPrimitive.content)

            val none = client.post("/api/persons/1/images") { auth(); setBody(form("no-face")) }
            assertEquals(HttpStatusCode.UnprocessableEntity, none.status)
            assertEquals(0, none.json()["images_accepted"]!!.jsonPrimitive.int)

            val ok = client.post("/api/persons/1/images") { auth(); setBody(form("a", "b")) }
            assertEquals(HttpStatusCode.OK, ok.status)
            assertEquals(2, ok.json()["images_accepted"]!!.jsonPrimitive.int)
            assertEquals(4L, backend.persons[1]!!.numImages)
        }

    @Test
    fun thresholdApi() =
        api {
            val auth: io.ktor.client.request.HttpRequestBuilder.() -> Unit = { header(HttpHeaders.Authorization, "Bearer $TOKEN") }
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/settings/threshold").status)
            assertEquals(HttpStatusCode.Unauthorized, client.put("/api/settings/threshold") { contentType(ContentType.Application.Json); setBody("{\"threshold\":0.5}") }.status)
            assertEquals("0.7", client.get("/api/settings/threshold") { auth() }.json()["threshold"]!!.jsonPrimitive.content)

            val ok = client.put("/api/settings/threshold") { auth(); contentType(ContentType.Application.Json); setBody("{\"threshold\":0.65}") }
            assertEquals(HttpStatusCode.OK, ok.status)
            assertEquals(0.65f, backend.storedThreshold, 1e-6f)

            for (bad in listOf("{\"threshold\":1.5}", "{\"threshold\":-0.1}", "{}", "not json")) {
                val res = client.put("/api/settings/threshold") { auth(); contentType(ContentType.Application.Json); setBody(bad) }
                assertEquals(bad, HttpStatusCode.BadRequest, res.status)
            }
            assertEquals(0.65f, backend.storedThreshold, 1e-6f)
        }

    @Test
    fun backendApi() =
        api {
            val auth: io.ktor.client.request.HttpRequestBuilder.() -> Unit = { header(HttpHeaders.Authorization, "Bearer $TOKEN") }
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/settings/backend").status)
            assertEquals(HttpStatusCode.Unauthorized, client.put("/api/settings/backend") { contentType(ContentType.Application.Json); setBody("{\"backend\":\"gpu\"}") }.status)

            val current = client.get("/api/settings/backend") { auth() }.json()
            assertEquals("cpu", current["requested"]!!.jsonPrimitive.content)
            assertEquals("cpu", current["active"]!!.jsonPrimitive.content)
            assertEquals("ready", current["phase"]!!.jsonPrimitive.content)
            val options = current["backends"]!!.jsonArray.map { it.jsonObject }
            assertEquals(listOf("cpu", "gpu", "npu"), options.map { it["id"]!!.jsonPrimitive.content })
            assertEquals(listOf("bundled", "download", "none"), options.map { it["source"]!!.jsonPrimitive.content })
            assertEquals("false", options[2]["available"]!!.jsonPrimitive.content)

            val gpu = client.put("/api/settings/backend") { auth(); contentType(ContentType.Application.Json); setBody("{\"backend\":\"GPU\"}") }
            assertEquals(HttpStatusCode.Accepted, gpu.status)
            assertEquals("gpu", gpu.json()["requested"]!!.jsonPrimitive.content)
            assertEquals("downloading", gpu.json()["phase"]!!.jsonPrimitive.content)

            val npu = client.put("/api/settings/backend") { auth(); contentType(ContentType.Application.Json); setBody("{\"backend\":\"npu\"}") }
            assertEquals(HttpStatusCode.Conflict, npu.status)
            assertEquals("backend_unavailable", npu.json()["error"]!!.jsonPrimitive.content)

            for (bad in listOf("{\"backend\":\"tpu\"}", "{}", "not json")) {
                val res = client.put("/api/settings/backend") { auth(); contentType(ContentType.Application.Json); setBody(bad) }
                assertEquals(bad, HttpStatusCode.BadRequest, res.status)
                assertEquals("invalid_backend", res.json()["error"]!!.jsonPrimitive.content)
            }
            val big = client.put("/api/settings/backend") { auth(); contentType(ContentType.Application.Json); setBody("{\"backend\":\"" + "x".repeat(5000) + "\"}") }
            assertEquals(HttpStatusCode.PayloadTooLarge, big.status)
            assertEquals(InferenceBackend.GPU, backend.backendStatus.requested)
        }

    @Test
    fun webPageIsServedWithoutTokenAndWithSecurityHeaders() =
        api {
            val page = client.get("/")
            assertEquals(HttpStatusCode.OK, page.status)
            assertTrue(page.contentType()!!.match(ContentType.Text.Html))
            val html = page.bodyAsText()
            assertTrue(html.contains("<script src=\"/app.js\"></script>"))
            assertFalse("no external URLs", Regex("(src|href)=\"(https?:)?//").containsMatchIn(html))
            assertTrue(page.headers["Content-Security-Policy"]!!.startsWith("default-src 'self'"))
            assertEquals("nosniff", page.headers["X-Content-Type-Options"])

            val js = client.get("/app.js")
            assertEquals(HttpStatusCode.OK, js.status)
            assertTrue(js.contentType()!!.match(ContentType.Text.JavaScript))
            assertTrue(js.bodyAsText().contains("sessionStorage"))
            assertTrue(client.get("/app.css").contentType()!!.match(ContentType.Text.CSS))

            assertEquals(HttpStatusCode.NotFound, client.get("/index.html").status)
            assertEquals(HttpStatusCode.NotFound, client.get("/../build.gradle.kts").status)
        }

    @Test
    fun noCorsHeadersEvenWithOrigin() =
        api {
            val res =
                client.get("/api/persons") {
                    header(HttpHeaders.Authorization, "Bearer $TOKEN")
                    header(HttpHeaders.Origin, "http://evil.example")
                }
            assertEquals(HttpStatusCode.OK, res.status)
            assertEquals(null, res.headers[HttpHeaders.AccessControlAllowOrigin])
            assertEquals("nosniff", res.headers["X-Content-Type-Options"])
            val preflight =
                client.options("/api/recognize") {
                    header(HttpHeaders.Origin, "http://evil.example")
                    header(HttpHeaders.AccessControlRequestMethod, "POST")
                }
            assertEquals(null, preflight.headers[HttpHeaders.AccessControlAllowOrigin])
        }

    @Test
    fun personsListAndDelete() =
        api {
            val auth: io.ktor.client.request.HttpRequestBuilder.() -> Unit = { header(HttpHeaders.Authorization, "Bearer $TOKEN") }
            val list = client.get("/api/persons") { auth() }.json()
            assertEquals(1, list["count"]!!.jsonPrimitive.int)
            assertEquals("An", list["persons"]!!.jsonArray[0].jsonObject["name"]!!.jsonPrimitive.content)

            assertEquals(HttpStatusCode.BadRequest, client.delete("/api/persons/abc") { auth() }.status)
            assertEquals(HttpStatusCode.NotFound, client.delete("/api/persons/99") { auth() }.status)
            assertEquals(HttpStatusCode.OK, client.delete("/api/persons/1") { auth() }.status)
            assertTrue(backend.persons.isEmpty())
        }
}
