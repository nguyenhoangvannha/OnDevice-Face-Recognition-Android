package com.ml.shubham0204.facenet_android.coprocessor.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.ml.shubham0204.facenet_android.MainActivity
import com.ml.shubham0204.facenet_android.coprocessor.AndroidCoprocessorBackend
import com.ml.shubham0204.facenet_android.coprocessor.CoprocessorSettings
import com.ml.shubham0204.facenet_android.coprocessor.CoprocessorState
import com.ml.shubham0204.facenet_android.coprocessor.ServerStatus
import com.ml.shubham0204.facenet_android.coprocessor.server.FaceApiServer
import com.ml.shubham0204.facenet_android.coprocessor.server.faceApiModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Keeps the REST API alive in the background: foreground service (type specialUse),
 * partial wake lock and a high-performance Wi-Fi lock.
 */
class CoprocessorService :
    Service(),
    KoinComponent {
    private val settings: CoprocessorSettings by inject()
    private val backend: AndroidCoprocessorBackend by inject()
    private val state = CoprocessorState.holder
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var server: FaceApiServer? = null
    private var startJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (intent?.action == ACTION_STOP) {
            settings.serverEnabled = false
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        // Must be called promptly after startForegroundService(); model loading happens later.
        startInForeground("Starting face API server…")
        acquireLocks()
        if (server == null && startJob?.isActive != true) {
            state.setStatus(ServerStatus.Starting)
            startJob = scope.launch { startServer() }
        }
        return START_STICKY
    }

    private fun startServer() {
        val port = settings.port
        try {
            // Touching the backend loads FaceNet/ML Kit/ObjectBox before the port opens.
            val api = backend
            val newServer =
                FaceApiServer(CoprocessorSettings.BIND_HOST, port) {
                    faceApiModule(
                        backend = api,
                        tokenProvider = { settings.token },
                        onRequest = state::record,
                        onError = { Log.e(TAG, "Unhandled API error", it) },
                    )
                }
            newServer.start()
            server = newServer
            state.setStatus(ServerStatus.Running(port, System.currentTimeMillis()))
            updateNotification("Face API listening on port $port")
            Log.i(TAG, "Face API listening on 0.0.0.0:$port")
        } catch (e: Throwable) {
            Log.e(TAG, "Face API failed to start on port $port", e)
            state.setStatus(ServerStatus.Failed(e.message ?: e.javaClass.simpleName))
            releaseLocks()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        startJob?.cancel()
        val running = server
        server = null
        // Ktor stop() waits up to ~3 s; keep it off the main thread.
        if (running != null) Thread({ running.stop() }, "face-api-stop").start()
        if (state.status.value !is ServerStatus.Failed) state.setStatus(ServerStatus.Stopped)
        releaseLocks()
        scope.cancel()
        super.onDestroy()
    }

    private fun acquireLocks() {
        if (wakeLock == null) {
            val power = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock =
                power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FaceNet:CoprocessorServer").apply {
                    setReferenceCounted(false)
                    // Held for as long as the service runs (24/7 by design).
                    acquire()
                }
        }
        if (wifiLock == null) {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            // HIGH_PERF keeps Wi-Fi out of power save with the screen off on Android <= 13.
            // On Android 14+ the platform silently turns it into LOW_LATENCY (screen on + foreground only).
            @Suppress("DEPRECATION")
            val mode = WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock =
                wifi.createWifiLock(mode, "FaceNet:CoprocessorServer").apply {
                    setReferenceCounted(false)
                    acquire()
                }
        }
    }

    private fun releaseLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
    }

    private fun startInForeground(text: String) {
        createChannel()
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel =
            NotificationChannel(CHANNEL_ID, "Coprocessor server", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while the face recognition API is running"
                setShowBadge(false)
            }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String) =
        NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("Face coprocessor")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            ).addAction(
                0,
                "Stop",
                PendingIntent.getService(
                    this,
                    1,
                    Intent(this, CoprocessorService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            ).build()

    companion object {
        private const val TAG = "CoprocessorService"
        private const val CHANNEL_ID = "coprocessor_server"
        private const val NOTIFICATION_ID = 8080
        const val ACTION_STOP = "com.ml.shubham0204.facenet_android.coprocessor.STOP"

        /** Marks the server as enabled (survives reboot) and starts it. */
        fun start(context: Context, settings: CoprocessorSettings) {
            settings.serverEnabled = true
            ContextCompat.startForegroundService(context, Intent(context, CoprocessorService::class.java))
        }

        /** Marks the server as disabled and stops it. */
        fun stop(context: Context, settings: CoprocessorSettings) {
            settings.serverEnabled = false
            context.stopService(Intent(context, CoprocessorService::class.java))
        }
    }
}
