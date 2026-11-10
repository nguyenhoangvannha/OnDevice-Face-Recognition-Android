package com.ml.shubham0204.facenet_android.coprocessor

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

/** One handled HTTP request, shown in the dashboard. Never contains the token or image data. */
data class RequestLogEntry(
    val timeMillis: Long,
    val method: String,
    val path: String,
    val status: Int,
    val durationMs: Long,
    val inferenceMs: Long? = null,
    val faceCount: Int? = null,
    val recognizedCount: Int? = null,
    val remoteHost: String? = null,
)

sealed interface ServerStatus {
    data object Stopped : ServerStatus

    data object Starting : ServerStatus

    data class Running(
        val port: Int,
        val startedAtMillis: Long,
    ) : ServerStatus

    data class Failed(
        val message: String,
    ) : ServerStatus
}

/** Process-wide server state shared by the foreground service and the dashboard. */
class CoprocessorStateHolder(
    private val maxLogEntries: Int = DEFAULT_MAX_LOG_ENTRIES,
) {
    private val _status = MutableStateFlow<ServerStatus>(ServerStatus.Stopped)
    val status: StateFlow<ServerStatus> = _status.asStateFlow()

    private val _recentRequests = MutableStateFlow<List<RequestLogEntry>>(emptyList())

    /** Newest first, at most [maxLogEntries]. */
    val recentRequests: StateFlow<List<RequestLogEntry>> = _recentRequests.asStateFlow()

    private val served = AtomicLong(0)
    val requestsServed: Long get() = served.get()

    fun setStatus(status: ServerStatus) {
        _status.value = status
    }

    fun record(entry: RequestLogEntry) {
        served.incrementAndGet()
        _recentRequests.update { (listOf(entry) + it).take(maxLogEntries) }
    }

    fun clearLog() {
        _recentRequests.value = emptyList()
    }

    companion object {
        const val DEFAULT_MAX_LOG_ENTRIES = 50
    }
}

object CoprocessorState {
    val holder = CoprocessorStateHolder()
}
