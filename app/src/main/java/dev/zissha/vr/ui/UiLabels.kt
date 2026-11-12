package dev.zissha.vr.ui

import dev.zissha.protocol.OutputMode
import dev.zissha.vr.stream.StreamState

/** Short mode label for status pills. */
fun modeShortLabel(mode: OutputMode): String = when (mode) {
    OutputMode.LIVE -> "LIVE"
    OutputMode.FREEZE -> "FREEZE"
    OutputMode.PRIVACY -> "PRIVACY"
}

/** Full mode label for explanatory text. */
fun modeLabel(mode: OutputMode): String = when (mode) {
    OutputMode.LIVE -> "通常配信(LIVE)"
    OutputMode.FREEZE -> "映像停止(FREEZE)"
    OutputMode.PRIVACY -> "プライバシー(PRIVACY)"
}

fun streamStateLabel(state: StreamState): String = when (state) {
    StreamState.IDLE -> "未接続"
    StreamState.CONNECTING -> "接続中"
    StreamState.STREAMING -> "配信中"
    StreamState.RECONNECTING -> "再接続中"
    StreamState.FAILED -> "接続失敗"
    StreamState.DISCONNECTED -> "切断"
}

fun triggerLabel(reason: String): String = when {
    reason.startsWith("internal_error") -> "内部エラー"
    else -> when (reason) {
        "pose_timeout" -> "トラッキング中断"
        "landmark_warp" -> "ランドマーク異常"
        "low_confidence" -> "信頼度低下"
        "camera_stall" -> "カメラ停止"
        "inference_timeout" -> "推論タイムアウト"
        "segmentation_anomaly" -> "セグメンテーション異常"
        else -> reason
    }
}
