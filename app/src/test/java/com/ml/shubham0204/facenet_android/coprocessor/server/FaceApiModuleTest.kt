package com.ml.shubham0204.facenet_android.coprocessor.server

import com.ml.shubham0204.facenet_android.coprocessor.RequestLogEntry
import com.ml.shubham0204.facenet_android.engine.EnrollResult
import com.ml.shubham0204.facenet_android.engine.FaceBox
import com.ml.shubham0204.facenet_android.engine.PersonSummary
import com.ml.shubham0204.facenet_android.engine.RecognitionResult
import com.ml.shubham0204.facenet_android.engine.RecognitionTimings
import com.ml.shubham0204.facenet_android.engine.RecognizedFace
import com.ml.shubham0204.facenet_android.engine.RecognizeOptions
import com.ml.shubham0204.facenet_android.engine.NormalizedBox
import com.ml.shubham0204.facenet_android.engine.RejectedImage
import com.ml.shubham0204.facenet_android.engine.backend.BackendPhase
import com.ml.shubham0204.facenet_android.engine.backend.BackendStatus
import com.ml.shubham0204.facenet_android.engine.backend.BackendUnavailableException
import com.ml.shubham0204.facenet_android.engine.backend.InferenceBackend
import com.ml.shubham0204.facenet_android.engine.backend.ModelCatalog
import com.ml.shubham0204.facenet_android.engine.StrangerInfo
import com.ml.shubham0204.facenet_android.engine.strangers.FakeHousehold
import com.ml.shubham0204.facenet_android.engine.strangers.InMemoryStrangerStore
import com.ml.shubham0204.facenet_android.engine.strangers.PromoteResult
import com.ml.shubham0204.facenet_android.engine.strangers.PromoteTarget
import com.ml.shubham0204.facenet_android.engine.strangers.StrangerCluster
import com.ml.shubham0204.facenet_android.engine.strangers.StrangerClusterer
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
    var lastOptions: RecognizeOptions? = null

    override suspend fun recognize(
        image: ByteArray,
        options: RecognizeOptions,
    ): RecognizeOutcome {
        if (image.decodeToString() == "not-an-image") throw InvalidImageException()
        lastLiveness = options.withLiveness
        lastOptions = options
        val unknown = RecognizedFace(FaceBox(50, 60, 90, 99), null, null, 0.21f, false)
        val faces =
            if (!options.clusterStrangers) {
                listOf(RecognizedFace(FaceBox(1, 2, 30, 40), 1, "An", 0.82f, true, sharpness = 168.44f), unknown)
            } else {
                val m = strangers.assign(floatArrayOf(1f, 0f, 0f), 60, strangerThreshold)
                listOf(
                    RecognizedFace(FaceBox(1, 2, 30, 40), 1, "An", 0.82f, true, stranger = StrangerInfo(isStranger = false)),
                    unknown.copy(
                        stranger = StrangerInfo(true, m.cluster?.id, m.cluster?.label, m.status.wireName, m.cluster?.appearances, m.similarity),
                    ),
                )
            }
        val dropped = if (options.personBox != null) 1 else 0
        return RecognizeOutcome(
            RecognitionResult(faces, 42, 640, 480, 0.7f, RecognitionTimings(10, 25, 7, 0), options.personBox, dropped),
            3,
        )
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

    val household = FakeHousehold()
    val strangers = StrangerClusterer(InMemoryStrangerStore(), household)
    var strangerThreshold = 0.7f

    override suspend fun listStrangers(): List<StrangerCluster> = strangers.list()

    override suspend fun promoteStranger(
        strangerId: Long,
        target: PromoteTarget,
    ): PromoteResult = strangers.promote(strangerId, target)

    override suspend fun deleteStranger(strangerId: Long): Boolean = strangers.delete(strangerId)

    override suspend fun deleteAllStrangers(): Int = strangers.deleteAll()

    override fun getStrangerSettings(): StrangerSettingsResponse = StrangerSettingsResponse(strangerThreshold, 200, 10, 40)

    override fun setStrangerThreshold(value: Float): StrangerSettingsResponse {
        strangerThreshold = value
        return getStrangerSettings()
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
            assertEquals("168.4", first["sharpness"]!!.jsonPrimitive.content)
            assertEquals("null", body["faces"]!!.jsonArray[1].jsonObject["sharpness"].toString())
            assertEquals(0L, body["timings"]!!.jsonObject["sharpness_ms"]!!.jsonPrimitive.long)
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
    fun recognizeAcceptsPersonBoxInQueryOrForm() =
        api {
            val plain =
                client.post("/api/recognize") {
                    header(HttpHeaders.Authorization, "Bearer $TOKEN")
                    setBody(MultiPartFormDataContent(imagePart("jpeg".toByteArray())))
                }.json()
            assertEquals(null, backend.lastOptions!!.personBox)
            assertEquals("null", plain["person_box"].toString())
            assertEquals(0, plain["faces_outside_person_box"]!!.jsonPrimitive.int)

            val query =
                client.post("/api/recognize?person_box=0.25,0.10,0.30,0.65") {
                    header(HttpHeaders.Authorization, "Bearer $TOKEN")
                    setBody(MultiPartFormDataContent(imagePart("jpeg".toByteArray())))
                }
            assertEquals(HttpStatusCode.OK, query.status)
            assertEquals(NormalizedBox(0.25f, 0.10f, 0.30f, 0.65f), backend.lastOptions!!.personBox)
            val body = query.json()
            assertEquals(4, body["person_box"]!!.jsonArray.size)
            assertEquals(1, body["faces_outside_person_box"]!!.jsonPrimitive.int)

            client.post("/api/recognize") {
                header(HttpHeaders.Authorization, "Bearer $TOKEN")
                setBody(
                    MultiPartFormDataContent(
                        formData {
                            append("person_box", "0,0,1,1")
                            append(
                                "image",
                                "jpeg".toByteArray(),
                                Headers.build {
                                    append(HttpHeaders.ContentType, "image/jpeg")
                                    append(HttpHeaders.ContentDisposition, "filename=\"a.jpg\"")
                                },
                            )
                        },
                    ),
                )
            }
            assertEquals(NormalizedBox(0f, 0f, 1f, 1f), backend.lastOptions!!.personBox)
        }

    @Test
    fun recognizeRejectsInvalidPersonBox() =
        api {
            for (bad in listOf("0.1,0.2,0.3", "a,b,c,d", "0.8,0,0.3,0.5", "0,0,0,0.5", "-0.1,0,0.5,0.5", "NaN,0,0.5,0.5")) {
                val res =
                    client.post("/api/recognize?person_box=$bad") {
                        header(HttpHeaders.Authorization, "Bearer $TOKEN")
                        setBody(MultiPartFormDataContent(imagePart("jpeg".toByteArray())))
                    }
                assertEquals(bad, HttpStatusCode.BadRequest, res.status)
                assertEquals("invalid_person_box", res.json()["error"]!!.jsonPrimitive.content)
            }
        }

    @Test
    fun recognizeWithoutClusteringKeepsV1Fields() =
        api {
            val face =
                client.post("/api/recognize") {
                    header(HttpHeaders.Authorization, "Bearer $TOKEN")
                    setBody(MultiPartFormDataContent(imagePart("jpeg".toByteArray())))
                }.json()["faces"]!!.jsonArray[1].jsonObject
            assertEquals("null", face["name"].toString())
            assertEquals("null", face["is_stranger"].toString())
            assertEquals(false, face["recognized"]!!.jsonPrimitive.content.toBoolean())
        }

    @Test
    fun strangerClusteringFlow() =
        api {
            val auth: io.ktor.client.request.HttpRequestBuilder.() -> Unit = { header(HttpHeaders.Authorization, "Bearer $TOKEN") }
            suspend fun recognize() =
                client.post("/api/recognize?cluster_strangers=true") {
                    auth()
                    setBody(MultiPartFormDataContent(imagePart("jpeg".toByteArray())))
                }.json()["faces"]!!.jsonArray

            val first = recognize()
            assertEquals(false, first[0].jsonObject["is_stranger"]!!.jsonPrimitive.content.toBoolean())
            assertEquals("An", first[0].jsonObject["name"]!!.jsonPrimitive.content)
            val s1 = first[1].jsonObject
            assertEquals(true, s1["is_stranger"]!!.jsonPrimitive.content.toBoolean())
            assertEquals("Person_01", s1["name"]!!.jsonPrimitive.content)
            assertEquals("new", s1["stranger_status"]!!.jsonPrimitive.content)
            assertEquals(1L, s1["appearances"]!!.jsonPrimitive.long)
            assertEquals(false, s1["recognized"]!!.jsonPrimitive.content.toBoolean())
            assertEquals("null", s1["person_id"].toString())

            val s2 = recognize()[1].jsonObject
            assertEquals("seen", s2["stranger_status"]!!.jsonPrimitive.content)
            assertEquals(2L, s2["appearances"]!!.jsonPrimitive.long)
            val strangerId = s2["stranger_id"]!!.jsonPrimitive.long

            val list = client.get("/api/strangers") { auth() }.json()
            assertEquals(1, list["count"]!!.jsonPrimitive.int)
            assertEquals("Person_01", list["strangers"]!!.jsonArray[0].jsonObject["name"]!!.jsonPrimitive.content)

            val bad = client.post("/api/strangers/$strangerId/promote") { auth(); contentType(ContentType.Application.Json); setBody("{}") }
            assertEquals(HttpStatusCode.BadRequest, bad.status)
            assertEquals("invalid_promote", bad.json()["error"]!!.jsonPrimitive.content)
            val missing = client.post("/api/strangers/999/promote") { auth(); contentType(ContentType.Application.Json); setBody("{\"name\":\"Nam\"}") }
            assertEquals(HttpStatusCode.NotFound, missing.status)

            val promoted = client.post("/api/strangers/$strangerId/promote") { auth(); contentType(ContentType.Application.Json); setBody("{\"name\":\" Nam \"}") }
            assertEquals(HttpStatusCode.OK, promoted.status)
            val p = promoted.json()
            assertEquals("Nam", p["name"]!!.jsonPrimitive.content)
            assertEquals("Person_01", p["stranger_name"]!!.jsonPrimitive.content)
            assertEquals(true, p["created"]!!.jsonPrimitive.content.toBoolean())
            assertEquals(2, p["embeddings_moved"]!!.jsonPrimitive.int)
            assertEquals(0, client.get("/api/strangers") { auth() }.json()["count"]!!.jsonPrimitive.int)

            // A new cluster, then ambiguity and delete paths
            val id3 = recognize()[1].jsonObject["stranger_id"]!!.jsonPrimitive.long
            backend.household.create("Bo")
            backend.household.create("Bo")
            val ambiguous = client.post("/api/strangers/$id3/promote") { auth(); contentType(ContentType.Application.Json); setBody("{\"name\":\"Bo\"}") }
            assertEquals(HttpStatusCode.Conflict, ambiguous.status)
            assertEquals("ambiguous_name", ambiguous.json()["error"]!!.jsonPrimitive.content)
            val noPerson = client.post("/api/strangers/$id3/promote") { auth(); contentType(ContentType.Application.Json); setBody("{\"person_id\":555}") }
            assertEquals("person_not_found", noPerson.json()["error"]!!.jsonPrimitive.content)
            assertEquals(HttpStatusCode.OK, client.delete("/api/strangers/$id3") { auth() }.status)
            assertEquals(HttpStatusCode.NotFound, client.delete("/api/strangers/$id3") { auth() }.status)
            recognize()
            assertEquals(1, client.delete("/api/strangers") { auth() }.json()["deleted"]!!.jsonPrimitive.int)
        }

    @Test
    fun strangerEndpointsNeedTokenAndValidateInput() =
        api {
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/strangers").status)
            assertEquals(HttpStatusCode.Unauthorized, client.delete("/api/strangers").status)
            assertEquals(HttpStatusCode.Unauthorized, client.delete("/api/strangers/1").status)
            assertEquals(HttpStatusCode.Unauthorized, client.post("/api/strangers/1/promote").status)
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/settings/strangers").status)
            val auth: io.ktor.client.request.HttpRequestBuilder.() -> Unit = { header(HttpHeaders.Authorization, "Bearer $TOKEN") }
            assertEquals(HttpStatusCode.BadRequest, client.delete("/api/strangers/abc") { auth() }.status)
            val badFlag =
                client.post("/api/recognize?cluster_strangers=yes") {
                    auth()
                    setBody(MultiPartFormDataContent(imagePart("jpeg".toByteArray())))
                }
            assertEquals(HttpStatusCode.BadRequest, badFlag.status)
            assertEquals("invalid_parameter", badFlag.json()["error"]!!.jsonPrimitive.content)
        }

    @Test
    fun strangerSettingsApi() =
        api {
            val auth: io.ktor.client.request.HttpRequestBuilder.() -> Unit = { header(HttpHeaders.Authorization, "Bearer $TOKEN") }
            val got = client.get("/api/settings/strangers") { auth() }.json()
            assertEquals("0.7", got["threshold"]!!.jsonPrimitive.content)
            assertEquals(200, got["max_clusters"]!!.jsonPrimitive.int)
            val put = client.put("/api/settings/strangers") { auth(); contentType(ContentType.Application.Json); setBody("{\"threshold\":0.75}") }
            assertEquals("0.75", put.json()["threshold"]!!.jsonPrimitive.content)
            for (bad in listOf("{\"threshold\":1.5}", "{}", "nope")) {
                val res = client.put("/api/settings/strangers") { auth(); contentType(ContentType.Application.Json); setBody(bad) }
                assertEquals(bad, HttpStatusCode.BadRequest, res.status)
            }
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
