package com.ml.shubham0204.facenet_android.coprocessor.backend

import android.content.Context
import com.ml.shubham0204.facenet_android.engine.backend.EmbeddingRunner
import com.ml.shubham0204.facenet_android.engine.backend.ModelProvider
import com.ml.shubham0204.facenet_android.engine.backend.ModelSource
import com.ml.shubham0204.facenet_android.engine.backend.ModelSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.pytorch.executorch.EValue
import org.pytorch.executorch.Module
import org.pytorch.executorch.Tensor
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * Model files live in filesDir (CPU: copied from assets, as upstream did; others: downloaded).
 * A "<file>.verified" sidecar holding the sha256 avoids re-hashing ~94 MB on every start.
 */
class AndroidModelProvider(
    private val context: Context,
) : ModelProvider {
    private fun fileFor(spec: ModelSpec) = File(context.filesDir, spec.fileName)

    private fun markerFor(spec: ModelSpec) = File(context.filesDir, spec.fileName + ".verified")

    override fun isLocal(spec: ModelSpec): Boolean {
        val file = fileFor(spec)
        if (!file.isFile) return false
        if (spec.sizeBytes != null && file.length() != spec.sizeBytes) return false
        val marker = markerFor(spec)
        return marker.isFile && marker.readText().trim() == spec.sha256
    }

    override suspend fun ensureLocal(
        spec: ModelSpec,
        onProgress: (downloaded: Long, total: Long?) -> Unit,
    ): String =
        withContext(Dispatchers.IO) {
            val target = fileFor(spec)
            if (isLocal(spec)) return@withContext target.absolutePath
            val expected = requireNotNull(spec.sha256) { "No checksum for ${spec.fileName}" }
            val part = File(context.filesDir, spec.fileName + ".part")
            markerFor(spec).delete()
            when (val source = spec.source) {
                is ModelSource.Asset ->
                    context.assets.open(source.assetName).use { copyHashing(it, part, spec.sizeBytes, onProgress) }
                is ModelSource.Remote -> download(source.url, part, spec.sizeBytes, onProgress)
                is ModelSource.Unavailable -> throw IllegalStateException(source.reason)
            }.let { actual ->
                if (actual != expected) {
                    part.delete()
                    throw IllegalStateException("checksum mismatch for ${spec.fileName}")
                }
            }
            if (!part.renameTo(target)) {
                target.delete()
                check(part.renameTo(target)) { "could not move ${spec.fileName} into place" }
            }
            markerFor(spec).writeText(expected)
            target.absolutePath
        }

    override fun load(
        spec: ModelSpec,
        path: String,
    ): EmbeddingRunner = ExecuTorchRunner.load(path)

    private suspend fun download(
        url: String,
        part: File,
        expectedSize: Long?,
        onProgress: (Long, Long?) -> Unit,
    ): String {
        val connection = (URL(url).openConnection() as HttpURLConnection)
        connection.connectTimeout = 20_000
        connection.readTimeout = 60_000
        connection.instanceFollowRedirects = true
        try {
            val code = connection.responseCode
            check(code == HttpURLConnection.HTTP_OK) { "download failed: HTTP $code" }
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: expectedSize
            return connection.inputStream.use { copyHashing(it, part, total, onProgress) }
        } finally {
            connection.disconnect()
        }
    }

    /** Copies [input] to [out] and returns the sha256 hex of the bytes. */
    private suspend fun copyHashing(
        input: InputStream,
        out: File,
        total: Long?,
        onProgress: (Long, Long?) -> Unit,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(256 * 1024)
        var done = 0L
        var lastReport = 0L
        out.outputStream().use { os ->
            while (true) {
                coroutineContext.ensureActive()
                val n = input.read(buffer)
                if (n < 0) break
                os.write(buffer, 0, n)
                digest.update(buffer, 0, n)
                done += n
                if (total != null && done > total + MAX_EXTRA_BYTES) throw IllegalStateException("model larger than expected")
                if (done - lastReport >= REPORT_EVERY_BYTES) {
                    lastReport = done
                    onProgress(done, total)
                }
            }
        }
        onProgress(done, total)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val REPORT_EVERY_BYTES = 1L shl 20
        const val MAX_EXTRA_BYTES = 1L shl 20
    }
}

/** ExecuTorch Module wrapper. The delegate (XNNPACK, Vulkan, ...) is baked into the .pte. */
class ExecuTorchRunner private constructor(
    private val module: Module,
) : EmbeddingRunner {
    override fun embed(input: FloatArray): FloatArray {
        val tensor = Tensor.fromBlob(input, longArrayOf(1, IMG_SIZE, IMG_SIZE, 3))
        return module.forward(EValue.from(tensor))[0].toTensor().dataAsFloatArray
    }

    override fun close() {
        module.destroy()
    }

    companion object {
        private const val IMG_SIZE = 160L

        fun load(path: String): ExecuTorchRunner {
            val module = Module.load(path)
            val error = module.loadMethod("forward")
            if (error != 0) {
                module.destroy()
                throw IllegalStateException("ExecuTorch could not load the model (error 0x%x)".format(error))
            }
            return ExecuTorchRunner(module)
        }
    }
}
