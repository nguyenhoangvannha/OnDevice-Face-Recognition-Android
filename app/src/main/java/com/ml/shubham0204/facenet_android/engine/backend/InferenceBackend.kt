package com.ml.shubham0204.facenet_android.engine.backend

/** Hardware the FaceNet embedding model runs on. Wire names are used by the REST API and settings. */
enum class InferenceBackend(
    val wireName: String,
    val label: String,
) {
    CPU("cpu", "Default (CPU, XNNPACK)"),
    GPU("gpu", "GPU (Vulkan)"),
    NPU("npu", "NPU (Qualcomm QNN HTP, SM8350)"),
    ;

    companion object {
        val DEFAULT = CPU

        fun fromWire(value: String?): InferenceBackend? {
            val v = value?.trim()?.lowercase() ?: return null
            return entries.firstOrNull { it.wireName == v }
        }
    }
}

/** Where a backend's .pte comes from. */
sealed interface ModelSource {
    /** Bundled in the APK under assets/. */
    data class Asset(
        val assetName: String,
    ) : ModelSource

    /** Downloaded on demand (HTTPS), verified against [ModelSpec.sha256]. */
    data class Remote(
        val url: String,
    ) : ModelSource

    /** No model shipped yet; [reason] is shown to the user. */
    data class Unavailable(
        val reason: String,
    ) : ModelSource
}

data class ModelSpec(
    val backend: InferenceBackend,
    /** File name under the app's models directory. */
    val fileName: String,
    val source: ModelSource,
    /** Lower-case hex; null only for [ModelSource.Unavailable]. */
    val sha256: String?,
    val sizeBytes: Long?,
    /** Numeric precision of the exported graph; anything but fp32 may shift embeddings. */
    val precision: String,
    /**
     * Minimum cosine between this model's and the CPU model's embedding of the probe input.
     * fp32 GPU should be ~1.0; an int8 export measured ~0.998 on LFW (tools/export/quant_drift.py).
     */
    val minProbeCosine: Float = 0.999f,
) {
    val available: Boolean get() = source !is ModelSource.Unavailable
}

/**
 * The models this build knows about. All use the same FaceNet weights
 * (facenet-pytorch InceptionResnetV1, VGGFace2); see tools/export/.
 */
object ModelCatalog {
    const val MODELS_RELEASE_BASE =
        "https://github.com/nguyenhoangvannha/OnDevice-Face-Recognition-Android/releases/download/models-v1"

    val CPU =
        ModelSpec(
            backend = InferenceBackend.CPU,
            fileName = "model.pte",
            source = ModelSource.Asset("model.pte"),
            sha256 = "06927e2f1e272686258d57fe6a8abaf8efce7e61a26d7b13e6ba40fa130251e2",
            sizeBytes = 94_011_392L,
            precision = "fp32",
        )

    val GPU =
        ModelSpec(
            backend = InferenceBackend.GPU,
            fileName = "facenet-vulkan-fp32.pte",
            source = ModelSource.Remote("$MODELS_RELEASE_BASE/facenet-vulkan-fp32.pte"),
            sha256 = "6408b0d46030f531e678adc27241307a839b27122a32edca806263e286d0b8bd",
            sizeBytes = 94_010_112L,
            precision = "fp32",
        )

    val NPU =
        ModelSpec(
            backend = InferenceBackend.NPU,
            fileName = "facenet-qnn-sm8350.pte",
            source =
                ModelSource.Unavailable(
                    "Not shipped yet: exporting for the Hexagon NPU needs the Qualcomm QNN SDK, whose licence " +
                        "must be accepted by the owner, and this app build has no QNN runtime.",
                ),
            sha256 = null,
            sizeBytes = null,
            precision = "int8 (planned)",
            minProbeCosine = 0.99f,
        )

    val all: List<ModelSpec> = listOf(CPU, GPU, NPU)

    fun spec(backend: InferenceBackend): ModelSpec = all.first { it.backend == backend }
}
