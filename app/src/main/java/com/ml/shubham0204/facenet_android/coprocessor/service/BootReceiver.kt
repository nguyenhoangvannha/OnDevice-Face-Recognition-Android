package com.ml.shubham0204.facenet_android.coprocessor.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.ml.shubham0204.facenet_android.coprocessor.CoprocessorSettings
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

/**
 * Restarts the API after reboot or app update, only if the user left it enabled.
 * These broadcasts are exempt from Android 12+ background FGS-start restrictions, and
 * Android 15's BOOT_COMPLETED restriction does not cover the specialUse type.
 */
class BootReceiver :
    BroadcastReceiver(),
    KoinComponent {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action !in ACTIONS) return
        val settings: CoprocessorSettings = get()
        if (!settings.serverEnabled) return
        try {
            ContextCompat.startForegroundService(context, Intent(context, CoprocessorService::class.java))
        } catch (e: Exception) {
            Log.w("BootReceiver", "Could not start coprocessor service", e)
        }
    }

    private companion object {
        val ACTIONS =
            setOf(
                Intent.ACTION_BOOT_COMPLETED,
                "android.intent.action.QUICKBOOT_POWERON",
                Intent.ACTION_MY_PACKAGE_REPLACED,
            )
    }
}
