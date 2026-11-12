package dev.zissha.vr.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.zissha.vr.BuildConfig
import dev.zissha.vr.core.Engine
import kotlinx.coroutines.launch

/**
 * Settings screen (spec 12): camera, resolution, FPS, tracking, LOD, avatar management,
 * privacy image, recording, RTMP, Bluetooth.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(engine: Engine, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val settings by engine.settingsFlow().collectAsState(initial = dev.zissha.vr.core.Settings())
    val ui by engine.uiState.collectAsState()
    val avatars = remember { mutableStateOf(engine.avatarLoaderInstance.listAvatars()) }

    fun update(transform: (dev.zissha.vr.core.Settings) -> dev.zissha.vr.core.Settings) {
        scope.launch { engine.settingsRepository.update(transform) }
    }

    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            scope.launch {
                val name = engine.avatarLoaderInstance.importFromUri(uri, uri.lastPathSegment)
                if (name != null) {
                    update { it.copy(avatarUri = name) }
                    avatars.value = engine.avatarLoaderInstance.listAvatars()
                }
            }
        }
    }

    val privacyPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            update { it.copy(privacyImageUri = uri.toString()) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
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
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionTitle("Camera")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = settings.cameraLensFront,
                    onClick = { update { it.copy(cameraLensFront = true) } },
                    label = { Text("Front") },
                )
                FilterChip(
                    selected = !settings.cameraLensFront,
                    onClick = { update { it.copy(cameraLensFront = false) } },
                    label = { Text("Back") },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0 to "720p", 1 to "1080p").forEach { (idx, label) ->
                    FilterChip(
                        selected = settings.resolutionIndex == idx,
                        onClick = { update { it.copy(resolutionIndex = idx) } },
                        label = { Text(label) },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(24, 30, 60).forEach { fps ->
                    FilterChip(
                        selected = settings.targetFps == fps,
                        onClick = { update { it.copy(targetFps = fps) } },
                        label = { Text("${fps}fps") },
                    )
                }
            }

            SectionTitle("Tracking")
            ToggleRow("Person segmentation", settings.segmentationEnabled) { v ->
                update { it.copy(segmentationEnabled = v) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("LOD", style = MaterialTheme.typography.bodyMedium)
                FilterChip(
                    selected = settings.lodAuto,
                    onClick = { update { it.copy(lodAuto = true) } },
                    label = { Text("Auto") },
                )
                listOf(0, 1, 2).forEach { lod ->
                    FilterChip(
                        selected = !settings.lodAuto && settings.forcedLod == lod,
                        onClick = { update { it.copy(lodAuto = false, forcedLod = lod) } },
                        label = { Text("L$lod") },
                    )
                }
            }
            Text(
                "Current LOD: ${ui.stats.lod} / governor L${ui.governorLevel} (${ui.thermalStatusText})",
                style = MaterialTheme.typography.bodySmall,
            )

            SectionTitle("Avatar")
            Button(onClick = { avatarPicker.launch(arrayOf("*/*")) }) { Text("Import VRM / glb") }
            if (ui.avatarLoading) Text("loading avatar...", style = MaterialTheme.typography.bodySmall)
            avatars.value.forEach { avatar ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        avatar.name + if (avatar.name == settings.avatarUri) "  ✓" else "",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = {
                        update { it.copy(avatarUri = avatar.name) }
                    }) { Text("Use") }
                    OutlinedButton(onClick = {
                        engine.avatarLoaderInstance.deleteAvatar(avatar.name)
                        avatars.value = engine.avatarLoaderInstance.listAvatars()
                    }) { Text("Del") }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Scale", style = MaterialTheme.typography.bodyMedium)
                listOf(0.8f, 1.0f, 1.2f).forEach { s ->
                    FilterChip(
                        selected = (settings.avatarScale - s).let { it > -0.05f && it < 0.05f },
                        onClick = {
                            update { it.copy(avatarScale = s) }
                            engine.setAvatarScale(s)
                        },
                        label = { Text("x%.1f".format(s)) },
                    )
                }
            }

            SectionTitle("Privacy image")
            Button(onClick = { privacyPicker.launch(arrayOf("image/*")) }) { Text("Choose PRIVACY image") }
            if (settings.privacyImageUri.isNotEmpty()) {
                OutlinedButton(onClick = { update { it.copy(privacyImageUri = "") } }) {
                    Text("Reset to default")
                }
            }

            SectionTitle("Recording / Streaming")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Audio", style = MaterialTheme.typography.bodyMedium)
                Switch(checked = settings.audioEnabled, onCheckedChange = { v ->
                    update { it.copy(audioEnabled = v) }
                })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(2000, 4000, 6000, 8000).forEach { kbps ->
                    FilterChip(
                        selected = settings.videoBitrateKbps == kbps,
                        onClick = { update { it.copy(videoBitrateKbps = kbps) } },
                        label = { Text("${kbps}k") },
                    )
                }
            }
            var rtmpUrl by remember(settings.rtmpUrl) { mutableStateOf(settings.rtmpUrl) }
            var streamKey by remember(settings.streamKey) { mutableStateOf(settings.streamKey) }
            OutlinedTextField(
                value = rtmpUrl,
                onValueChange = { rtmpUrl = it },
                label = { Text("RTMP URL (rtmp://server/live)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = streamKey,
                onValueChange = { streamKey = it },
                label = { Text("Stream key (stored separately, never logged)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Button(onClick = { update { it.copy(rtmpUrl = rtmpUrl.trim(), streamKey = streamKey.trim()) } }) {
                Text("Save RTMP settings")
            }

            SectionTitle("Bluetooth remote")
            ToggleRow("BLE peripheral enabled", settings.bleEnabled) { v ->
                update { it.copy(bleEnabled = v) }
                if (v) {
                    // restart peripheral via engine setting propagation happens on next start()
                }
            }
            Text(
                "Pairing token: ${ui.bleToken.ifEmpty { "(BLE inactive)" }}",
                style = MaterialTheme.typography.bodyMedium,
            )

            HorizontalDivider()
            Text(
                "Build ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 24.dp),
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
