package dev.zissha.remote

import dev.zissha.protocol.OutputMode

fun connectionLabel(state: BleCentral.ConnectionState): String = when (state) {
    BleCentral.ConnectionState.IDLE -> "未接続"
    BleCentral.ConnectionState.SCANNING -> "検索中…"
    BleCentral.ConnectionState.CONNECTING -> "接続中…"
    BleCentral.ConnectionState.CONNECTED -> "接続済み(未認証)"
    BleCentral.ConnectionState.AUTHORIZED -> "認証済み"
    BleCentral.ConnectionState.FAILED -> "接続に失敗しました"
}

fun remoteModeLabel(mode: OutputMode): String = when (mode) {
    OutputMode.LIVE -> "通常配信(LIVE)"
    OutputMode.FREEZE -> "映像停止(FREEZE)"
    OutputMode.PRIVACY -> "プライバシー(PRIVACY)"
}

fun rtmpStateLabel(state: String): String = when (state) {
    "IDLE" -> "未接続"
    "CONNECTING" -> "接続中"
    "STREAMING" -> "配信中"
    "RECONNECTING" -> "再接続中"
    "FAILED" -> "接続失敗"
    "DISCONNECTED" -> "切断"
    else -> state
}
