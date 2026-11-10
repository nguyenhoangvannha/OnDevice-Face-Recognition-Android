package com.ml.shubham0204.facenet_android.coprocessor.server

import io.ktor.http.ContentType
import io.ktor.http.withCharset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import java.util.concurrent.ConcurrentHashMap

/** The built-in test page, bundled as Java resources under `coprocessor-web/` (no CDN). */
internal object WebAssets {
    data class Asset(
        val resource: String,
        val contentType: ContentType,
    )

    /** Exact paths only: no directory listing, no path traversal. */
    val routes: Map<String, Asset> =
        mapOf(
            "/" to Asset("coprocessor-web/index.html", ContentType.Text.Html.withCharset(Charsets.UTF_8)),
            "/app.js" to Asset("coprocessor-web/app.js", ContentType.Text.JavaScript.withCharset(Charsets.UTF_8)),
            "/app.css" to Asset("coprocessor-web/app.css", ContentType.Text.CSS.withCharset(Charsets.UTF_8)),
        )

    private val cache = ConcurrentHashMap<String, ByteArray>()

    fun load(asset: Asset): ByteArray? =
        cache[asset.resource] ?: WebAssets::class.java.classLoader
            ?.getResourceAsStream(asset.resource)
            ?.use { it.readBytes() }
            ?.also { cache[asset.resource] = it }
}

/** Headers on every response (page and API). No CORS headers are ever sent. */
internal object SecurityHeaders {
    const val CONTENT_SECURITY_POLICY =
        "default-src 'self'; img-src 'self' blob:; object-src 'none'; base-uri 'none'; " +
            "form-action 'self'; frame-ancestors 'none'"

    fun apply(call: ApplicationCall) {
        call.response.header("Content-Security-Policy", CONTENT_SECURITY_POLICY)
        call.response.header("X-Content-Type-Options", "nosniff")
        call.response.header("X-Frame-Options", "DENY")
        call.response.header("Referrer-Policy", "no-referrer")
    }
}
