package com.ml.shubham0204.facenet_android.coprocessor.server

import io.ktor.server.application.Application
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Embedded Ktor CIO server wrapping [faceApiModule]. */
class FaceApiServer(
    private val host: String,
    private val port: Int,
    private val module: Application.() -> Unit,
) {
    private var engine: ApplicationEngine? = null

    val isRunning: Boolean get() = engine != null

    /** Binds and starts; throws if the port cannot be bound. */
    @Synchronized
    fun start() {
        if (engine != null) return
        // watchPaths = emptyList(): no auto-reload on Android
        val started = embeddedServer(CIO, port = port, host = host, watchPaths = emptyList(), module = module).start(wait = false)
        // CIO binds asynchronously: start() alone does not report a taken port.
        // resolvedConnectors() waits for the bind and rethrows its failure.
        try {
            runBlocking { withTimeout(BIND_TIMEOUT_MS) { started.resolvedConnectors() } }
        } catch (e: Throwable) {
            started.stop(0, 0)
            throw e
        }
        engine = started
    }

    private companion object {
        const val BIND_TIMEOUT_MS = 10_000L
    }

    @Synchronized
    fun stop() {
        engine?.stop(gracePeriodMillis = 1_000, timeoutMillis = 2_000)
        engine = null
    }
}
