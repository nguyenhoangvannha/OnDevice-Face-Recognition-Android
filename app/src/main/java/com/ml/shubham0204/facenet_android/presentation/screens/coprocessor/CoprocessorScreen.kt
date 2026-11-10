package com.ml.shubham0204.facenet_android.presentation.screens.coprocessor

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ml.shubham0204.facenet_android.coprocessor.RequestLogEntry
import com.ml.shubham0204.facenet_android.coprocessor.ServerStatus
import com.ml.shubham0204.facenet_android.presentation.theme.FaceNetAndroidTheme
import org.koin.androidx.compose.koinViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoprocessorScreen(onNavigateBack: () -> Unit) {
    val viewModel: CoprocessorViewModel = koinViewModel()
    FaceNetAndroidTheme {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = { Text("Coprocessor Server", style = MaterialTheme.typography.headlineSmall) },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Navigate Back")
                        }
                    },
                )
            },
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier.padding(innerPadding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { ServerCard(viewModel) }
                item { NetworkCard(viewModel) }
                item { TokenCard(viewModel) }
                item { ThresholdCard(viewModel) }
                item { DeviceCard(viewModel) }
                item { Text("Recent requests", style = MaterialTheme.typography.titleMedium) }
                item { RecentRequests(viewModel) }
                item { Spacer(Modifier.padding(8.dp)) }
            }
        }
    }
}

@Composable
private fun Section(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun ServerCard(viewModel: CoprocessorViewModel) {
    val status by viewModel.serverStatus.collectAsState()
    val enabled by viewModel.serverEnabled
    val notificationPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            // The service runs either way; without permission its notification is just hidden.
            viewModel.setServerEnabled(true)
        }
    Section("Server") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(statusText(status), modifier = Modifier.weight(1f))
            Switch(
                checked = enabled,
                onCheckedChange = { on ->
                    if (on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        viewModel.setServerEnabled(on)
                    }
                },
            )
        }
        Text(
            "When enabled, the server also restarts after a reboot.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun statusText(status: ServerStatus): String =
    when (status) {
        ServerStatus.Stopped -> "Stopped"
        ServerStatus.Starting -> "Starting (loading models)…"
        is ServerStatus.Running -> "Running on port ${status.port}"
        is ServerStatus.Failed -> "Failed: ${status.message}"
    }

@Composable
private fun NetworkCard(viewModel: CoprocessorViewModel) {
    val addresses by viewModel.addresses
    val port by viewModel.port
    var portText by viewModel.portText
    val portError by viewModel.portError
    Section("Network") {
        if (addresses.isEmpty()) {
            Text("No IPv4 network")
        } else {
            addresses.forEach {
                Text("${it.interfaceName}: http://${it.address}:$port", fontFamily = FontFamily.Monospace)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = portText,
                onValueChange = { portText = it.filter(Char::isDigit).take(5) },
                label = { Text("Port") },
                singleLine = true,
                isError = portError != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { viewModel.applyPort() }) { Text("Save") }
        }
        Text(
            portError ?: "A new port applies the next time the server starts.",
            style = MaterialTheme.typography.bodySmall,
            color = if (portError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TokenCard(viewModel: CoprocessorViewModel) {
    val context = LocalContext.current
    val token by viewModel.token
    var visible by remember { mutableStateOf(false) }
    var confirmRegenerate by remember { mutableStateOf(false) }
    Section("API token") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (visible) token else "•".repeat(12),
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = if (visible) "Hide token" else "Show token",
                )
            }
            IconButton(onClick = { copySensitive(context, token) }) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Copy token")
            }
        }
        Text("Send as: Authorization: Bearer <token>", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { confirmRegenerate = true }) { Text("Regenerate token") }
    }
    if (confirmRegenerate) {
        AlertDialog(
            onDismissRequest = { confirmRegenerate = false },
            title = { Text("Regenerate token?") },
            text = { Text("Clients using the current token will get 401 until they are updated.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.regenerateToken()
                    confirmRegenerate = false
                }) { Text("Regenerate") }
            },
            dismissButton = { TextButton(onClick = { confirmRegenerate = false }) { Text("Cancel") } },
        )
    }
}

private fun copySensitive(
    context: Context,
    text: String,
) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    val clip = ClipData.newPlainText("API token", text)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        // Hides the token from the Android 13+ clipboard preview.
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }
    clipboard.setPrimaryClip(clip)
}

@Composable
private fun ThresholdCard(viewModel: CoprocessorViewModel) {
    val threshold by viewModel.threshold
    Section("Similarity threshold") {
        Text(String.format(Locale.US, "%.2f (cosine, API only)", threshold))
        Slider(
            value = threshold.coerceIn(THRESHOLD_MIN, THRESHOLD_MAX),
            onValueChange = viewModel::onThresholdChange,
            onValueChangeFinished = viewModel::commitThreshold,
            valueRange = THRESHOLD_MIN..THRESHOLD_MAX,
        )
        Text(
            "Not calibrated: tune with real camera snapshots. The live camera screen keeps its own 0.30.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private const val THRESHOLD_MIN = 0.30f
private const val THRESHOLD_MAX = 0.95f

@SuppressLint("BatteryLife")
@Composable
private fun DeviceCard(viewModel: CoprocessorViewModel) {
    val context = LocalContext.current
    val device by viewModel.device
    Section("Device") {
        Text("Battery: ${device.batteryPct?.let { "$it%" } ?: "?"}" + if (device.charging == true) " (charging)" else "")
        Text("Battery temperature: ${device.batteryTempC?.let { String.format(Locale.US, "%.1f °C", it) } ?: "?"}")
        Text("Thermal status: ${device.thermalStatus ?: "n/a"} (SoC temperature is not exposed to apps)")
        Text("App heap: ${device.heapUsedMb} / ${device.heapMaxMb} MB")
        Text("Battery optimization: " + if (device.ignoringBatteryOptimizations) "unrestricted" else "optimized (may be killed)")
        if (!device.ignoringBatteryOptimizations) {
            OutlinedButton(onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
                )
            }) { Text("Allow running in background") }
        }
    }
}

@Composable
private fun RecentRequests(viewModel: CoprocessorViewModel) {
    val requests by viewModel.recentRequests.collectAsState()
    if (requests.isEmpty()) {
        Text("No requests yet", style = MaterialTheme.typography.bodySmall)
        return
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            requests.forEach { RequestRow(it) }
        }
    }
}

private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

@Composable
private fun RequestRow(entry: RequestLogEntry) {
    val details =
        buildList {
            entry.inferenceMs?.let { add("inference_ms=$it") }
            entry.faceCount?.let { add("faces=$it" + (entry.recognizedCount?.let { r -> " known=$r" } ?: "")) }
            add("total=${entry.durationMs}ms")
            entry.remoteHost?.let { add("from $it") }
        }.joinToString(" · ")
    Text(
        "${timeFormat.format(Date(entry.timeMillis))} ${entry.method} ${entry.path} ${entry.status}\n$details",
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
    )
}
