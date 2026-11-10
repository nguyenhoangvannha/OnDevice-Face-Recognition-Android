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

    suspend fun listPersons(): List<PersonSummary>

    /** Returns false when the person does not exist. */
    suspend fun deletePerson(personId: Long): Boolean

    suspend fun status(): StatusResponse
}
