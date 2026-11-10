package com.ml.shubham0204.facenet_android.coprocessor

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.ml.shubham0204.facenet_android.engine.backend.BackendPrefs
import com.ml.shubham0204.facenet_android.engine.backend.InferenceBackend
import org.koin.core.annotation.Single

/**
 * Coprocessor settings in app-private SharedPreferences ([PREFS_NAME]).
 * The file is excluded from cloud backup and device transfer (see res/xml backup rules).
 */
@Single
class CoprocessorSettings(
    context: Context,
) : BackendPrefs {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** API token; generated randomly on first access and persisted. */
    val token: String
        get() =
            prefs.getString(KEY_TOKEN, null) ?: synchronized(this) {
                prefs.getString(KEY_TOKEN, null) ?: TokenUtils.newToken().also {
                    prefs.edit(commit = true) { putString(KEY_TOKEN, it) }
                }
            }

    /** Replaces the token. Clients using the old token get 401 immediately. */
    fun regenerateToken(): String =
        synchronized(this) {
            TokenUtils.newToken().also { prefs.edit(commit = true) { putString(KEY_TOKEN, it) } }
        }

    /** Listening port; takes effect on the next server start. */
    var port: Int
        get() = prefs.getInt(KEY_PORT, DEFAULT_PORT).takeIf { it in PORT_RANGE } ?: DEFAULT_PORT
        set(value) {
            require(value in PORT_RANGE) { "Port must be in $PORT_RANGE" }
            prefs.edit { putInt(KEY_PORT, value) }
        }

    /** Cosine-similarity threshold for the API; read on every request. */
    var threshold: Float
        get() = prefs.getFloat(KEY_THRESHOLD, DEFAULT_THRESHOLD).coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)
        set(value) {
            prefs.edit { putFloat(KEY_THRESHOLD, value.coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)) }
        }

    /** Desired server state; the boot receiver only starts the server when this is true. */
    var serverEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) {
            prefs.edit { putBoolean(KEY_ENABLED, value) }
        }

    /** Hardware for FaceNet embeddings, chosen in the dashboard or via PUT /api/settings/backend. */
    override var requestedBackend: InferenceBackend
        get() = InferenceBackend.fromWire(prefs.getString(KEY_BACKEND, null)) ?: InferenceBackend.DEFAULT
        set(value) {
            prefs.edit(commit = true) { putString(KEY_BACKEND, value.wireName) }
        }

    /** Crash guard; written with commit() so it survives a native crash during model init. */
    override var pendingProbe: InferenceBackend?
        get() = InferenceBackend.fromWire(prefs.getString(KEY_PENDING_PROBE, null))
        set(value) {
            prefs.edit(commit = true) {
                if (value == null) remove(KEY_PENDING_PROBE) else putString(KEY_PENDING_PROBE, value.wireName)
            }
        }

    companion object {
        const val PREFS_NAME = "coprocessor_settings"
        const val DEFAULT_PORT = 8080
        const val DEFAULT_THRESHOLD = 0.70f
        const val MIN_THRESHOLD = 0.0f
        const val MAX_THRESHOLD = 1.0f
        const val BIND_HOST = "0.0.0.0"
        val PORT_RANGE = 1024..65535

        private const val KEY_TOKEN = "api_token"
        private const val KEY_PORT = "port"
        private const val KEY_THRESHOLD = "threshold"
        private const val KEY_ENABLED = "server_enabled"
        private const val KEY_BACKEND = "inference_backend"
        private const val KEY_PENDING_PROBE = "inference_backend_pending_probe"
    }
}
