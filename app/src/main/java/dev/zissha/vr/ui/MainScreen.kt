package dev.zissha.vr.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zissha.protocol.OutputMode
import dev.zissha.ui.components.StatusPill
import dev.zissha.ui.theme.ZisshaTheme
import dev.zissha.vr.core.Engine
import dev.zissha.vr.core.EngineUiState
import dev.zissha.vr.stream.StreamState

/**
 * Main screen (spec 12): preview, health/stat readout, LIVE/FREEZE/PRIVACY mode switching and
 * record/stream controls.
 *
 * The preview SurfaceView is handed to the render thread; UI recomposition never touches the
 * camera / inference / encode pipeline (spec 12: 画面と映像処理の分離).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(engine: Engine, onOpenSettings: () -> Unit, onOpenRecordings: () -> Unit) {
    val ui by engine.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Surface engine errors once, then clear so they do not repeat on recomposition.
    LaunchedEffect(ui.lastError) {
        val message = ui.lastError
        if (!message.isNullOrBlank()) {
            snackbarHostState.showSnackbar(message)
            engine.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ZisshaVR") },
                actions = {
                    IconButton(onClick = onOpenRecordings) {
                        Icon(Icons.Filled.Videocam, contentDescription = "録画一覧")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "設定")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            PreviewPane(
                engine = engine,
                ui = ui,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
            StatsRow(ui)
            ModeControls(engine, ui)
            OutputControls(engine, ui)
            if (ui.avatarName.isEmpty() && !ui.avatarLoading) {
                AvatarHint(onOpenSettings)
            }
            if (ui.bleRunning) {
                BleCard(ui)
            }
        }
    }
}

/** Preview surface + non-blocking status overlay (kept minimal so it never hides the frame). */
@Composable
private fun PreviewPane(engine: Engine, ui: EngineUiState, modifier: Modifier = Modifier) {
    val sem = ZisshaTheme.semantic
    Box(
        modifier = modifier.background(Color.Black),
    ) {
        AndroidView(
            factory = { context ->
                SurfaceView(context).also { sv ->
                    sv.holder.addCallback(object : android.view.SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: android.view.SurfaceHolder) {
                            engine.setPreviewSurface(holder.surface)
                        }

                        override fun surfaceChanged(
                            holder: android.view.SurfaceHolder,
                            format: Int,
                            width: Int,
                            height: Int,
                        ) {
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

        // Top-left: current output state.
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val (modeColor, modeInk) = when (ui.mode) {
                OutputMode.LIVE -> sem.live to sem.onLive
                OutputMode.FREEZE -> sem.freeze to sem.onFreeze
                OutputMode.PRIVACY -> sem.privacy to sem.onPrivacy
            }
            StatusPill(
                text = buildString {
                    append(modeShortLabel(ui.mode))
                    ui.gateTrigger?.let { append(" · ${triggerLabel(it)}") }
                },
                color = modeColor,
                contentColor = modeInk,
                dot = true,
            )
            StatusPill(
                text = if (ui.trackingHealthy) "トラッキング 正常" else "トラッキング 検出なし",
                color = if (ui.trackingHealthy) sem.ok else sem.danger,
                dot = true,
            )
        }

        // Top-right: active output + thermal warning.
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(10.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (ui.recording) {
                StatusPill(text = "REC", color = sem.danger, dot = true)
            }
            if (ui.streamState == StreamState.STREAMING) {
                StatusPill(text = "配信中", color = sem.ok, dot = true)
            }
            if (ui.governorLevel >= 3) {
                StatusPill(text = "温度注意 L${ui.governorLevel}", color = sem.warning, contentColor = sem.onFill)
            }
        }

        // Bottom-left: recovery hint while not live.
        if (ui.mode != OutputMode.LIVE) {
            StatusPill(
                text = if (ui.canRecover) "「通常配信」で復帰できます" else "トラッキング確認中…",
                color = Color(0xCC3E2723),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(10.dp),
            )
        }

        // Camera failure overlay with a retry action.
        if (ui.previewSizeError) {
            Card(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        Icons.Filled.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Text(
                        text = "カメラを開始できませんでした",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = "権限とカメラの使用状況を確認して再試行してください。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        textAlign = TextAlign.Center,
                    )
                    Button(onClick = { engine.retryCamera() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("再試行", modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
}

/** Compact, wrapping readout of performance + output state below the preview. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatsRow(ui: EngineUiState) {
    val sem = ZisshaTheme.semantic
    val neutral = MaterialTheme.colorScheme.surfaceVariant
    val neutralInk = MaterialTheme.colorScheme.onSurfaceVariant
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        StatusPill("LOD ${ui.stats.lod}", neutral, contentColor = neutralInk)
        StatusPill("推定 %.0f".format(ui.stats.fps), neutral, contentColor = neutralInk)
        StatusPill("描画 %.0f".format(ui.renderFps), neutral, contentColor = neutralInk)
        StatusPill(
            "推論 %.0fms".format(ui.stats.poseMs + ui.stats.faceMs + ui.stats.handMs + ui.stats.segmentMs),
            neutral,
            contentColor = neutralInk,
        )
        StatusPill(
            "温度 ${ui.thermalStatusText} (L${ui.governorLevel})",
            if (ui.governorLevel >= 3) sem.warning else neutral,
            contentColor = if (ui.governorLevel >= 3) sem.onFill else neutralInk,
        )
        StatusPill(
            if (ui.recording) "録画中" else "録画停止",
            if (ui.recording) sem.danger else neutral,
            contentColor = if (ui.recording) Color.White else neutralInk,
        )
        StatusPill(
            "配信 ${streamStateLabel(ui.streamState)}",
            if (ui.streamState == StreamState.STREAMING) sem.ok else neutral,
            contentColor = if (ui.streamState == StreamState.STREAMING) Color.White else neutralInk,
        )
        if (ui.avatarName.isNotEmpty()) {
            StatusPill("アバター ${ui.avatarName}", neutral, contentColor = neutralInk)
            StatusPill("ボーン ${ui.avatarBones}", neutral, contentColor = neutralInk)
            StatusPill(
                if (ui.poseApplied) "ポーズ ○" else "ポーズ ×",
                if (ui.poseApplied) sem.ok else sem.danger,
                contentColor = Color.White,
            )
        }
    }
}

/** Large, synchronous mode buttons (spec 7.4). PRIVACY is always available. */
@Composable
private fun ModeControls(engine: Engine, ui: EngineUiState) {
    val sem = ZisshaTheme.semantic
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = { engine.setMode(OutputMode.LIVE) },
            enabled = ui.mode == OutputMode.LIVE || ui.canRecover,
            colors = ButtonDefaults.buttonColors(
                containerColor = sem.live,
                contentColor = sem.onLive,
            ),
            modifier = Modifier
                .weight(1f)
                .height(64.dp),
        ) {
            Text("通常配信", fontWeight = FontWeight.Bold)
        }
        Button(
            onClick = { engine.setMode(OutputMode.FREEZE) },
            enabled = ui.mode != OutputMode.FREEZE,
            colors = ButtonDefaults.buttonColors(
                containerColor = sem.freeze,
                contentColor = sem.onFreeze,
            ),
            modifier = Modifier
                .weight(1f)
                .height(64.dp),
        ) {
            Text("映像停止", fontWeight = FontWeight.Bold)
        }
        Button(
            onClick = { engine.setMode(OutputMode.PRIVACY) },
            enabled = ui.mode != OutputMode.PRIVACY,
            colors = ButtonDefaults.buttonColors(
                containerColor = sem.privacy,
                contentColor = sem.onPrivacy,
            ),
            modifier = Modifier
                .weight(1.15f)
                .height(64.dp),
        ) {
            Text("プライバシー", fontWeight = FontWeight.Bold)
        }
    }
}

/** Recording / streaming toggles with explicit state colours. */
@Composable
private fun OutputControls(engine: Engine, ui: EngineUiState) {
    val sem = ZisshaTheme.semantic
    val idleContainer = MaterialTheme.colorScheme.secondaryContainer
    val idleInk = MaterialTheme.colorScheme.onSecondaryContainer
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = { if (ui.recording) engine.stopRecording() else engine.startRecording() },
            colors = ButtonDefaults.buttonColors(
                containerColor = if (ui.recording) sem.danger else idleContainer,
                contentColor = if (ui.recording) Color.White else idleInk,
            ),
            modifier = Modifier
                .weight(1f)
                .height(56.dp),
        ) {
            Icon(
                if (ui.recording) Icons.Filled.Stop else Icons.Filled.FiberManualRecord,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Text(
                if (ui.recording) "録画停止" else "録画開始",
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        val streaming = ui.streamState == StreamState.STREAMING ||
            ui.streamState == StreamState.CONNECTING ||
            ui.streamState == StreamState.RECONNECTING
        Button(
            onClick = { if (streaming) engine.stopStreaming() else engine.startStreaming() },
            colors = ButtonDefaults.buttonColors(
                containerColor = if (streaming) sem.ok else idleContainer,
                contentColor = if (streaming) Color.White else idleInk,
            ),
            modifier = Modifier
                .weight(1f)
                .height(56.dp),
        ) {
            Icon(
                if (streaming) Icons.Filled.Stop else Icons.Filled.Cast,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Text(
                if (streaming) "配信停止" else "配信開始",
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Composable
private fun AvatarHint(onOpenSettings: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Filled.PersonAdd, contentDescription = null)
            Text(
                text = "アバターが未設定です",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onOpenSettings) { Text("設定を開く") }
        }
    }
}

@Composable
private fun BleCard(ui: EngineUiState) {
    val context = LocalContext.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (ui.bleConnected) "BLEリモコン 接続済み" else "BLEリモコン 待機中",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "認証コード ${ui.bleToken.ifEmpty { "----" }}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            if (ui.bleToken.isNotEmpty()) {
                IconButton(onClick = {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    clipboard?.setPrimaryClip(ClipData.newPlainText("ZisshaVR pairing code", ui.bleToken))
                }) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = "認証コードをコピー")
                }
            }
        }
    }
}
