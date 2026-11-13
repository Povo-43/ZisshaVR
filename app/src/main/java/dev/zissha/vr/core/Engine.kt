package dev.zissha.vr.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import dev.zissha.protocol.OutputMode
import dev.zissha.protocol.StatusPayload
import dev.zissha.vr.avatar.AvatarLoader
import dev.zissha.vr.avatar.render.AvatarController
import dev.zissha.vr.avatar.render.AvatarRenderer
import dev.zissha.vr.ble.PeripheralManager
import dev.zissha.vr.camera.BoundCamera
import dev.zissha.vr.camera.CameraController
import dev.zissha.vr.gl.GLRenderer
import dev.zissha.vr.record.RecordingManager
import dev.zissha.vr.stream.RtmpSession
import dev.zissha.vr.stream.StreamState
import dev.zissha.vr.tracking.TrackingEngine
import dev.zissha.vr.tracking.TrackingStats
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Aggregated UI state (spec 12 main screen). */
data class EngineUiState(
    val mode: OutputMode = OutputMode.LIVE,
    val canRecover: Boolean = false,
    val gateTrigger: String? = null,
    val trackingHealthy: Boolean = false,
    val stats: TrackingStats = TrackingStats(),
    /** composite output FPS measured on the render thread (preview / encoder health). */
    val renderFps: Float = 0f,
    val streamState: StreamState = StreamState.IDLE,
    val recording: Boolean = false,
    val thermalStatusText: String = "NONE",
    val governorLevel: Int = 0,
    val avatarName: String = "",
    val avatarLoading: Boolean = false,
    /** Mapped humanoid bone count (0 = VRM bone mapping failed). */
    val avatarBones: Int = 0,
    /** Whether the latest frame applied a tracking pose to the avatar. */
    val poseApplied: Boolean = false,
    val bleRunning: Boolean = false,
    val bleToken: String = "",
    val bleConnected: Boolean = false,
    val lastError: String? = null,
    val previewSizeError: Boolean = false,
)

/**
 * Central orchestrator: wires camera, tracking, GL pipeline, safety gate, recording, streaming,
 * BLE and settings together. One instance per process (spec: UI and video processing are fully
 * separated; the engine lives outside the Activity lifecycle for the render threads).
 */
class Engine private constructor() {

    companion object {
        private const val TAG = "Engine"
        private const val WATCHDOG_INTERVAL_MS = 1000L
        private const val CAMERA_STALL_MS = 1200L

        @Volatile private var instance: Engine? = null

        fun get(): Engine = instance ?: synchronized(this) {
            instance ?: Engine().also { instance = it }
        }
    }

    lateinit var settingsRepository: SettingsRepository
        private set

    /** Settings flow safe to call before [start] (used by UI). */
    fun settingsFlow(): kotlinx.coroutines.flow.Flow<Settings> =
        if (::settingsRepository.isInitialized) {
            settingsRepository.settings
        } else {
            kotlinx.coroutines.flow.flowOf(Settings())
        }

    val gate = SafetyGate()
    private lateinit var governor: PerformanceGovernor
    private lateinit var trackingEngine: TrackingEngine
    private lateinit var glRenderer: GLRenderer
    private lateinit var cameraController: CameraController
    private lateinit var rtmpSession: RtmpSession
    private lateinit var recordingManager: RecordingManager
    private lateinit var avatarLoader: AvatarLoader
    private var peripheralManager: PeripheralManager? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lifecycleScope: kotlinx.coroutines.CoroutineScope? = null
    private var started = false
    private var settingsJob: Job? = null
    private var currentSettings: Settings = Settings()
    private var avatarJob: Job? = null
    private var loadedAvatarFile: File? = null

    private val _uiState = MutableStateFlow(EngineUiState())
    val uiState: StateFlow<EngineUiState> = _uiState

    private lateinit var appContext: Context

    private var lastFrameUptimeMs = 0L
    private var renderFps = 0f
    private var frameCounter = 0L
    private var fpsWindowStart = 0L
    private var cameraBound = false

