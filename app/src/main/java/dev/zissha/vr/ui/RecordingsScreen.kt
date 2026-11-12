package dev.zissha.vr.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.zissha.vr.core.Engine
import dev.zissha.vr.record.RecordingManager
import java.io.File

/** Recordings list (spec 8: saved MP4s; share/delete). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingsScreen(engine: Engine, onBack: () -> Unit) {
    val context = LocalContext.current
    val recordings by remember { mutableStateOf(engine.recordings()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Recordings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (recordings.isEmpty()) {
                Text("No recordings yet.", style = MaterialTheme.typography.bodyMedium)
            }
            recordings.forEach { rec ->
                Column {
                    Text(rec.file.name, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${RecordingManager.formatSize(rec.sizeBytes)} · ${RecordingManager.formatTimestamp(rec.lastModified)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            val share = Intent(Intent.ACTION_SEND).apply {
                                type = "video/mp4"
                                putExtra(Intent.EXTRA_STREAM, fileUri(context, rec.file))
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(share, "Share recording"))
                        }) { Text("Share") }
                        OutlinedButton(onClick = {
                            engine.deleteRecording(rec.file)
                        }) { Text("Delete") }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

private fun fileUri(context: android.content.Context, file: File): android.net.Uri {
    return androidx.core.content.FileProvider.getUriForFile(
        context,
        context.packageName + ".fileprovider",
        file,
    )
}
