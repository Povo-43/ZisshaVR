package dev.zissha.remote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zissha.protocol.OutputMode
import dev.zissha.ui.components.InfoRow
import dev.zissha.ui.components.SectionCard
import dev.zissha.ui.components.StatusPill
import dev.zissha.ui.theme.ZisshaTheme

/**
 * Remote control screen (spec 10.2). FREEZE and PRIVACY are large and clearly separated so they
 * are easy to hit and hard to trigger by accident.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteScreen(central: BleCentral, onScan: () -> Unit) {
    val state by central.state.collectAsStateWithLifecycle()
    val status by central.status.collectAsStateWithLifecycle()
    val authorized = state == BleCentral.ConnectionState.AUTHORIZED
    var token by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ZisshaVR リモコン") },
                actions = {
                    IconButton(onClick = { central.requestStatus() }, enabled = authorized) {
                        Icon(Icons.Filled.Refresh, contentDescription = "状態を更新")
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ConnectionSection(state = state, onScan = onScan)

            if (state == BleCentral.ConnectionState.CONNECTED) {
                AuthSection(
                    token = token,
                    onTokenChange = { token = it },
                    onSubmit = { central.token = token },
                )
            }

            ModeSection(
                authorized = authorized,
                currentMode = status?.mode,
                onMode = { central.setMode(it) },
            )

            OutputSection(
                authorized = authorized,
                recording = status?.recording == true,
                streaming = status?.streaming == true,
                onRecordToggle = { if (status?.recording == true) central.recordStop() else central.recordStart() },
                onStreamToggle = { if (status?.streaming == true) central.streamStop() else central.streamStart() },
            )

            StatusSection(status)
        }
    }
}

@Composable
private fun ConnectionSection(state: BleCentral.ConnectionState, onScan: () -> Unit) {
    val sem = ZisshaTheme.semantic
    val (color, ink) = when (state) {
        BleCentral.ConnectionState.IDLE -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
        BleCentral.ConnectionState.SCANNING,
        BleCentral.ConnectionState.CONNECTING,
        BleCentral.ConnectionState.CONNECTED,
        -> sem.warning to sem.onFill
        BleCentral.ConnectionState.AUTHORIZED -> sem.ok to Color.White
        BleCentral.ConnectionState.FAILED -> sem.danger to Color.White
    }

    SectionCard(title = "接続") {
        StatusPill(
            text = connectionLabel(state),
            color = color,
            contentColor = ink,
            dot = true,
        )
        when (state) {
            BleCentral.ConnectionState.SCANNING,
            BleCentral.ConnectionState.CONNECTING,
            -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                Text("しばらくお待ちください…", style = MaterialTheme.typography.bodySmall)
            }

            BleCentral.ConnectionState.IDLE,
            BleCentral.ConnectionState.FAILED,
            -> Button(onClick = onScan, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Search, contentDescription = null)
                Text("ZisshaVRを検索", modifier = Modifier.padding(start = 8.dp))
            }

            else -> Unit
        }
        Text(
            text = "メイン端末のZisshaVRを起動し、Bluetoothを有効にしてください。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AuthSection(token: String, onTokenChange: (String) -> Unit, onSubmit: () -> Unit) {
    SectionCard(
        title = "認証",
        subtitle = "メイン端末の設定に表示される6桁のコードを入力します。",
    ) {
        OutlinedTextField(
            value = token,
            onValueChange = { input ->
                onTokenChange(input.filter { it.isDigit() }.take(6))
            },
            label = { Text("6桁のコード") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = onSubmit,
            enabled = token.length == 6,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
            Text("認証する", modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun ModeSection(authorized: Boolean, currentMode: OutputMode?, onMode: (OutputMode) -> Unit) {
    val sem = ZisshaTheme.semantic
    SectionCard(
        title = "出力モード",
        subtitle = if (authorized) null else "認証すると操作できます。",
    ) {
        currentMode?.let { mode ->
            val (color, ink) = when (mode) {
                OutputMode.LIVE -> sem.live to sem.onLive
                OutputMode.FREEZE -> sem.freeze to sem.onFreeze
                OutputMode.PRIVACY -> sem.privacy to sem.onPrivacy
            }
            StatusPill(text = "現在: ${remoteModeLabel(mode)}", color = color, contentColor = ink)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { onMode(OutputMode.LIVE) },
                enabled = authorized && currentMode != OutputMode.LIVE,
                colors = ButtonDefaults.buttonColors(
                    containerColor = sem.live,
                    contentColor = sem.onLive,
                ),
                modifier = Modifier.weight(1f).height(64.dp),
            ) { Text("通常配信", fontWeight = FontWeight.Bold) }
            Button(
                onClick = { onMode(OutputMode.FREEZE) },
                enabled = authorized && currentMode != OutputMode.FREEZE,
                colors = ButtonDefaults.buttonColors(
                    containerColor = sem.freeze,
                    contentColor = sem.onFreeze,
                ),
                modifier = Modifier.weight(1f).height(64.dp),
            ) { Text("映像停止", fontWeight = FontWeight.Bold) }
        }
        Button(
            onClick = { onMode(OutputMode.PRIVACY) },
            enabled = authorized && currentMode != OutputMode.PRIVACY,
            colors = ButtonDefaults.buttonColors(
                containerColor = sem.privacy,
                contentColor = sem.onPrivacy,
            ),
            modifier = Modifier.fillMaxWidth().height(76.dp),
        ) { Text("プライバシー", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun OutputSection(
    authorized: Boolean,
    recording: Boolean,
    streaming: Boolean,
    onRecordToggle: () -> Unit,
    onStreamToggle: () -> Unit,
) {
    val sem = ZisshaTheme.semantic
    val idleContainer = MaterialTheme.colorScheme.secondaryContainer
    val idleInk = MaterialTheme.colorScheme.onSecondaryContainer
    SectionCard(title = "録画・配信") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = onRecordToggle,
                enabled = authorized,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (recording) sem.danger else idleContainer,
                    contentColor = if (recording) Color.White else idleInk,
                ),
                modifier = Modifier.weight(1f).height(56.dp),
            ) {
                Icon(
                    if (recording) Icons.Filled.Stop else Icons.Filled.FiberManualRecord,
                    contentDescription = null,
                )
                Text(
                    if (recording) "録画停止" else "録画開始",
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Button(
                onClick = onStreamToggle,
                enabled = authorized,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (streaming) sem.ok else idleContainer,
                    contentColor = if (streaming) Color.White else idleInk,
                ),
                modifier = Modifier.weight(1f).height(56.dp),
            ) {
                Icon(
                    if (streaming) Icons.Filled.Stop else Icons.Filled.Tv,
                    contentDescription = null,
                )
                Text(
                    if (streaming) "配信停止" else "配信開始",
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun StatusSection(status: dev.zissha.protocol.StatusPayload?) {
    SectionCard(title = "状態") {
        if (status == null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Filled.Bluetooth, contentDescription = null)
                Text(
                    "状態を受信していません。接続と認証を確認してください。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            return@SectionCard
        }
        InfoRow("モード", remoteModeLabel(status.mode))
        InfoRow("トラッキング", if (status.trackingHealthy) "正常" else "検出なし")
        InfoRow("LOD", "L${status.lod} / 負荷 L${status.thermalStatus}")
        InfoRow("FPS / 推論", "%.1f / %.0fms".format(status.fps, status.inferenceMs), valueMonospace = true)
        InfoRow("録画", if (status.recording) "実行中" else "停止")
        InfoRow("配信", rtmpStateLabel(status.rtmpState))
        InfoRow("ビットレート", "${status.bitrate / 1000}Mbps")
        if (status.avatarName.isNotEmpty()) {
            InfoRow("アバター", status.avatarName)
        }
        if (status.message.isNotEmpty()) {
            InfoRow("メッセージ", status.message)
        }
    }
}