    private val watchdog = object : Runnable {
        override fun run() {
            if (!started) return
            val now = SystemClock.uptimeMillis()
            if (now - lastFrameUptimeMs > CAMERA_STALL_MS && gate.mode.value == OutputMode.LIVE) {
                gate.onCameraStall()
            }
            peripheralManager?.notifyStatus(buildStatusPayload())
            updateUiState()
            mainHandler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    fun start(context: Context, lifecycleOwner: LifecycleOwner) {
        if (started) return
        started = true
        appContext = context.applicationContext
        AppLog.init(context)

        settingsRepository = SettingsRepository(context)
        governor = PerformanceGovernor(context)
        trackingEngine = TrackingEngine(context)
        avatarLoader = AvatarLoader(context)
        recordingManager = RecordingManager(context)
        rtmpSession = RtmpSession(context)
        val avatarController = AvatarController(AvatarRenderer())
        glRenderer = GLRenderer(trackingEngine, avatarController)
        cameraController = CameraController(context, lifecycleOwner)
        lifecycleScope = lifecycleOwner.lifecycleScope

        governor.listener = object : PerformanceGovernor.Listener {
            override fun onGovernorLevelChanged(level: Int) {
                trackingEngine.thermalLevel = level
                glRenderer.minFrameIntervalMs = when {
                    level >= 4 -> 66L
                    level >= 3 -> 40L
                    else -> 0L
                }
            }
        }

        glRenderer.callbacks = object : GLRenderer.Callbacks {
            override fun onFrameRendered(tsMs: Long, renderMs: Float) {
                lastFrameUptimeMs = SystemClock.uptimeMillis()
                frameCounter++
                val now = SystemClock.uptimeMillis()
                if (fpsWindowStart == 0L) fpsWindowStart = now
                if (now - fpsWindowStart >= 1000) {
                    renderFps = frameCounter / ((now - fpsWindowStart) / 1000f)
                    frameCounter = 0
                    fpsWindowStart = now
                    updateUiState()
                }
                val health = trackingEngine.health.value
                val ts = health?.tsMs ?: tsMs
                gate.onFrame(ts, health?.healthy ?: false, health?.reason, gate.safeFrameAvailable)
            }

            override fun onRenderError(what: String, e: Exception?) {
                Log.e(TAG, "render error: $what", e)
                gate.onInternalError(what)
            }

            override fun onFirstFrame() {
                updateUiState()
            }
        }

        cameraController.onBound = { bound -> onCameraBound(bound) }

        glRenderer.onCameraSurfaceCreated = { st ->
            cameraController.attachSurfaceTexture(st)
            lifecycleScope?.launch(Dispatchers.Main) {
                restartCamera()
            }
        }

        rtmpSession.encoderSurfaceListener = object : RtmpSession.EncoderSurfaceListener {
            override fun onEncoderSurfaceTextureAvailable(surfaceTexture: android.graphics.SurfaceTexture?) {
                glRenderer.setEncoderSurfaceTexture(surfaceTexture)
            }
        }

        // tracking healthy flag -> render thread gate selection
        lifecycleScope?.launch {
            trackingEngine.health.collect { health ->
                glRenderer.trackingHealthy = health?.healthy ?: false
            }
        }
        lifecycleScope?.launch {
            trackingEngine.stats.collect { stats ->
                _uiState.value = _uiState.value.copy(stats = stats)
            }
        }
        lifecycleScope?.launch {
            gate.mode.collect { mode ->
                // Drive the GL output selection (composite / frozen frame / privacy image, spec 7).
                glRenderer.gateMode = mode
                _uiState.value = _uiState.value.copy(mode = mode)
            }
        }
        lifecycleScope?.launch {
            gate.canRecover.collect { canRecover ->
                _uiState.value = _uiState.value.copy(canRecover = canRecover)
                // Optional auto-recovery: only for states entered *automatically* (trigger != null),
                // never for a mode the user chose explicitly, and only once tracking is verified.
                if (canRecover && currentSettings.autoRecover && gate.trigger.value != null) {
                    setMode(OutputMode.LIVE)
                }
            }
        }
        lifecycleScope?.launch {
            gate.trigger.collect { trigger ->
                _uiState.value = _uiState.value.copy(gateTrigger = trigger)
            }
        }
        lifecycleScope?.launch {
            rtmpSession.state.collect { state ->
                _uiState.value = _uiState.value.copy(streamState = state)
            }
        }

        // settings
        settingsJob = lifecycleOwner.lifecycleScope.launch {
            settingsRepository.settings.collect { settings ->
                val prev = currentSettings
                currentSettings = settings
                applySettings(settings, prev)
            }
        }

        // BLE peripheral
        setupPeripheral(context)

        trackingEngine.start()
        glRenderer.start()
        governor.start()
        loadPrivacyImage("")
        mainHandler.postDelayed(watchdog, WATCHDOG_INTERVAL_MS)
        AppLog.i("engine started")
    }

    fun stop() {
        if (!started) return
        started = false
        mainHandler.removeCallbacks(watchdog)
        settingsJob?.cancel()
        avatarJob?.cancel()
        peripheralManager?.stop()
        rtmpSession.stopRecording()
        rtmpSession.stopStream()
        cameraController.stop()
        glRenderer.stop()
        trackingEngine.stop()
        governor.stop()
        AppLog.i("engine stopped")
    }

    // ------------------------------------------------------------------ camera wiring

    private var lastBound: BoundCamera? = null

    private fun onCameraBound(bound: BoundCamera) {
        lastBound = bound
        // output FBO is portrait: swap if the camera buffer is landscape
        val outW: Int
        val outH: Int
        if (bound.bufferWidth > bound.bufferHeight) {
            outW = bound.bufferHeight
            outH = bound.bufferWidth
        } else {
            outW = bound.bufferWidth
            outH = bound.bufferHeight
        }
        glRenderer.setOutputSize(outW, outH)
        applyCameraTransform()
        cameraBound = true
        _uiState.value = _uiState.value.copy(previewSizeError = false)
        AppLog.i(
            "camera bound: buffer=${bound.bufferWidth}x${bound.bufferHeight} output=${outW}x$outH " +
                "sensor=${bound.sensorOrientation} front=${bound.isFront}",
        )
    }

    /**
     * Apply the display rotation / mirror for the bound camera, honouring the device-override
     * settings (spec: sensorOrientation differs per device and could not be fixed blindly).
     */
    private fun applyCameraTransform() {
        val bound = lastBound ?: return
        val autoTurns = (bound.sensorOrientation / 90) % 4
        val turns = currentSettings.cameraRotationOverride.takeIf { it in 0..3 } ?: autoTurns
        glRenderer.setCameraTransform(turns, bound.isFront != currentSettings.cameraMirrorInverted)
    }

    private suspend fun restartCamera() {
        // CAMERA permission must be granted before binding (SecurityException otherwise)
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                appContext, android.Manifest.permission.CAMERA,
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            AppLog.w("CAMERA permission not granted; camera start deferred")
            return
        }
        try {
            val res = if (currentSettings.resolutionIndex == 0) {
                1280 to 720
            } else {
                1920 to 1080
            }
            cameraController.start(
                front = currentSettings.cameraLensFront,
                requestWidth = res.first,
                requestHeight = res.second,
                fps = currentSettings.targetFps,
            )
        } catch (e: Exception) {
            Log.e(TAG, "camera start failed", e)
            AppLog.e("camera start failed: ${e.message}")
            _uiState.value = _uiState.value.copy(previewSizeError = true)
        }
    }

    // ------------------------------------------------------------------ settings

    private fun applySettings(settings: Settings, prev: Settings) {
        if (settings.cameraLensFront != prev.cameraLensFront ||
            settings.resolutionIndex != prev.resolutionIndex ||
            settings.targetFps != prev.targetFps
        ) {
            lifecycleScope?.launch(Dispatchers.Main) { restartCamera() }
        }
        if (settings.cameraRotationOverride != prev.cameraRotationOverride ||
            settings.cameraMirrorInverted != prev.cameraMirrorInverted
        ) {
            applyCameraTransform()
        }
        trackingEngine.segmentationEnabled = settings.segmentationEnabled
        glRenderer.segmentationEnabled = settings.segmentationEnabled
        glRenderer.skeletonOverlay = settings.skeletonOverlay
        if (settings.bleEnabled != prev.bleEnabled) {
            if (settings.bleEnabled) {
                peripheralManager?.start()
            } else {
                peripheralManager?.stop()
            }
        }
        if (settings.avatarUri != prev.avatarUri) {
            loadAvatarFromUri(settings.avatarUri)
        }
        if (settings.privacyImageUri != prev.privacyImageUri) {
            loadPrivacyImage(settings.privacyImageUri)
        }
        if ((settings.avatarScale - prev.avatarScale).let { it > 0.001f || it < -0.001f }) {
            setAvatarScale(settings.avatarScale)
        }
    }

    // ------------------------------------------------------------------ avatar

    private fun loadAvatarFromUri(uriString: String) {
        if (uriString.isBlank()) return
        avatarJob?.cancel()
        _uiState.value = _uiState.value.copy(avatarLoading = true)
        avatarJob = lifecycleScope?.launch(Dispatchers.Default) {
            try {
                val existing = avatarLoader.listAvatars().firstOrNull { it.name == uriString }
                val file = existing?.file ?: run {
                    val imported = avatarLoader.importFromUri(Uri.parse(uriString), null)
                    imported?.let { avatarLoader.avatarDir().resolve(it) }
                } ?: File(avatarLoader.avatarDir(), uriString).takeIf { it.exists() }
                if (file == null) {
                    AppLog.w("avatar source not found: $uriString")
                    _uiState.value = _uiState.value.copy(avatarLoading = false)
                    return@launch
                }
                val doc = avatarLoader.parse(file)
                loadedAvatarFile = file
                withContext(Dispatchers.Main) {
                    glRenderer.reloadAvatar(doc)
                    _uiState.value = _uiState.value.copy(avatarLoading = false, avatarName = file.name)
                }
            } catch (e: Exception) {
                Log.e(TAG, "avatar load failed", e)
                AppLog.e("avatar load failed: ${e.message}")
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(avatarLoading = false, lastError = "avatar load failed")
                }
            }
        }
    }

