package com.ml.shubham0204.facenet_android.coprocessor.server

import com.ml.shubham0204.facenet_android.engine.EnrollResult
import com.ml.shubham0204.facenet_android.engine.PersonSummary
import io.ktor.server.application.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL

/** Starts the real CIO engine on a free local port (JVM, not Android). */
class FaceApiServerTest {
    private object NoBackend : CoprocessorBackend {
        override suspend fun recognize(image: ByteArray, withLiveness: Boolean): RecognizeOutcome = error("unused")

        override suspend fun enroll(name: String, images: List<ByteArray>): EnrollResult = error("unused")

        override suspend fun addImages(personId: Long, images: List<ByteArray>): EnrollResult? = null

        override suspend fun listPersons(): List<PersonSummary> = emptyList()

        override suspend fun deletePerson(personId: Long): Boolean = false

        override suspend fun status(): StatusResponse = error("unused")
    }

    @Test
    fun startsServesAndStops() {
        val port = ServerSocket(0).use { it.localPort }
        val server = FaceApiServer("127.0.0.1", port) { faceApiModule(NoBackend, { "t0ken" }) }
        server.start()
        try {
            assertTrue(server.isRunning)
            val health = URL("http://127.0.0.1:$port/api/health").openConnection() as HttpURLConnection
            assertEquals(200, health.responseCode)
            assertTrue(health.inputStream.bufferedReader().readText().contains("\"status\":\"ok\""))

            val persons = URL("http://127.0.0.1:$port/api/persons").openConnection() as HttpURLConnection
            assertEquals(401, persons.responseCode)

            val authed = URL("http://127.0.0.1:$port/api/persons").openConnection() as HttpURLConnection
            authed.setRequestProperty("Authorization", "Bearer t0ken")
            assertEquals(200, authed.responseCode)
        } finally {
            server.stop()
        }
        assertTrue(!server.isRunning)
    }

    @Test(expected = Exception::class)
    fun failsWhenPortIsTaken() {
        ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1")).use { taken ->
            FaceApiServer("127.0.0.1", taken.localPort, Application::noop).start()
        }
    }
}

private fun Application.noop() {}
