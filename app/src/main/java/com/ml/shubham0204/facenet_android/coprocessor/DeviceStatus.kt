package com.ml.shubham0204.facenet_android.coprocessor

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import java.net.Inet4Address
import java.net.NetworkInterface

data class DeviceSnapshot(
    val batteryPct: Int?,
    val batteryTempC: Float?,
    val charging: Boolean?,
    /** PowerManager thermal status name; null below Android 10. SoC temperature has no public API. */
    val thermalStatus: String?,
    val heapUsedMb: Long,
    val heapMaxMb: Long,
    val ignoringBatteryOptimizations: Boolean,
)

data class NetworkAddress(
    val interfaceName: String,
    val address: String,
)

object DeviceStatus {
    fun read(context: Context): DeviceSnapshot {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val tempTenths = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1

        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val thermal =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) thermalName(power.currentThermalStatus) else null

        val runtime = Runtime.getRuntime()
        val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / MB
        return DeviceSnapshot(
            batteryPct = if (level >= 0 && scale > 0) level * 100 / scale else null,
            batteryTempC = if (tempTenths != Int.MIN_VALUE) tempTenths / 10f else null,
            charging = if (plugged >= 0) plugged != 0 else null,
            thermalStatus = thermal,
            heapUsedMb = usedMb,
            heapMaxMb = runtime.maxMemory() / MB,
            ignoringBatteryOptimizations = power.isIgnoringBatteryOptimizations(context.packageName),
        )
    }

    /** Non-loopback IPv4 addresses (wlan0, tun0 for Tailscale, ...). */
    fun ipv4Addresses(): List<NetworkAddress> =
        try {
            NetworkInterface
                .getNetworkInterfaces()
                ?.toList()
                .orEmpty()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { nif ->
                    nif.inetAddresses.toList().filterIsInstance<Inet4Address>().map {
                        NetworkAddress(nif.name, it.hostAddress ?: "")
                    }
                }.filter { it.address.isNotEmpty() }
        } catch (e: Exception) {
            emptyList()
        }

    private fun thermalName(status: Int): String =
        when (status) {
            PowerManager.THERMAL_STATUS_NONE -> "none"
            PowerManager.THERMAL_STATUS_LIGHT -> "light"
            PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
            PowerManager.THERMAL_STATUS_SEVERE -> "severe"
            PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
            else -> "unknown"
        }

    private const val MB = 1024L * 1024L
}
