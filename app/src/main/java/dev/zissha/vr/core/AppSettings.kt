package dev.zissha.vr.core

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "zissha_settings")

data class Settings(
    val cameraLensFront: Boolean = true,
    /** -1 = auto (device sensor orientation), 0..3 = forced quarter-turn rotation. */
    val cameraRotationOverride: Int = -1,
    /** Invert the automatic front-camera mirror (device-specific display fix). */
    val cameraMirrorInverted: Boolean = false,
    val resolutionIndex: Int = 1, // 0=720p, 1=1080p
    val targetFps: Int = 30,
    val poseEnabled: Boolean = true,
    val faceEnabled: Boolean = true,
    val handEnabled: Boolean = true,
    val segmentationEnabled: Boolean = true,
    val lodAuto: Boolean = true,
    val forcedLod: Int = 1,
    val avatarUri: String = "",
    val avatarScale: Float = 1f,
    val privacyImageUri: String = "",
    val rtmpUrl: String = "",
    val streamKey: String = "",
    val audioEnabled: Boolean = true,
    val videoBitrateKbps: Int = 6000,
    val bleEnabled: Boolean = true,
)

/**
 * DataStore-backed settings (spec 2). The RTMP stream key is stored separately from the URL
 * and is never written to logs (spec 9).
 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val cameraLensFront = booleanPreferencesKey("camera_front")
        val cameraRotationOverride = intPreferencesKey("camera_rotation_override")
        val cameraMirrorInverted = booleanPreferencesKey("camera_mirror_inverted")
        val resolutionIndex = intPreferencesKey("resolution_index")
        val targetFps = intPreferencesKey("target_fps")
        val poseEnabled = booleanPreferencesKey("pose_enabled")
        val faceEnabled = booleanPreferencesKey("face_enabled")
        val handEnabled = booleanPreferencesKey("hand_enabled")
        val segmentationEnabled = booleanPreferencesKey("segmentation_enabled")
        val lodAuto = booleanPreferencesKey("lod_auto")
        val forcedLod = intPreferencesKey("forced_lod")
        val avatarUri = stringPreferencesKey("avatar_uri")
        val avatarScale = stringPreferencesKey("avatar_scale")
        val privacyImageUri = stringPreferencesKey("privacy_image_uri")
        val rtmpUrl = stringPreferencesKey("rtmp_url")
        val streamKey = stringPreferencesKey("rtmp_key")
        val audioEnabled = booleanPreferencesKey("audio_enabled")
        val videoBitrateKbps = intPreferencesKey("video_bitrate_kbps")
        val bleEnabled = booleanPreferencesKey("ble_enabled")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            cameraLensFront = p[Keys.cameraLensFront] ?: true,
            cameraRotationOverride = p[Keys.cameraRotationOverride] ?: -1,
            cameraMirrorInverted = p[Keys.cameraMirrorInverted] ?: false,
            resolutionIndex = p[Keys.resolutionIndex] ?: 1,
            targetFps = p[Keys.targetFps] ?: 30,
            poseEnabled = p[Keys.poseEnabled] ?: true,
            faceEnabled = p[Keys.faceEnabled] ?: true,
            handEnabled = p[Keys.handEnabled] ?: true,
            segmentationEnabled = p[Keys.segmentationEnabled] ?: true,
            lodAuto = p[Keys.lodAuto] ?: true,
            forcedLod = p[Keys.forcedLod] ?: 1,
            avatarUri = p[Keys.avatarUri] ?: "",
            avatarScale = p[Keys.avatarScale]?.toFloatOrNull() ?: 1f,
            privacyImageUri = p[Keys.privacyImageUri] ?: "",
            rtmpUrl = p[Keys.rtmpUrl] ?: "",
            streamKey = p[Keys.streamKey] ?: "",
            audioEnabled = p[Keys.audioEnabled] ?: true,
            videoBitrateKbps = p[Keys.videoBitrateKbps] ?: 6000,
            bleEnabled = p[Keys.bleEnabled] ?: true,
        )
    }

    suspend fun update(transform: (Settings) -> Settings) {
        context.dataStore.edit { p ->
            val current = Settings(
                cameraLensFront = p[Keys.cameraLensFront] ?: true,
                cameraRotationOverride = p[Keys.cameraRotationOverride] ?: -1,
                cameraMirrorInverted = p[Keys.cameraMirrorInverted] ?: false,
                resolutionIndex = p[Keys.resolutionIndex] ?: 1,
                targetFps = p[Keys.targetFps] ?: 30,
                poseEnabled = p[Keys.poseEnabled] ?: true,
                faceEnabled = p[Keys.faceEnabled] ?: true,
                handEnabled = p[Keys.handEnabled] ?: true,
                segmentationEnabled = p[Keys.segmentationEnabled] ?: true,
                lodAuto = p[Keys.lodAuto] ?: true,
                forcedLod = p[Keys.forcedLod] ?: 1,
                avatarUri = p[Keys.avatarUri] ?: "",
                avatarScale = p[Keys.avatarScale]?.toFloatOrNull() ?: 1f,
                privacyImageUri = p[Keys.privacyImageUri] ?: "",
                rtmpUrl = p[Keys.rtmpUrl] ?: "",
                streamKey = p[Keys.streamKey] ?: "",
                audioEnabled = p[Keys.audioEnabled] ?: true,
                videoBitrateKbps = p[Keys.videoBitrateKbps] ?: 6000,
                bleEnabled = p[Keys.bleEnabled] ?: true,
            )
            val next = transform(current)
            p[Keys.cameraLensFront] = next.cameraLensFront
            p[Keys.cameraRotationOverride] = next.cameraRotationOverride
            p[Keys.cameraMirrorInverted] = next.cameraMirrorInverted
            p[Keys.resolutionIndex] = next.resolutionIndex
            p[Keys.targetFps] = next.targetFps
            p[Keys.poseEnabled] = next.poseEnabled
            p[Keys.faceEnabled] = next.faceEnabled
            p[Keys.handEnabled] = next.handEnabled
            p[Keys.segmentationEnabled] = next.segmentationEnabled
            p[Keys.lodAuto] = next.lodAuto
            p[Keys.forcedLod] = next.forcedLod
            p[Keys.avatarUri] = next.avatarUri
            p[Keys.avatarScale] = next.avatarScale.toString()
            p[Keys.privacyImageUri] = next.privacyImageUri
            p[Keys.rtmpUrl] = next.rtmpUrl
            p[Keys.streamKey] = next.streamKey
            p[Keys.audioEnabled] = next.audioEnabled
            p[Keys.videoBitrateKbps] = next.videoBitrateKbps
            p[Keys.bleEnabled] = next.bleEnabled
        }
    }
}
