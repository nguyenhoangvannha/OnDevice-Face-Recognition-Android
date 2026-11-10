package com.ml.shubham0204.facenet_android.presentation.screens.coprocessor

import android.content.Context
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ml.shubham0204.facenet_android.coprocessor.CoprocessorSettings
import com.ml.shubham0204.facenet_android.coprocessor.CoprocessorState
import com.ml.shubham0204.facenet_android.coprocessor.DeviceStatus
import com.ml.shubham0204.facenet_android.coprocessor.service.CoprocessorService
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.koin.android.annotation.KoinViewModel

@KoinViewModel
class CoprocessorViewModel(
    private val context: Context,
    private val settings: CoprocessorSettings,
) : ViewModel() {
    val serverStatus = CoprocessorState.holder.status
    val recentRequests = CoprocessorState.holder.recentRequests

    val serverEnabled = mutableStateOf(settings.serverEnabled)
    val token = mutableStateOf(settings.token)
    val threshold = mutableFloatStateOf(settings.threshold)
    val port = mutableStateOf(settings.port)
    val portText = mutableStateOf(settings.port.toString())
    val portError = mutableStateOf<String?>(null)
    val device = mutableStateOf(DeviceStatus.read(context))
    val addresses = mutableStateOf(DeviceStatus.ipv4Addresses())

    init {
        viewModelScope.launch {
            while (isActive) {
                delay(REFRESH_MS)
                refresh()
            }
        }
    }

    fun refresh() {
        device.value = DeviceStatus.read(context)
        addresses.value = DeviceStatus.ipv4Addresses()
        // The notification's Stop action changes this flag outside the UI.
        serverEnabled.value = settings.serverEnabled
    }

    fun setServerEnabled(enabled: Boolean) {
        if (enabled) CoprocessorService.start(context, settings) else CoprocessorService.stop(context, settings)
        serverEnabled.value = enabled
    }

    fun onThresholdChange(value: Float) {
        threshold.floatValue = value
    }

    fun commitThreshold() {
        settings.threshold = threshold.floatValue
        threshold.floatValue = settings.threshold
    }

    fun regenerateToken() {
        token.value = settings.regenerateToken()
    }

    /** Saves the port; the running server keeps its old port until restarted. */
    fun applyPort() {
        val value = portText.value.trim().toIntOrNull()
        if (value == null || value !in CoprocessorSettings.PORT_RANGE) {
            portError.value = "Port must be ${CoprocessorSettings.PORT_RANGE.first}-${CoprocessorSettings.PORT_RANGE.last}"
            return
        }
        settings.port = value
        port.value = value
        portError.value = null
    }

    private companion object {
        const val REFRESH_MS = 5_000L
    }
}
