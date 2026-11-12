package dev.zissha.vr.stream

import android.content.Context
import android.graphics.SurfaceTexture
import android.util.Log
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.sources.audio.MicrophoneSource
import com.pedro.encoder.input.sources.audio.NoAudioSource
import com.pedro.encoder.input.sources.video.NoVideoSource
import com.pedro.library.rtmp.RtmpStream
import dev.zissha.vr.core.AppLog
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class StreamState {
    IDLE, CONNECTING, STREAMING, RECONNECTING, FAILED, DISCONNECTED,
}

/**
 * RTMP session (spec 9) built on RootEncoder's RtmpStream with a NoVideoSource: the app renders
 * the composed frame into the stream's SurfaceTexture, so preview / recording / streaming share
 * exactly the same composited pixels (spec 6.3). Encoding is MediaCodec H.264 + AAC inside the
 * library; MP4 recording reuses the same encoder path.
 *
 * The stream key is never logged (spec 9).
 */
class RtmpSession(private val context: Context) : ConnectChecker {

    companion object {
        private const val TAG = "RtmpSession"
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 2000L
    }

    interface EncoderSurfaceListener {
        /** The SurfaceTexture that must receive the composited frames once the session is active. */
        fun onEncoderSurfaceTextureAvailable(surfaceTexture: SurfaceTexture?)
    }

    var encoderSurfaceListener: EncoderSurfaceListener? = null

    private val _state = MutableStateFlow(StreamState.IDLE)
    val state: StateFlow<StreamState> = _state

    private val _statsBitrate = MutableStateFlow(0L)
    val statsBitrate: StateFlow<Long> = _statsBitrate

    var stream: RtmpStream? = null
        private set

    private val autoRetry = AtomicBoolean(true)
    private var retryCount = 0
    private var retryJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    @Volatile var recording = false
        private set

