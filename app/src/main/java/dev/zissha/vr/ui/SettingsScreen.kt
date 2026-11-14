package dev.zissha.vr.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zissha.ui.components.ChoiceChips
import dev.zissha.ui.components.InfoRow
import dev.zissha.ui.components.SectionCard
import dev.zissha.ui.components.ToggleRow
import dev.zissha.vr.BuildConfig
import dev.zissha.vr.core.Engine
import dev.zissha.vr.core.Settings
import kotlinx.coroutines.launch

/**
 * Settings screen (spec 12): camera, tracking, avatar management, privacy image,
 * recording / RTMP and Bluetooth. Grouped into cards so each topic is scannable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(engine: Engine, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val settings by engine.settingsFlow().collectAsStateWithLifecycle(initialValue = Settings())
    val ui by engine.uiState.collectAsStateWithLifecycle()
    var avatars by remember { mutableStateOf(engine.avatarLoaderInstance.listAvatars()) }
    var pendingDeleteAvatar by remember { mutableStateOf<String?>(null) }
    var showStreamKey by remember { mutableStateOf(false) }

    fun update(transform: (Settings) -> Settings) {
        scope.launch { engine.settingsRepository.update(transform) }
    }

    fun toast(message: String) {
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            scope.launch {
                val name = engine.avatarLoaderInstance.importFromUri(uri, uri.lastPathSegment)
                if (name != null) {
                    update { it.copy(avatarUri = name) }
                    avatars = engine.avatarLoaderInstance.listAvatars()
                    toast("アバターを取り込みました: $name")
                } else {
                    toast("取り込みに失敗しました")
                }
            }
        }
    }

    val privacyPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            update { it.copy(privacyImageUri = uri.toString()) }
            toast("プライバシー画像を設定しました")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("設定") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionCard(title = "カメラ") {
                Text("レンズ", style = MaterialTheme.typography.bodyMedium)
                ChoiceChips(
                    options = listOf(true to "フロント", false to "バック"),
                    selected = settings.cameraLensFront,
                    onSelect = { v -> update { it.copy(cameraLensFront = v) } },
                )
                Text("解像度", style = MaterialTheme.typography.bodyMedium)
                ChoiceChips(
                    options = listOf(0 to "720p", 1 to "1080p"),
                    selected = settings.resolutionIndex,
                    onSelect = { v -> update { it.copy(resolutionIndex = v) } },
                )
                Text("フレームレート", style = MaterialTheme.typography.bodyMedium)
                ChoiceChips(
                    options = listOf(24 to "24fps", 30 to "30fps", 60 to "60fps"),
                    selected = settings.targetFps,
                    onSelect = { v -> update { it.copy(targetFps = v) } },
                )
                Text("向き(回転)", style = MaterialTheme.typography.bodyMedium)
                ChoiceChips(
                    options = listOf(
                        -1 to "自動",
                        0 to "0°",
                        1 to "90°",
                        2 to "180°",
                        3 to "270°",
                    ),
                    selected = settings.cameraRotationOverride,
                    onSelect = { v -> update { it.copy(cameraRotationOverride = v) } },
                )
                ToggleRow(
                    label = "ミラーを反転",
                    description = "自動の左右反転を入れ替えます(映像の向きがおかしい場合)",
                    checked = settings.cameraMirrorInverted,
                    onCheckedChange = { v -> update { it.copy(cameraMirrorInverted = v) } },
                )
            }

            SectionCard(title = "トラッキング") {
                ToggleRow(
                    label = "人物の形で切り抜く",
                    description = "OFF: アバター全体を人物の位置・大きさに合わせて重ねます。ON: 人物のシルエットでアバターを切り抜き、人物を完全に隠します。",
                    checked = settings.segmentationEnabled,
                    onCheckedChange = { v -> update { it.copy(segmentationEnabled = v) } },
                )
                Text("LOD(負荷レベル)", style = MaterialTheme.typography.bodyMedium)
                ChoiceChips(
                    options = listOf(
                        0 to "自動",
                        1 to "L0",
                        2 to "L1",
                        3 to "L2",
                    ),
                    selected = if (settings.lodAuto) 0 else settings.forcedLod + 1,
                    onSelect = { v ->
                        if (v == 0) {
                            update { it.copy(lodAuto = true) }
                        } else {
                            update { it.copy(lodAuto = false, forcedLod = v - 1) }
                        }
                    },
                )
                InfoRow("現在のLOD", "L${ui.stats.lod}")
                InfoRow("負荷制御", "L${ui.governorLevel} (${ui.thermalStatusText})")
                ToggleRow(
                    label = "骨格を重ねて表示",
                    description = "MediaPipeが検出した骨格を映像に合成します(位置合わせの確認用)",
                    checked = settings.skeletonOverlay,
                    onCheckedChange = { v -> update { it.copy(skeletonOverlay = v) } },
                )
            }

            SectionCard(title = "アバター") {
                Button(
                    onClick = { avatarPicker.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("VRM / glb / gltf を読み込む") }
                if (ui.avatarLoading) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.padding(2.dp))
                        Text("読み込み中…", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (avatars.isEmpty()) {
                    Text(
                        "アバターが登録されていません。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    avatars.forEach { avatar ->
                        val active = avatar.name == settings.avatarUri || avatar.name == ui.avatarName
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = avatar.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = if (active) "使用中" else "${avatar.sizeBytes / 1024} KB",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (active) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                            OutlinedButton(
                                onClick = { update { it.copy(avatarUri = avatar.name) } },
                                enabled = !active,
                            ) { Text("使用") }
                            IconButton(onClick = { pendingDeleteAvatar = avatar.name }) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = "削除",
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
                AvatarScaleRow(
                    scale = settings.avatarScale,
                    onCommit = { v -> update { it.copy(avatarScale = v) } },
                )
            }

            SectionCard(title = "プライバシー画像") {
                Text(
                    text = "PRIVACYモードで表示・配信される静止画像です。未設定の場合は既定画像を使用します。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = { privacyPicker.launch(arrayOf("image/*")) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("画像を選択") }
                if (settings.privacyImageUri.isNotEmpty()) {
                    InfoRow("設定", "カスタム画像")
                    OutlinedButton(
                        onClick = {
                            update { it.copy(privacyImageUri = "") }
                            toast("既定画像に戻しました")
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("既定画像に戻す") }
                } else {
                    InfoRow("設定", "既定画像")
                }
            }

            SectionCard(title = "安全") {
                ToggleRow(
                    label = "自動で復帰",
                    description = "自動で停止/プライバシーになった後、トラッキングが安定したら自動でLIVEへ戻します",
                    checked = settings.autoRecover,
                    onCheckedChange = { v -> update { it.copy(autoRecover = v) } },
                )
            }

            SectionCard(title = "録画・配信") {
                ToggleRow(
                    label = "マイク音声を含める",
                    checked = settings.audioEnabled,
                    onCheckedChange = { v -> update { it.copy(audioEnabled = v) } },
                )
                Text("ビットレート", style = MaterialTheme.typography.bodyMedium)
                ChoiceChips(
                    options = listOf(2000, 4000, 6000, 8000).map { it to "${it / 1000}Mbps" },
                    selected = settings.videoBitrateKbps,
                    onSelect = { v -> update { it.copy(videoBitrateKbps = v) } },
                )
                RtmpFields(
                    initialUrl = settings.rtmpUrl,
                    initialKey = settings.streamKey,
                    showKey = showStreamKey,
                    onToggleShowKey = { showStreamKey = !showStreamKey },
                    onSave = { url, key ->
                        update { it.copy(rtmpUrl = url, streamKey = key) }
                        toast("RTMP設定を保存しました")
                    },
                )
            }

            SectionCard(
                title = "Bluetoothリモコン",
                subtitle = "別端末の「ZisshaVR Remote」から操作できます。",
            ) {
                ToggleRow(
                    label = "BLEペリフェラルを有効化",
                    description = if (settings.bleEnabled && ui.bleRunning) "稼働中" else "停止中",
                    checked = settings.bleEnabled,
                    onCheckedChange = { v -> update { it.copy(bleEnabled = v) } },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("ペアリングコード", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = ui.bleToken.ifEmpty { "(BLE無効)" },
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    if (ui.bleToken.isNotEmpty()) {
                        IconButton(onClick = {
                            val clipboard = context.getSystemService(ClipboardManager::class.java)
                            clipboard?.setPrimaryClip(
                                ClipData.newPlainText("ZisshaVR pairing code", ui.bleToken),
                            )
                            toast("コードをコピーしました")
                        }) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = "コピー")
                        }
                    }
                }
                InfoRow("接続状態", if (ui.bleConnected) "接続済み" else "待機中")
            }

            Text(
                text = "ZisshaVR ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 24.dp),
            )
        }
    }

    pendingDeleteAvatar?.let { name ->
        AlertDialog(
            onDismissRequest = { pendingDeleteAvatar = null },
            title = { Text("アバターを削除しますか？") },
            text = { Text("$name\nこの操作は取り消せません。") },
            confirmButton = {
                TextButton(onClick = {
                    engine.avatarLoaderInstance.deleteAvatar(name)
                    avatars = engine.avatarLoaderInstance.listAvatars()
                    if (settings.avatarUri == name) {
                        update { it.copy(avatarUri = "") }
                    }
                    pendingDeleteAvatar = null
                    toast("削除しました")
                }) {
                    Text("削除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteAvatar = null }) { Text("キャンセル") }
            },
        )
    }
}

@Composable
private fun AvatarScaleRow(scale: Float, onCommit: (Float) -> Unit) {
    var local by remember(scale) { mutableStateOf(scale) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("サイズ", style = MaterialTheme.typography.bodyMedium)
            Text(
                "x%.2f".format(local),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = local,
            onValueChange = { local = it },
            onValueChangeFinished = { onCommit(local) },
            valueRange = 0.5f..1.5f,
        )
    }
}

@Composable
private fun RtmpFields(
    initialUrl: String,
    initialKey: String,
    showKey: Boolean,
    onToggleShowKey: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var url by remember(initialUrl) { mutableStateOf(initialUrl) }
    var key by remember(initialKey) { mutableStateOf(initialKey) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("RTMP URL") },
            placeholder = { Text("rtmp://サーバー/live") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            label = { Text("ストリームキー") },
            supportingText = { Text("URLとは分離して保存され、ログには出力されません") },
            trailingIcon = {
                IconButton(onClick = onToggleShowKey) {
                    Icon(
                        if (showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (showKey) "キーを隠す" else "キーを表示",
                    )
                }
            },
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Button(
            onClick = { onSave(url.trim(), key.trim()) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("RTMP設定を保存") }
    }
}
