package com.ml.shubham0204.facenet_android.coprocessor.server

import com.ml.shubham0204.facenet_android.engine.EnrollResult
import com.ml.shubham0204.facenet_android.engine.PersonSummary
import com.ml.shubham0204.facenet_android.engine.RecognizeOptions
import com.ml.shubham0204.facenet_android.engine.backend.InferenceBackend
import com.ml.shubham0204.facenet_android.engine.strangers.PromoteResult
import com.ml.shubham0204.facenet_android.engine.strangers.PromoteTarget
import com.ml.shubham0204.facenet_android.engine.strangers.StrangerCluster

/** What the REST layer needs from the app. Faked in JVM tests. */
interface CoprocessorBackend {
    /** @throws InvalidImageException when the bytes are not a decodable image. */
    suspend fun recognize(
        image: ByteArray,
        options: RecognizeOptions,
    ): RecognizeOutcome

    /** @throws InvalidImageException when any image cannot be decoded. */
    suspend fun enroll(
        name: String,
        images: List<ByteArray>,
    ): EnrollResult

    /**
     * Adds images to an existing person. Returns null when the person does not exist.
     * @throws InvalidImageException when any image cannot be decoded.
     */
    suspend fun addImages(
        personId: Long,
        images: List<ByteArray>,
    ): EnrollResult?

    suspend fun listPersons(): List<PersonSummary>

    /** Returns false when the person does not exist. */
    suspend fun deletePerson(personId: Long): Boolean

    suspend fun status(): StatusResponse

    fun getThreshold(): Float

    /** Persists the threshold (same storage as the app slider) and returns the stored value. */
    fun setThreshold(value: Float): Float

    fun getBackendSettings(): BackendSettingsResponse

    /**
     * Persists the choice and starts switching in the background (download/load/verify).
     * @throws com.ml.shubham0204.facenet_android.engine.backend.BackendUnavailableException
     */
    fun selectBackend(backend: InferenceBackend): BackendSettingsResponse

    /** Stranger clusters, most recently seen first. */
    suspend fun listStrangers(): List<StrangerCluster>

    suspend fun promoteStranger(
        strangerId: Long,
        target: PromoteTarget,
    ): PromoteResult

    /** Returns false when the cluster does not exist. */
    suspend fun deleteStranger(strangerId: Long): Boolean

    /** Returns the number of clusters deleted. */
    suspend fun deleteAllStrangers(): Int

    fun getStrangerSettings(): StrangerSettingsResponse

    fun setStrangerThreshold(value: Float): StrangerSettingsResponse
}