    /**
     * Prepare and start streaming.
     * @param baseUrl e.g. rtmp://server/live  @param streamKey appended by the server path
     */
    @Synchronized
    fun startStream(
        baseUrl: String,
        streamKey: String,
        width: Int,
        height: Int,
        fps: Int,
        bitrateKbps: Int,
        audioEnabled: Boolean,
    ): Boolean {
        if (baseUrl.isBlank()) {
            AppLog.w("RTMP URL not configured")
            return false
        }
        release()
        val fullUrl = buildStreamUrl(baseUrl, streamKey)
        autoRetry.set(true)
        retryCount = 0
        return try {
            val newStream = RtmpStream(context, this, NoVideoSource(), if (audioEnabled) MicrophoneSource() else NoAudioSource())
            val prepared = newStream.prepareVideo(width, height, bitrateKbps * 1000, fps, 2, 0)
            if (!prepared) {
                AppLog.e("prepareVideo failed")
                _state.value = StreamState.FAILED
                return false
            }
            if (audioEnabled) {
                if (!newStream.prepareAudio(44100, true, 128 * 1000, false, false)) {
                    AppLog.w("prepareAudio failed; continuing video-only")
                }
            }
            stream = newStream
            encoderSurfaceListener?.onEncoderSurfaceTextureAvailable(newStream.surfaceTexture)
            _state.value = StreamState.CONNECTING
            newStream.startStream(fullUrl)
            AppLog.i("RTMP stream starting to ${sanitizeUrl(baseUrl)} (key withheld, ${width}x$height@$fps ${bitrateKbps}kbps audio=$audioEnabled)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "startStream failed", e)
            AppLog.e("RTMP start failed: ${e.message}")
            _state.value = StreamState.FAILED
            release()
            false
        }
    }

    /** Start an MP4 recording without streaming (same encoder pipeline, spec 8). */
    @Synchronized
    fun startRecording(
        path: String,
        width: Int,
        height: Int,
        fps: Int,
        bitrateKbps: Int,
        audioEnabled: Boolean,
        onStatus: (Boolean, String?) -> Unit,
    ): Boolean {
        val target = stream ?: run {
            // no active stream: create a record-only session
            if (!createSession(width, height, fps, bitrateKbps, audioEnabled)) return false
            stream!!
        }
        return try {
            encoderSurfaceListener?.onEncoderSurfaceTextureAvailable(target.surfaceTexture)
            target.startRecord(path) { status ->
                when (status) {
                    com.pedro.library.base.recording.RecordController.Status.RECORDING,
                    com.pedro.library.base.recording.RecordController.Status.RESUMED,
                    -> onStatus(true, null)
                    com.pedro.library.base.recording.RecordController.Status.STARTED -> Unit
                    com.pedro.library.base.recording.RecordController.Status.STOPPED ->
                        onStatus(false, null)
                    com.pedro.library.base.recording.RecordController.Status.PAUSED -> Unit
                }
            }
            recording = true
            AppLog.i("recording started: $path")
            true
        } catch (e: Exception) {
            Log.e(TAG, "startRecord failed", e)
            AppLog.e("recording start failed: ${e.message}")
            false
        }
    }

    @Synchronized
    fun stopRecording(): Boolean {
        val target = stream ?: return false
        return try {
            val ok = target.stopRecord()
            recording = false
            if (!target.isStreaming) {
                release()
            }
            AppLog.i("recording stopped (ok=$ok)")
            ok
        } catch (e: Exception) {
            Log.e(TAG, "stopRecord failed", e)
            false
        }
    }

    private fun createSession(
        width: Int,
        height: Int,
        fps: Int,
        bitrateKbps: Int,
        audioEnabled: Boolean,
    ): Boolean {
        return try {
            val newStream = RtmpStream(context, this, NoVideoSource(), if (audioEnabled) MicrophoneSource() else NoAudioSource())
            if (!newStream.prepareVideo(width, height, bitrateKbps * 1000, fps, 2, 0)) return false
            if (audioEnabled) {
                runCatching { newStream.prepareAudio(44100, true, 128 * 1000, false, false) }
            }
            stream = newStream
            true
        } catch (e: Exception) {
            Log.e(TAG, "record session create failed", e)
            false
        }
    }

    @Synchronized
    fun stopStream() {
        try {
            stream?.let { s ->
                s.stopStream()
                if (!s.isRecording) {
                    release()
                } else {
                    AppLog.i("stream stopped; recording continues")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "stopStream failed", e)
        }
        _state.value = StreamState.DISCONNECTED
    }

    /** Live bitrate change (spec 9). */
    fun setVideoBitrate(bitrateKbps: Int) {
        runCatching { stream?.setVideoBitrateOnFly(bitrateKbps * 1000) }
            .onFailure { Log.w(TAG, "bitrate change failed", it) }
    }

    @Synchronized
    private fun release() {
        retryJob?.cancel()
        retryJob = null
        encoderSurfaceListener?.onEncoderSurfaceTextureAvailable(null)
        try {
            stream?.release()
        } catch (e: Exception) {
            Log.w(TAG, "stream release failed", e)
        }
        stream = null
        recording = false
    }

    private fun buildStreamUrl(baseUrl: String, streamKey: String): String {
        val base = baseUrl.trimEnd('/')
        return if (streamKey.isBlank()) base else "$base/$streamKey"
    }

    private fun sanitizeUrl(url: String): String = url.substringBefore("://").let { prefix ->
        // keep scheme + host, drop any credentials
        val rest = url.substringAfter("://")
        val host = rest.substringBefore('/')
        "$prefix://$host/..."
    }

    // ------------------------------------------------------------- ConnectChecker

    override fun onConnectionStarted(url: String) {
        _state.value = StreamState.CONNECTING
    }

    override fun onConnectionSuccess() {
        retryCount = 0
        _state.value = StreamState.STREAMING
        AppLog.i("RTMP connected")
    }

    override fun onConnectionFailed(reason: String) {
        AppLog.w("RTMP connection failed: $reason")
        if (autoRetry.get() && retryCount < MAX_RETRIES) {
            retryCount++
            _state.value = StreamState.RECONNECTING
            AppLog.i("RTMP retry $retryCount/$MAX_RETRIES in ${RETRY_DELAY_MS}ms")
            retryJob = scope.launch {
                delay(RETRY_DELAY_MS)
                try {
                    stream?.streamClient?.reTry(RETRY_DELAY_MS, reason)
                } catch (e: Exception) {
                    Log.w(TAG, "reTry failed", e)
                }
            }
        } else {
            _state.value = StreamState.FAILED
        }
    }

    override fun onDisconnect() {
        _state.value = StreamState.DISCONNECTED
        AppLog.i("RTMP disconnected")
    }

    override fun onAuthError() {
        _state.value = StreamState.FAILED
        AppLog.e("RTMP auth error")
    }

    override fun onAuthSuccess() {
        AppLog.i("RTMP auth ok")
    }

    override fun onNewBitrate(bitrate: Long) {
        _statsBitrate.value = bitrate
    }
}
