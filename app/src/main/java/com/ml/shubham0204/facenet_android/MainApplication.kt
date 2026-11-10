package com.ml.shubham0204.facenet_android

import android.app.Application
import android.util.Log
import com.ml.shubham0204.facenet_android.data.ObjectBoxStore
import com.ml.shubham0204.facenet_android.di.AppModule
import com.ml.shubham0204.facenet_android.engine.backend.BackendController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.ksp.generated.module

class MainApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@MainApplication)
            modules(AppModule().module)
        }
        ObjectBoxStore.init(this)
        // Load the FaceNet model early (CPU first, then the chosen backend in the background).
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            runCatching { GlobalContext.get().get<BackendController>().initialize() }
                .onFailure { Log.e("MainApplication", "FaceNet model init failed", it) }
        }
    }
}
