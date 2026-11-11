package com.ml.shubham0204.facenet_android.di

import android.content.Context
import com.ml.shubham0204.facenet_android.coprocessor.CoprocessorSettings
import com.ml.shubham0204.facenet_android.coprocessor.backend.AndroidModelProvider
import com.ml.shubham0204.facenet_android.domain.face_detection.BaseFaceDetector
import com.ml.shubham0204.facenet_android.domain.face_detection.MLKitFaceDetector
import com.ml.shubham0204.facenet_android.data.HouseholdDB
import com.ml.shubham0204.facenet_android.data.ImagesVectorDB
import com.ml.shubham0204.facenet_android.data.PersonDB
import com.ml.shubham0204.facenet_android.data.StrangerDB
import com.ml.shubham0204.facenet_android.engine.backend.BackendController
import com.ml.shubham0204.facenet_android.engine.strangers.StrangerClusterer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single

@Module
@ComponentScan("com.ml.shubham0204.facenet_android")
class AppModule {

    @Single
    fun provideFaceDetector(context: Context): BaseFaceDetector = MLKitFaceDetector(context)

    /** Stranger clusters for `cluster_strangers=true`; ObjectBox-backed, embeddings only. */
    @Single
    fun provideStrangerClusterer(
        personDB: PersonDB,
        imagesVectorDB: ImagesVectorDB,
    ): StrangerClusterer = StrangerClusterer(StrangerDB(), HouseholdDB(personDB, imagesVectorDB))

    /** Process-wide: owns the loaded FaceNet model and background model switches/downloads. */
    @Single
    fun provideBackendController(
        context: Context,
        settings: CoprocessorSettings,
    ): BackendController =
        BackendController(
            provider = AndroidModelProvider(context),
            prefs = settings,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
}
