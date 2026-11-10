package com.ml.shubham0204.facenet_android.coprocessor.server

import io.ktor.server.application.Application
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer

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
        engine = embeddedServer(CIO, port = port, host = host, watchPaths = emptyList(), module = module).start(wait = false)
    }

    @Synchronized
    fun stop() {
        engine?.stop(gracePeriodMillis = 1_000, timeoutMillis = 2_000)
        engine = null
    }
}
