package com.ml.shubham0204.facenet_android.coprocessor.server

import com.ml.shubham0204.facenet_android.engine.EnrollResult
import com.ml.shubham0204.facenet_android.engine.PersonSummary

/** What the REST layer needs from the app. Faked in JVM tests. */
interface CoprocessorBackend {
    /** @throws InvalidImageException when the bytes are not a decodable image. */
    suspend fun recognize(
        image: ByteArray,
        withLiveness: Boolean,
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
}
