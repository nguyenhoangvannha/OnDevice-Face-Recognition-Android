package com.ml.shubham0204.facenet_android.engine.backend

import com.ml.shubham0204.facenet_android.engine.SimilarityMath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One loaded embedding model. Implementations need not be thread-safe; the controller serialises calls. */
interface EmbeddingRunner : AutoCloseable {
    /** [input]: 160*160*3 RGB floats 0..255 (NHWC). Returns the 512-D embedding. */
    fun embed(input: FloatArray): FloatArray
}

/** Gets model files onto the device and loads them. Android implementation: AndroidModelProvider. */
interface ModelProvider {
    /** True when the model file is present locally and its checksum was verified. */
    fun isLocal(spec: ModelSpec): Boolean

    /** Copies/downloads the model if needed and returns its local path. */
    suspend fun ensureLocal(
        spec: ModelSpec,
        onProgress: (downloaded: Long, total: Long?) -> Unit,
    ): String

    /** Loads the model; must throw if the backend cannot run it. */
    fun load(
        spec: ModelSpec,
        path: String,
    ): EmbeddingRunner
}

/** Persisted choice plus a crash guard for native init. */
interface BackendPrefs {
    var requestedBackend: InferenceBackend

    /** Set while a non-CPU backend is being initialised; still set at startup = that attempt crashed the process. */
    var pendingProbe: InferenceBackend?
}

enum class BackendPhase { IDLE, DOWNLOADING, LOADING, READY, FAILED }

data class BackendStatus(
    val requested: InferenceBackend,
    /** What embeddings are computed on right now; null before the first load. */
    val active: InferenceBackend?,
    val phase: BackendPhase,
    val downloadedBytes: Long = 0,
    val totalBytes: Long? = null,
    /** Why [active] differs from [requested], if it does. */
    val fallbackReason: String? = null,
)

class BackendUnavailableException(
    val backend: InferenceBackend,
    reason: String,
) : Exception(reason)

/**
 * Chooses and swaps the embedding model at runtime.
 *
 * - CPU (bundled XNNPACK model) is always the fallback.
 * - A non-CPU model is accepted only if it loads, runs a probe input, and its embedding matches the
 *   CPU embedding of the same probe (cosine >= [ModelSpec.minProbeCosine]). That keeps enrolled faces valid.
 * - If the process dies while a non-CPU backend initialises, the next start stays on CPU.
 */
