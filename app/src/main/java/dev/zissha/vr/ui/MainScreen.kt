package dev.zissha.vr.ui

import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.zissha.protocol.OutputMode
import dev.zissha.vr.core.Engine

/**
 * Main screen (spec 12): preview + status + LIVE/FREEZE/PRIVACY + record/stream controls.
 * The preview SurfaceView is handed to the render thread; UI recomposition never blocks the
 * camera / inference / encode pipeline.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(engine: Engine, onOpenSettings: () -> Unit, onOpenRecordings: () -> Unit) {
    val ui by engine.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ZisshaVR") },
                actions = {
                    IconButton(onClick = onOpenRecordings) {
                        Icon(Icons.Filled.Videocam, contentDescription = "recordings")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "settings")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color.Black),
            ) {
                AndroidView(
                    factory = { context ->
                        SurfaceView(context).also { sv ->
                            sv.holder.addCallback(object : android.view.SurfaceHolder.Callback {
                                override fun surfaceCreated(holder: android.view.SurfaceHolder) {
                                    engine.setPreviewSurface(holder.surface)
                                }

                                override fun surfaceChanged(holder: android.view.SurfaceHolder, format: Int, width: Int, height: Int) {
                                    engine.setPreviewSurface(holder.surface)
                                }

                                override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {
                                    engine.setPreviewSurface(null)
                                }
                            })
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )

                // status overlay
                Column(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    StatusChip(
                        text = "MODE: ${ui.mode}" + (ui.gateTrigger?.let { " ($it)" } ?: ""),
                        color = when (ui.mode) {
                            OutputMode.LIVE -> Color(0xFF1E88E5)
                            OutputMode.FREEZE -> Color(0xFFFFA000)
                            OutputMode.PRIVACY -> Color(0xFF8E24AA)
                        },
                    )
                    StatusChip(
                        text = if (ui.trackingHealthy) "TRACKING: OK" else "TRACKING: LOST",
                        color = if (ui.trackingHealthy) Color(0xFF2E7D32) else Color(0xFFC62828),
                    )
                    StatusChip(text = "LOD: ${ui.stats.lod}", color = Color(0xFF37474F))
                    StatusChip(
                        text = "FPS: %.1f  infer: %.1fms".format(ui.stats.fps, ui.stats.poseMs + ui.stats.faceMs + ui.stats.handMs + ui.stats.segmentMs),
                        color = Color(0xFF37474F),
                    )
                    StatusChip(
                        text = "THERMAL: ${ui.thermalStatusText} (L${ui.governorLevel})",
                        color = if (ui.governorLevel >= 3) Color(0xFFC62828) else Color(0xFF37474F),
                    )
                    StatusChip(
                        text = if (ui.recording) "REC ●" else "REC",
                        color = if (ui.recording) Color(0xFFC62828) else Color(0xFF37474F),
                    )
                    StatusChip(
                        text = "RTMP: ${ui.streamState}",
                        color = if (ui.streamState == dev.zissha.vr.stream.StreamState.STREAMING) Color(0xFF2E7D32) else Color(0xFF37474F),
                    )
                    if (ui.avatarName.isNotEmpty()) {
                        StatusChip(text = "AVATAR: ${ui.avatarName}", color = Color(0xFF37474F))
                    }
                    if (!ui.canRecover && ui.mode == OutputMode.FREEZE) {
                        StatusChip(text = "Recovery requires user action", color = Color(0xFF6D4C41))
                    }
                }
            }

            // mode buttons (spec 7.4: large buttons, synchronous switching)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { engine.setMode(OutputMode.LIVE) },
                    enabled = ui.mode == OutputMode.LIVE || ui.canRecover,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E88E5)),
                    modifier = Modifier.weight(1f).height(56.dp),
                ) { Text("LIVE") }
                Button(
                    onClick = { engine.setMode(OutputMode.FREEZE) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFA000)),
                    modifier = Modifier.weight(1f).height(56.dp),
                ) { Text("FREEZE") }
                Button(
                    onClick = { engine.setMode(OutputMode.PRIVACY) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8E24AA)),
                    modifier = Modifier.weight(1f).height(56.dp),
                ) { Text("PRIVACY") }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { if (ui.recording) engine.stopRecording() else engine.startRecording() },
                    modifier = Modifier.weight(1f),
                ) { Text(if (ui.recording) "STOP RECORD" else "RECORD") }
                Button(
                    onClick = { if (ui.streamState == dev.zissha.vr.stream.StreamState.STREAMING) engine.stopStreaming() else engine.startStreaming() },
                    modifier = Modifier.weight(1f),
                ) { Text(if (ui.streamState == dev.zissha.vr.stream.StreamState.STREAMING) "STOP STREAM" else "STREAM") }
            }

            if (ui.bleRunning) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "BLE Remote: ${if (ui.bleConnected) "connected" else "waiting"}  token: ${ui.bleToken}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

/** Access bridge kept minimal: status chips. */
@Composable
fun StatusChip(text: String, color: Color) {
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.75f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(text, color = Color.White, style = MaterialTheme.typography.labelSmall)
    }
}
