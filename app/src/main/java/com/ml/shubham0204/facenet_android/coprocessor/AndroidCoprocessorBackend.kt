package com.ml.shubham0204.facenet_android.coprocessor

import android.content.Context
import android.os.Build
import com.ml.shubham0204.facenet_android.coprocessor.server.CoprocessorBackend
import com.ml.shubham0204.facenet_android.coprocessor.server.InvalidImageException
import com.ml.shubham0204.facenet_android.coprocessor.server.RecognizeOutcome
import com.ml.shubham0204.facenet_android.coprocessor.server.StatusResponse
import com.ml.shubham0204.facenet_android.engine.EnrollResult
import com.ml.shubham0204.facenet_android.engine.FaceRecognitionEngine
import com.ml.shubham0204.facenet_android.engine.ImageDecoding
import com.ml.shubham0204.facenet_android.engine.PersonSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Single
import kotlin.time.DurationUnit
import kotlin.time.measureTimedValue

/** Connects the REST layer to the on-device engine and settings. */
@Single
class AndroidCoprocessorBackend(
    private val context: Context,
    private val engine: FaceRecognitionEngine,
    private val settings: CoprocessorSettings,
) : CoprocessorBackend {
    override suspend fun recognize(
        image: ByteArray,
        withLiveness: Boolean,
    ): RecognizeOutcome =
        withContext(Dispatchers.Default) {
            val (bitmap, decodeTime) = measureTimedValue { ImageDecoding.decode(image) }
            if (bitmap == null) throw InvalidImageException()
            val result = engine.recognize(bitmap, settings.threshold, withLiveness)
            RecognizeOutcome(result, decodeTime.toLong(DurationUnit.MILLISECONDS))
        }

    override suspend fun enroll(
        name: String,
        images: List<ByteArray>,
    ): EnrollResult =
        withContext(Dispatchers.Default) {
            val bitmaps =
                images.mapIndexed { i, bytes ->
                    ImageDecoding.decode(bytes) ?: throw InvalidImageException("Image #$i could not be decoded")
                }
            engine.enroll(name, bitmaps)
        }

    override suspend fun addImages(
        personId: Long,
        images: List<ByteArray>,
    ): EnrollResult? =
        withContext(Dispatchers.Default) {
            val bitmaps =
                images.mapIndexed { i, bytes ->
                    ImageDecoding.decode(bytes) ?: throw InvalidImageException("Image #$i could not be decoded")
                }
            engine.addImages(personId, bitmaps)
        }

    override suspend fun listPersons(): List<PersonSummary> = withContext(Dispatchers.IO) { engine.listPersons() }

    override suspend fun deletePerson(personId: Long): Boolean = withContext(Dispatchers.IO) { engine.deletePerson(personId) }

    override fun getThreshold(): Float = settings.threshold

    override fun setThreshold(value: Float): Float {
        settings.threshold = value
        return settings.threshold
    }

    override suspend fun status(): StatusResponse =
        withContext(Dispatchers.IO) {
            val device = DeviceStatus.read(context)
            val state = CoprocessorState.holder
            val running = state.status.value as? ServerStatus.Running
            StatusResponse(
                batteryPct = device.batteryPct,
                batteryTempC = device.batteryTempC,
                charging = device.charging,
                thermalStatus = device.thermalStatus,
                heapUsedMb = device.heapUsedMb,
                heapMaxMb = device.heapMaxMb,
                ignoringBatteryOptimizations = device.ignoringBatteryOptimizations,
                persons = engine.countPersons(),
                faceRecords = engine.countFaceRecords(),
                threshold = settings.threshold,
                uptimeSeconds = running?.let { (System.currentTimeMillis() - it.startedAtMillis) / 1000 } ?: 0,
                requestsServed = state.requestsServed,
                androidSdk = Build.VERSION.SDK_INT,
            )
        }
}
