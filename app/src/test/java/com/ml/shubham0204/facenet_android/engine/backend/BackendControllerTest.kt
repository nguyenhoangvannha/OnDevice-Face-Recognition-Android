package com.ml.shubham0204.facenet_android.engine.backend

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class MemoryPrefs(
    override var requestedBackend: InferenceBackend = InferenceBackend.CPU,
    override var pendingProbe: InferenceBackend? = null,
) : BackendPrefs

/** Embedding = first 4 input values (scaled), tagged so tests can see which runner answered. */
private class FakeRunner(
    val backend: InferenceBackend,
    private val scale: Float = 1f,
    private val failOnEmbed: Boolean = false,
    private val onEmbed: () -> Unit = {},
) : EmbeddingRunner {
    var closed = false

    override fun embed(input: FloatArray): FloatArray {
        onEmbed()
        if (failOnEmbed) throw IllegalStateException("vulkan device lost")
        return floatArrayOf(input[0] * scale, input[1], input[2], input[3], backend.ordinal.toFloat() * 0f)
    }

    override fun close() {
        closed = true
    }
}

private class FakeProvider : ModelProvider {
    val local = mutableSetOf(InferenceBackend.CPU)
    val loaded = mutableListOf<FakeRunner>()
    var downloadFails = false
    var loadFails: InferenceBackend? = null
    var gpuScale = 1f
    var gpuFailsOnEmbed = false
    var gpuOnEmbed: () -> Unit = {}

    /** When set, getting the GPU model waits for it, so a test can observe the CPU-first state. */
    var gpuGate: CompletableDeferred<Unit>? = null

    override fun isLocal(spec: ModelSpec) = spec.backend in local

    override suspend fun ensureLocal(
        spec: ModelSpec,
        onProgress: (Long, Long?) -> Unit,
    ): String {
        if (spec.backend == InferenceBackend.GPU) gpuGate?.await()
        if (spec.backend !in local) {
            if (downloadFails) throw IllegalStateException("download failed: HTTP 404")
            onProgress(50, 100)
            onProgress(100, 100)
            local += spec.backend
        }
        return "/models/${spec.fileName}"
    }

    override fun load(
        spec: ModelSpec,
        path: String,
    ): EmbeddingRunner {
        if (loadFails == spec.backend) throw IllegalStateException("no Vulkan device")
        val runner =
            if (spec.backend == InferenceBackend.GPU) {
                FakeRunner(spec.backend, gpuScale, gpuFailsOnEmbed, gpuOnEmbed)
            } else {
                FakeRunner(spec.backend)
            }
        loaded += runner
        return runner
    }
}

class BackendControllerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val input = FloatArray(BackendController.PROBE_SIZE) { (it % 7).toFloat() }

    private fun controller(
        provider: FakeProvider,
        prefs: MemoryPrefs,
    ) = BackendController(provider, prefs, scope)

    @Test
    fun startsOnCpuByDefault() =
        runBlocking {
            val provider = FakeProvider()
            val c = controller(provider, MemoryPrefs())
            c.initialize()
            val s = c.status.value
            assertEquals(InferenceBackend.CPU, s.active)
            assertEquals(BackendPhase.READY, s.phase)
            assertNull(s.fallbackReason)
            assertEquals(1, provider.loaded.size)
        }

    @Test
    fun switchesToGpuAfterDownloadAndProbe() =
        runBlocking {
            val provider = FakeProvider()
            val prefs = MemoryPrefs()
            val c = controller(provider, prefs)
            c.initialize()
            c.select(InferenceBackend.GPU)
            c.awaitSwitch()
            val s = c.status.value
            assertEquals(InferenceBackend.GPU, s.active)
            assertEquals(BackendPhase.READY, s.phase)
            assertEquals(InferenceBackend.GPU, prefs.requestedBackend)
            assertTrue(InferenceBackend.GPU in provider.local)
            assertTrue("old CPU runner is released", provider.loaded.first().closed)
            assertNull(prefs.pendingProbe)
            assertEquals(5, c.embed(input).size) // runs on the GPU runner
        }

    @Test
    fun requestedGpuIsRestoredOnStartup() =
        runBlocking {
            val gate = CompletableDeferred<Unit>()
            val provider = FakeProvider().apply { local += InferenceBackend.GPU; gpuGate = gate }
            val c = controller(provider, MemoryPrefs(requestedBackend = InferenceBackend.GPU))
            c.initialize()
            assertEquals(InferenceBackend.CPU, c.status.value.active) // CPU first, GPU in background
            gate.complete(Unit)
            c.awaitSwitch()
            assertEquals(InferenceBackend.GPU, c.status.value.active)
        }

    @Test
    fun loadFailureFallsBackToCpuAndKeepsChoice() =
        runBlocking {
            val provider = FakeProvider().apply { loadFails = InferenceBackend.GPU }
            val prefs = MemoryPrefs()
            val c = controller(provider, prefs)
            c.initialize()
            c.select(InferenceBackend.GPU)
            c.awaitSwitch()
            val s = c.status.value
            assertEquals(InferenceBackend.CPU, s.active)
            assertEquals(InferenceBackend.GPU, s.requested)
            assertEquals(BackendPhase.FAILED, s.phase)
            assertTrue(s.fallbackReason!!.contains("no Vulkan device"))
            assertNull(prefs.pendingProbe)
            assertFalse(provider.loaded.first().closed)
        }

    @Test
    fun downloadFailureFallsBackToCpu() =
        runBlocking {
            val provider = FakeProvider().apply { downloadFails = true }
            val c = controller(provider, MemoryPrefs())
            c.initialize()
            c.select(InferenceBackend.GPU)
            c.awaitSwitch()
            assertEquals(InferenceBackend.CPU, c.status.value.active)
            assertTrue(c.status.value.fallbackReason!!.contains("HTTP 404"))
        }

    @Test
    fun runtimeErrorDuringProbeFallsBackToCpu() =
        runBlocking {
            val provider = FakeProvider().apply { gpuFailsOnEmbed = true }
            val c = controller(provider, MemoryPrefs())
            c.initialize()
            c.select(InferenceBackend.GPU)
            c.awaitSwitch()
            assertEquals(InferenceBackend.CPU, c.status.value.active)
            assertTrue(provider.loaded.last().closed)
            assertTrue(c.status.value.fallbackReason!!.contains("device lost"))
        }

    @Test
    fun mismatchingOutputIsRejected() =
        runBlocking {
            // Different direction than CPU on the probe -> enrolled faces would not match.
            val provider = FakeProvider().apply { gpuScale = -1f }
            val c = controller(provider, MemoryPrefs())
            c.initialize()
            c.select(InferenceBackend.GPU)
            c.awaitSwitch()
            val s = c.status.value
            assertEquals(InferenceBackend.CPU, s.active)
            assertTrue(s.fallbackReason!!.contains("differs from CPU"))
            assertTrue(provider.loaded.last().closed)
        }

    @Test
    fun crashDuringPreviousProbeKeepsCpuOnNextStart() =
        runBlocking {
            val provider = FakeProvider().apply { local += InferenceBackend.GPU }
            val prefs = MemoryPrefs(requestedBackend = InferenceBackend.GPU, pendingProbe = InferenceBackend.GPU)
            val c = controller(provider, prefs)
            c.initialize()
            c.awaitSwitch()
            val s = c.status.value
            assertEquals(InferenceBackend.CPU, s.active)
            assertEquals(BackendPhase.FAILED, s.phase)
            assertTrue(s.fallbackReason!!.contains("crashed"))
            assertNull(prefs.pendingProbe)
            assertEquals("GPU was not attempted again", 1, provider.loaded.size)
        }

    @Test
    fun probeFlagIsSetWhileNativeInitRuns() =
        runBlocking {
            val prefs = MemoryPrefs()
            var seenDuringProbe: InferenceBackend? = null
            val provider = FakeProvider().apply { gpuOnEmbed = { seenDuringProbe = prefs.pendingProbe } }
            val c = controller(provider, prefs)
            c.initialize()
            c.select(InferenceBackend.GPU)
            c.awaitSwitch()
            assertEquals(InferenceBackend.GPU, seenDuringProbe)
            assertNull(prefs.pendingProbe)
        }

    @Test
    fun npuIsRejectedWhileNoModelShips() {
        val prefs = MemoryPrefs()
        val c = controller(FakeProvider(), prefs)
        val e = runCatching { c.select(InferenceBackend.NPU) }.exceptionOrNull()
        assertTrue(e is BackendUnavailableException)
        assertEquals(InferenceBackend.CPU, prefs.requestedBackend)
    }

    @Test
    fun switchingBackToCpuReloadsCpu() =
        runBlocking {
            val provider = FakeProvider()
            val prefs = MemoryPrefs()
            val c = controller(provider, prefs)
            c.initialize()
            c.select(InferenceBackend.GPU)
            c.awaitSwitch()
            c.select(InferenceBackend.CPU)
            c.awaitSwitch()
            assertEquals(InferenceBackend.CPU, c.status.value.active)
            assertEquals(BackendPhase.READY, c.status.value.phase)
            assertTrue("GPU runner released", provider.loaded.first { it.backend == InferenceBackend.GPU }.closed)
        }

    @Test
    fun embedInitialisesLazily() =
        runBlocking {
            val c = controller(FakeProvider(), MemoryPrefs())
            val out = c.embed(input)
            assertNotNull(out)
            assertEquals(InferenceBackend.CPU, c.status.value.active)
        }

    @Test
    fun wireNamesRoundTrip() {
        InferenceBackend.entries.forEach { assertEquals(it, InferenceBackend.fromWire(it.wireName.uppercase())) }
        assertNull(InferenceBackend.fromWire("tpu"))
        assertNull(InferenceBackend.fromWire(null))
    }

    @Test
    fun catalogUsesSameModelFamilyAndPinsChecksums() {
        ModelCatalog.all.filter { it.available }.forEach {
            assertEquals("fp32", it.precision)
            assertEquals(64, it.sha256!!.length)
        }
        assertFalse(ModelCatalog.NPU.available)
        assertTrue(ModelCatalog.GPU.source is ModelSource.Remote)
        assertTrue((ModelCatalog.GPU.source as ModelSource.Remote).url.startsWith("https://"))
    }

    @Test
    fun probeIsDeterministic() {
        val a = BackendController.defaultProbe()
        val b = BackendController.defaultProbe()
        assertTrue(a.contentEquals(b))
        assertTrue(a.all { it in 0f..255f })
        assertTrue(a.toSet().size > 200)
    }
}