class BackendController(
    private val provider: ModelProvider,
    private val prefs: BackendPrefs,
    private val scope: CoroutineScope,
    private val catalog: (InferenceBackend) -> ModelSpec = ModelCatalog::spec,
    private val probeInput: FloatArray = defaultProbe(),
) {
    private val runMutex = Mutex()
    private val switchMutex = Mutex()
    private var runner: EmbeddingRunner? = null
    @Volatile private var activeBackend: InferenceBackend? = null
    private var cpuProbeEmbedding: FloatArray? = null
    private var switchJob: Job? = null

    private val _status = MutableStateFlow(BackendStatus(prefs.requestedBackend, null, BackendPhase.IDLE))
    val status: StateFlow<BackendStatus> = _status.asStateFlow()

    /**
     * Loads the CPU model synchronously-in-coroutine (as the app always did), then, if another backend
     * is requested, switches to it in [scope]. Embeddings keep running on CPU meanwhile.
     */
    suspend fun initialize() {
        switchMutex.withLock {
            if (activeBackend != null) return
            loadCpu(fallbackReason = null)
            val crashed = prefs.pendingProbe
            if (crashed != null) {
                prefs.pendingProbe = null
                markFallback("${crashed.wireName} crashed the app during initialisation last time; using CPU")
                return
            }
        }
        val requested = prefs.requestedBackend
        if (requested != InferenceBackend.CPU) {
            if (!catalog(requested).available) {
                markFallback((catalog(requested).source as ModelSource.Unavailable).reason)
            } else {
                switchJob = scope.launch { switchTo(requested) }
            }
        }
    }

    /**
     * Persists [backend] as the user's choice and starts switching in [scope].
     * @throws BackendUnavailableException when no model exists for it in this build.
     */
    fun select(backend: InferenceBackend): BackendStatus {
        val spec = catalog(backend)
        val source = spec.source
        if (source is ModelSource.Unavailable) throw BackendUnavailableException(backend, source.reason)
        prefs.requestedBackend = backend
        _status.update { it.copy(requested = backend) }
        switchJob = scope.launch { switchTo(backend) }
        return _status.value
    }

    /** For tests and callers that want to wait for the last requested switch. */
    suspend fun awaitSwitch() {
        switchJob?.join()
    }

    suspend fun embed(input: FloatArray): FloatArray {
        if (activeBackend == null) initialize()
        return runMutex.withLock {
            val current = runner ?: error("No embedding model loaded")
            current.embed(input)
        }
    }

    /** Loads [backend] (with download if needed), verifies it, then swaps it in; falls back to CPU on any failure. */
    suspend fun switchTo(backend: InferenceBackend) {
        switchMutex.withLock {
            if (prefs.requestedBackend != backend) return // superseded by a newer choice
            if (activeBackend == backend && runner != null) {
                _status.update { it.copy(phase = BackendPhase.READY, fallbackReason = null) }
                return
            }
            if (backend == InferenceBackend.CPU) {
                loadCpu(fallbackReason = null)
                return
            }
            val spec = catalog(backend)
            try {
                val reference = cpuReference()
                if (!provider.isLocal(spec)) {
                    _status.update { it.copy(phase = BackendPhase.DOWNLOADING, downloadedBytes = 0, totalBytes = spec.sizeBytes) }
                }
                val path =
                    provider.ensureLocal(spec) { done, total ->
                        _status.update { it.copy(phase = BackendPhase.DOWNLOADING, downloadedBytes = done, totalBytes = total) }
                    }
                _status.update { it.copy(phase = BackendPhase.LOADING) }
                prefs.pendingProbe = backend
                val candidate =
                    try {
                        provider.load(spec, path)
                    } catch (t: Throwable) {
                        prefs.pendingProbe = null
                        throw t
                    }
                val similarity =
                    try {
                        SimilarityMath.cosineSimilarity(candidate.embed(probeInput.copyOf()), reference)
                    } catch (t: Throwable) {
                        candidate.close()
                        throw t
                    } finally {
                        prefs.pendingProbe = null
                    }
                if (similarity.isNaN() || similarity < spec.minProbeCosine) {
                    candidate.close()
                    throw IllegalStateException(
                        "output differs from CPU (cosine %.5f < %.4f); enrolled faces would not match"
                            .format(java.util.Locale.US, similarity, spec.minProbeCosine),
                    )
                }
                swapIn(backend, candidate)
                _status.value = BackendStatus(backend, backend, BackendPhase.READY)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                loadCpu(fallbackReason = "${backend.wireName} failed: ${t.message ?: t.javaClass.simpleName}".take(MAX_REASON))
            }
        }
    }

    private suspend fun cpuReference(): FloatArray {
        cpuProbeEmbedding?.let { return it }
        // Active runner may be CPU already; otherwise load a temporary CPU runner.
        val reference =
            if (activeBackend == InferenceBackend.CPU) {
                runMutex.withLock { runner!!.embed(probeInput.copyOf()) }
            } else {
                val spec = catalog(InferenceBackend.CPU)
                provider.load(spec, provider.ensureLocal(spec) { _, _ -> }).use { it.embed(probeInput.copyOf()) }
            }
        cpuProbeEmbedding = reference
        return reference
    }

    private suspend fun loadCpu(fallbackReason: String?) {
        val requested = prefs.requestedBackend
        if (activeBackend != InferenceBackend.CPU || runner == null) {
            _status.update { it.copy(phase = BackendPhase.LOADING) }
            val spec = catalog(InferenceBackend.CPU)
            val cpu = provider.load(spec, provider.ensureLocal(spec) { _, _ -> })
            swapIn(InferenceBackend.CPU, cpu)
            if (cpuProbeEmbedding == null) {
                cpuProbeEmbedding = runMutex.withLock { cpu.embed(probeInput.copyOf()) }
            }
        }
        _status.value =
            BackendStatus(
                requested = requested,
                active = InferenceBackend.CPU,
                phase = if (fallbackReason == null) BackendPhase.READY else BackendPhase.FAILED,
                fallbackReason = fallbackReason,
            )
    }

    private fun markFallback(reason: String) {
        _status.update { it.copy(phase = BackendPhase.FAILED, fallbackReason = reason.take(MAX_REASON)) }
    }

    private suspend fun swapIn(
        backend: InferenceBackend,
        newRunner: EmbeddingRunner,
    ) {
        val old =
            runMutex.withLock {
                val previous = runner
                runner = newRunner
                activeBackend = backend
                previous
            }
        old?.close()
    }

    companion object {
        const val PROBE_SIZE = 160 * 160 * 3
        private const val MAX_REASON = 300

        /** Deterministic pseudo-random image (values 0..255), identical on every run and device. */
        fun defaultProbe(): FloatArray {
            var state = 0x2545F491L
            return FloatArray(PROBE_SIZE) {
                state = (state * 6364136223846793005L + 1442695040888963407L)
                ((state ushr 33) % 256).toFloat()
            }
        }
    }
}
