package dev.zissha.remote

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.zissha.protocol.OutputMode

class MainActivity : ComponentActivity() {

    private val central by lazy { BleCentral(this) }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted.values.all { it }) central.startScan()
    }

    private fun requestScan() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            central.startScan()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    RemoteScreen(central, onScan = { requestScan() })
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        central.disconnect()
    }
}

@Composable
fun RemoteScreen(central: BleCentral, onScan: () -> Unit) {
    val state by central.state.collectAsState()
    val status by central.status.collectAsState()
    var token by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("ZisshaVR リモコン", style = MaterialTheme.typography.titleLarge)
        Text(
            when (state) {
                BleCentral.ConnectionState.IDLE -> "未接続"
                BleCentral.ConnectionState.SCANNING -> "検索中…"
                BleCentral.ConnectionState.CONNECTING -> "接続中…"
                BleCentral.ConnectionState.CONNECTED -> "接続済み(未認証)"
                BleCentral.ConnectionState.AUTHORIZED -> "認証済み"
                BleCentral.ConnectionState.FAILED -> "失敗 - Bluetooth/権限を確認してください"
            },
            style = MaterialTheme.typography.bodyMedium,
        )

        if (state == BleCentral.ConnectionState.IDLE || state == BleCentral.ConnectionState.FAILED) {
            Button(onClick = onScan) { Text("ZisshaVRを検索") }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("ペアリングコード(メイン端末に表示)", style = MaterialTheme.typography.labelMedium)
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it.filter { c -> c.isDigit() }.take(6) },
                    label = { Text("6桁のコード") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { central.token = token },
                    enabled = token.length == 6 && state == BleCentral.ConnectionState.CONNECTED,
                ) { Text("認証する") }
            }
        }

        // Mode buttons: FREEZE and PRIVACY are large and separated (spec 10.2)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { central.setMode(OutputMode.LIVE) },
                enabled = state == BleCentral.ConnectionState.AUTHORIZED,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E88E5)),
                modifier = Modifier.weight(1f).height(64.dp),
            ) { Text("通常配信") }
            Button(
                onClick = { central.setMode(OutputMode.FREEZE) },
                enabled = state == BleCentral.ConnectionState.AUTHORIZED,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFA000)),
                modifier = Modifier.weight(1f).height(64.dp),
            ) { Text("映像停止") }
        }
        Button(
            onClick = { central.setMode(OutputMode.PRIVACY) },
            enabled = state == BleCentral.ConnectionState.AUTHORIZED,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8E24AA)),
            modifier = Modifier.fillMaxWidth().height(80.dp),
        ) { Text("プライバシー", style = MaterialTheme.typography.titleLarge) }

        Spacer(Modifier.height(4.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val recording = status?.recording == true
            Button(
                onClick = { if (recording) central.recordStop() else central.recordStart() },
                enabled = state == BleCentral.ConnectionState.AUTHORIZED,
                modifier = Modifier.weight(1f),
            ) { Text(if (recording) "録画停止" else "録画開始") }
            val streaming = status?.streaming == true
            Button(
                onClick = { if (streaming) central.streamStop() else central.streamStart() },
                enabled = state == BleCentral.ConnectionState.AUTHORIZED,
                modifier = Modifier.weight(1f),
            ) { Text(if (streaming) "配信停止" else "配信開始") }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("状態", style = MaterialTheme.typography.labelMedium)
                val s = status
                if (s == null) {
                    Text("(状態を受信していません)", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("モード: ${s.mode}", style = MaterialTheme.typography.bodySmall)
                    Text("録画: ${s.recording} / 配信: ${s.streaming}", style = MaterialTheme.typography.bodySmall)
                    Text("トラッキング: ${if (s.trackingHealthy) "正常" else "検出なし"} / LOD ${s.lod}", style = MaterialTheme.typography.bodySmall)
                    Text("FPS: %.1f  推論: %.1fms".format(s.fps, s.inferenceMs), style = MaterialTheme.typography.bodySmall)
                    Text("配信: ${s.rtmpState} @ ${s.bitrate / 1000}kbps", style = MaterialTheme.typography.bodySmall)
                    Text("アバター: ${s.avatarName}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        OutlinedButton(onClick = { central.requestStatus() }, enabled = state == BleCentral.ConnectionState.AUTHORIZED) {
            Text("状態を更新")
        }
    }
}