    private fun loadPrivacyImage(uriString: String) {
        val context = appContext
        lifecycleScope?.launch(Dispatchers.Default) {
            val bitmap: Bitmap? = if (uriString.isBlank()) {
                null
            } else {
                try {
                    val uri = Uri.parse(uriString)
                    context.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "privacy image load failed", e)
                    null
                }
            }
            val scaled = scaleForPrivacy(bitmap)
            withContext(Dispatchers.Main) { glRenderer.setPrivacyImage(scaled) }
        }
    }

    private fun scaleForPrivacy(bitmap: Bitmap?): Bitmap? {
        bitmap ?: return defaultPrivacyBitmap()
        val maxDim = 1920
        val scale = minOf(1f, maxDim.toFloat() / maxOf(bitmap.width, bitmap.height))
        return if (scale < 1f) {
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            bitmap
        }
    }

    /** Default PRIVACY image (spec 7.1): a generated still image, no camera content. */
    private fun defaultPrivacyBitmap(): Bitmap {
        val bmp = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.parseColor("#101319"))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#7C4DFF")
            textAlign = Paint.Align.CENTER
            textSize = 110f
            isFakeBoldText = true
        }
        canvas.drawText("プライバシー", 540f, 900f, paint)
        paint.textSize = 44f
        paint.color = Color.parseColor("#8A93A5")
        canvas.drawText("ZisshaVR プライバシーモード / カメラ映像は非表示", 540f, 1000f, paint)
        return bmp
    }

    // ------------------------------------------------------------------ public controls

    /** Clears a one-shot error message after the UI has surfaced it (snackbar). */
    fun clearError() {
        if (_uiState.value.lastError != null) {
            _uiState.value = _uiState.value.copy(lastError = null)
        }
    }

    /** User-initiated camera retry after a bind failure (previewSizeError). */
    fun retryCamera() {
        if (!started) return
        _uiState.value = _uiState.value.copy(previewSizeError = false)
        lifecycleScope?.launch(Dispatchers.Main) { restartCamera() }
    }

    fun setMode(mode: OutputMode) {
        gate.requestMode(mode)
        if (mode == OutputMode.PRIVACY) {
            loadPrivacyImage(currentSettings.privacyImageUri)
        }
        updateUiState()
    }

    fun startRecording(): Boolean {
        if (_uiState.value.recording) return true
        val settings = currentSettings
        val (w, h) = if (settings.resolutionIndex == 0) 720 to 1280 else 1080 to 1920
        val file = recordingManager.newRecordingFile()
        val ok = rtmpSession.startRecording(
            path = file.absolutePath,
            width = w,
            height = h,
            fps = settings.targetFps,
            bitrateKbps = settings.videoBitrateKbps,
            audioEnabled = settings.audioEnabled,
        ) { recordingActive, error ->
            if (!recordingActive && error == null) {
                // finalized
                recordingManager.registerInMediaStore(file)
            }
            _uiState.value = _uiState.value.copy(recording = recordingActive, lastError = error)
        }
        _uiState.value = _uiState.value.copy(
            recording = ok || _uiState.value.recording,
            lastError = if (ok) null else "録画を開始できませんでした(保存先とコーデック設定を確認してください)",
        )
        return ok
    }

    fun stopRecording(): Boolean {
        val ok = rtmpSession.stopRecording()
        _uiState.value = _uiState.value.copy(recording = !ok)
        return ok
    }

    fun startStreaming(): Boolean {
        val settings = currentSettings
        val (w, h) = if (settings.resolutionIndex == 0) 720 to 1280 else 1080 to 1920
        val ok = rtmpSession.startStream(
            baseUrl = settings.rtmpUrl,
            streamKey = settings.streamKey,
            width = w,
            height = h,
            fps = settings.targetFps,
            bitrateKbps = settings.videoBitrateKbps,
            audioEnabled = settings.audioEnabled,
        )
        if (ok) {
            _uiState.value = _uiState.value.copy(streamState = StreamState.CONNECTING, lastError = null)
        } else {
            _uiState.value = _uiState.value.copy(
                lastError = if (settings.rtmpUrl.isBlank()) {
                    "RTMP URLが未設定です(設定画面で入力してください)"
                } else {
                    "配信を開始できませんでした(RTMP設定とネットワークを確認してください)"
                },
            )
        }
        return ok
    }

    fun stopStreaming() {
        rtmpSession.stopStream()
    }

    fun setBitrate(kbps: Int) {
        rtmpSession.setVideoBitrate(kbps)
    }

    fun recordings(): List<RecordingManager.Recording> = recordingManager.list()

    fun deleteRecording(file: File): Boolean = recordingManager.delete(file)

    val avatarLoaderInstance: AvatarLoader get() = avatarLoader

    fun setAvatarScale(scale: Float) {
        glRenderer.setAvatarScale(scale)
    }

    /** Preview surface from the UI (SurfaceView holder). */
    fun setPreviewSurface(surface: android.view.Surface?) {
        if (::glRenderer.isInitialized) {
            glRenderer.setPreviewSurface(surface)
        }
    }

    /**
     * Called by the UI once runtime permissions were granted (spec: Android 12+ requires
     * BLUETOOTH_CONNECT/ADVERTISE for GATT server APIs; CAMERA for binding).
     */
    fun onPermissionsGranted() {
        if (!started) return
        // BLE peripheral: start (or restart) if enabled and not running
        val bleWanted = currentSettings.bleEnabled
        val pm = peripheralManager
        if (bleWanted && pm != null && !pm.running.value) {
            pm.start()
        }
        // Camera: retry binding if the first attempt was deferred due to missing permission
        if (!cameraBound && ::cameraController.isInitialized) {
            lifecycleScope?.launch(Dispatchers.Main) { restartCamera() }
        }
    }

    // ------------------------------------------------------------------ BLE

    private fun setupPeripheral(context: Context) {
        val pm = PeripheralManager(context)
        peripheralManager = pm
        pm.commandHandler = object : PeripheralManager.CommandHandler {
            override fun onSetMode(mode: OutputMode) = setMode(mode)
            override fun onRecordStart() {
                if (!_uiState.value.recording) startRecording()
            }
            override fun onRecordStop() {
                if (_uiState.value.recording) stopRecording()
            }
            override fun onStreamStart() {
                if (_uiState.value.streamState != StreamState.STREAMING) startStreaming()
            }
            override fun onStreamStop() = stopStreaming()
            override fun onSetBitrate(kbps: Int) = setBitrate(kbps)
            override fun onStatusRequest(): StatusPayload = buildStatusPayload()
        }
        if (currentSettings.bleEnabled) {
            pm.start()
        }
        lifecycleScope?.launch {
            pm.token.collect { t -> _uiState.value = _uiState.value.copy(bleToken = t) }
        }
        lifecycleScope?.launch {
            pm.connectedDevice.collect { d ->
                _uiState.value = _uiState.value.copy(bleConnected = d != null)
            }
        }
        lifecycleScope?.launch {
            pm.running.collect { r -> _uiState.value = _uiState.value.copy(bleRunning = r) }
        }
    }

    private fun buildStatusPayload(): StatusPayload = StatusPayload(
        mode = gate.mode.value,
        recording = _uiState.value.recording,
        streaming = _uiState.value.streamState == StreamState.STREAMING,
        trackingHealthy = glRenderer.trackingHealthy,
        lod = trackingEngine.currentLod(),
        fps = if (renderFps > 0f) renderFps else _uiState.value.stats.fps,
        inferenceMs = _uiState.value.stats.poseMs + _uiState.value.stats.faceMs +
            _uiState.value.stats.handMs + _uiState.value.stats.segmentMs,
        thermalStatus = governorLevelForStatus(),
        rtmpState = _uiState.value.streamState.name,
        bitrate = currentSettings.videoBitrateKbps,
        avatarName = _uiState.value.avatarName,
    )

    private fun governorLevelForStatus(): Int = _uiState.value.governorLevel

    private fun updateUiState() {
        _uiState.value = _uiState.value.copy(
            trackingHealthy = glRenderer.trackingHealthy,
            renderFps = this.renderFps,
            thermalStatusText = if (::governor.isInitialized) governor.thermalStatusText else "NONE",
            governorLevel = if (::governor.isInitialized) governor.level else 0,
            recording = rtmpSession.recording || _uiState.value.recording,
            avatarBones = if (::glRenderer.isInitialized) glRenderer.avatarController.boneCount else 0,
            poseApplied = if (::glRenderer.isInitialized) glRenderer.avatarController.poseApplied else false,
        )
    }
}
