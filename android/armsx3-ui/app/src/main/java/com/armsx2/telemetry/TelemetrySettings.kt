package com.armsx2.telemetry

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import com.armsx2.ui.settings.ToggleRow

@Composable
fun TelemetrySettings() {
    val context = LocalContext.current
    val enabled by PerformanceTelemetry.enabled.collectAsState()
    val status by PerformanceTelemetry.status.collectAsState()
    val folder by PerformanceTelemetry.folder.collectAsState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                PerformanceTelemetry.selectFolder(uri)
            }.onFailure { PerformanceTelemetry.reportSelectionError(it) }
        }
    }
    Column {
        Text("Performance logging", style = MaterialTheme.typography.titleMedium)
        Text("Choose a folder once. Each game session then streams performance files there automatically.")
        OutlinedButton(onClick = { picker.launch(folder?.let(android.net.Uri::parse)) }) {
            Text(if (folder == null) "Choose recording folder" else "Change recording folder")
        }
        ToggleRow("Automatically record games", enabled,
            description = "Guest frames, CPU shares, pipeline timings, hardware samples and native logs.") {
            PerformanceTelemetry.setEnabled(it)
        }
        Text(status, color = if (status.startsWith("Recording failed")) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant)
        folder?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
