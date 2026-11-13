package dev.zissha.vr.gl

import android.graphics.SurfaceTexture
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import dev.zissha.protocol.OutputMode
import dev.zissha.vr.avatar.render.AvatarController
import dev.zissha.vr.tracking.TrackingEngine
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Render thread: owns the EGL context, the camera SurfaceTexture and the compositor.
 * Drives frame rendering, tracking input readback and output blits (preview / encoder).
 * UI changes never block this thread (spec 12).
 */
class GLRenderer(
    private val trackingEngine: TrackingEngine,
    val avatarController: AvatarController,
) {

    companion object {
        private const val TAG = "GLRenderer"
        private const val KEEPALIVE_TICK_MS = 66L // ~15fps tick for FREEZE / camera stall output
    }

    interface Callbacks {
        fun onFrameRendered(tsMs: Long, renderMs: Float)
        fun onRenderError(what: String, e: Exception?)
        fun onFirstFrame()
    }

    var callbacks: Callbacks? = null

    private val thread = HandlerThread("ZisshaGLRender").apply { start() }
    private val handler = Handler(thread.looper)
    private val running = AtomicBoolean(false)
    private val started = AtomicBoolean(false)

    private var egl: EglCore? = null
    private var pbufferSurface: EGLSurface? = null
    private var cameraTexture = 0
    private var cameraSurfaceTexture: SurfaceTexture? = null
    private val stMatrix = FloatArray(16)

    private var previewEglSurface: EGLSurface? = null
    private var previewSurface: Surface? = null

    /** Preview surface requested before the GL context was ready (applied in initializeGl). */
    private var pendingPreviewSurface: Surface? = null

    private var encoderEglSurface: EGLSurface? = null
    private var encoderSurface: Surface? = null

    private var compositor: Compositor? = null
    private val compositorRef = AtomicReference<Compositor?>(null)

    /** Bitmaps queued before the GL thread finished initialization. */
    private var pendingPrivacyBitmap: android.graphics.Bitmap? = null

    /** rotation quarter-turns + mirror configured by the camera controller */
    @Volatile var rotationQuarterTurns: Int = 1
    @Volatile var mirror: Boolean = false

    /** min interval between rendered frames (fps governor, spec 11) */
    @Volatile var minFrameIntervalMs: Long = 0

    @Volatile var gateMode: OutputMode = OutputMode.LIVE
    @Volatile var trackingHealthy: Boolean = false
    @Volatile var segmentationEnabled: Boolean = true

    /** Composite the detected pose skeleton onto the live output (debug / alignment aid). */
    @Volatile var skeletonOverlay: Boolean = false

    /** Push the camera transform to the compositor so the skeleton overlay matches the display. */
    fun setCameraTransform(turns: Int, mirror: Boolean) {
        this.rotationQuarterTurns = turns
        this.mirror = mirror
        compositor?.let {
            it.rotationQuarterTurns = turns
            it.mirror = mirror
        }
    }

    private var lastRenderUptimeMs = 0L
    private var lastCameraFrameTsMs = 0L
    private var frameCount = 0L

    /** latest camera frame available callback target (SurfaceTexture -> CameraController) */
    var onCameraSurfaceCreated: ((SurfaceTexture) -> Unit)? = null

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!running.get()) return
            // keepalive: re-emit the current gate source when the camera is quiet (FREEZE etc.)
            val now = SystemClock.uptimeMillis()
            if (now - lastRenderUptimeMs > KEEPALIVE_TICK_MS) {
                renderAndOutput(now, fromTick = true)
            }
            handler.postDelayed(this, KEEPALIVE_TICK_MS)
        }
    }

    private val frameListener = SurfaceTexture.OnFrameAvailableListener {
        if (running.get()) handler.post { renderAndOutput(SystemClock.uptimeMillis(), fromTick = false) }
    }

    fun start() {
        if (!started.compareAndSet(false, true)) return
        running.set(true)
        handler.post {
            try {
                initializeGl()
                running.set(true)
                handler.postDelayed(tickRunnable, KEEPALIVE_TICK_MS)
            } catch (e: Exception) {
                Log.e(TAG, "GL init failed", e)
                callbacks?.onRenderError("gl_init", e)
            }
        }
    }

    fun stop() {
        running.set(false)
        handler.post {
            handler.removeCallbacks(tickRunnable)
            releaseGl()
            thread.quitSafely()
        }
    }

    /**
     * Preview surface from the UI thread (SurfaceView). Safe to call anytime.
     *
     * The UI sends the surface on both surfaceCreated and surfaceChanged; repeat calls with the
     * same Surface must be a no-op. The Surface itself is owned by the SurfaceView, so it is
     * never released here (releasing it invalidates the SurfaceView and blanks the preview).
     */
    fun setPreviewSurface(surface: Surface?) {
        handler.post {
            val s = surface
            if (s === previewSurface) return@post
            releasePreviewSurface()
            if (s != null && s.isValid) {
                previewSurface = s
                if (egl != null) {
                    createPreviewEglSurface(s)
                } else {
                    // GL thread not initialized yet: remember and apply after initializeGl.
                    pendingPreviewSurface = s
                }
            }
        }
    }

    private fun createPreviewEglSurface(surface: Surface) {
        previewEglSurface = try {
            egl?.createWindowSurface(surface)
        } catch (e: Exception) {
            Log.e(TAG, "preview surface create failed", e)
            null
        }
        if (previewEglSurface == null) {
            Log.w(TAG, "preview EGL surface unavailable; output stays off")
        } else {
            Log.i(TAG, "preview EGL surface ready")
        }
    }

    /** RootEncoder surface texture for stream/record output. */
    fun setEncoderSurfaceTexture(surfaceTexture: SurfaceTexture?) {
        handler.post {
            releaseEncoderSurface()
            val st = surfaceTexture
            if (st != null) {
                val surface = Surface(st)
                encoderSurface = surface
                try {
                    encoderEglSurface = egl?.createWindowSurface(surface)
                } catch (e: Exception) {
                    Log.e(TAG, "encoder surface create failed", e)
                    encoderSurface = null
                }
            }
        }
    }

    fun setPrivacyImage(bitmap: android.graphics.Bitmap?) {
        handler.post {
            val c = compositor
            if (c == null) {
                // GL thread not initialized yet: keep it and apply after initializeGl
                pendingPrivacyBitmap = bitmap
            } else {
                c.setPrivacyImage(bitmap)
            }
        }
    }

    /** Avatar scale adjustment from settings (spec 12 スケール調整). */
    fun setAvatarScale(scale: Float) {
        handler.post { avatarController.scaleMultiplier = scale.coerceIn(0.3f, 3f) }
    }

    fun setOutputSize(width: Int, height: Int) {
        handler.post { compositor?.setOutputSize(width, height) }
    }

    fun reloadAvatar(doc: dev.zissha.vr.avatar.gltf.GltfDocument) {
        handler.post {
            try {
                avatarController.load(doc)
            } catch (e: Exception) {
                Log.e(TAG, "avatar load failed", e)
                callbacks?.onRenderError("avatar_load", e)
            }
        }
    }

    private fun initializeGl() {
        val core = EglCore()
        core.createShared()
        egl = core
        pbufferSurface = core.createPbufferSurface(1, 1)
        core.makeCurrent(pbufferSurface!!)

        cameraTexture = GlUtil.createOesTexture()
        val st = SurfaceTexture(cameraTexture)
        st.setOnFrameAvailableListener(frameListener, handler)
        cameraSurfaceTexture = st

        compositor = Compositor(avatarController)
        compositor!!.initialize()
        compositorRef.set(compositor)
        pendingPrivacyBitmap?.let {
            compositor!!.setPrivacyImage(it)
            pendingPrivacyBitmap = null
        }

        // A preview surface may have arrived before the context existed; bind it now.
        pendingPreviewSurface?.let { s ->
            pendingPreviewSurface = null
            if (s.isValid) {
                previewSurface = s
                createPreviewEglSurface(s)
            }
        }

        onCameraSurfaceCreated?.invoke(st)
    }

    private fun releaseGl() {
        releasePreviewSurface()
        releaseEncoderSurface()
        compositor?.release()
        compositorRef.set(null)
        cameraSurfaceTexture?.release()
        cameraSurfaceTexture = null
        GlUtil.deleteTexture(cameraTexture)
        cameraTexture = 0
        pbufferSurface?.let { egl?.destroySurface(it) }
        pbufferSurface = null
        egl?.release()
        egl = null
    }

    private fun releasePreviewSurface() {
        previewEglSurface?.let { egl?.destroySurface(it) }
        previewEglSurface = null
        // The preview Surface is owned by the SurfaceView / SurfaceHolder. Do NOT call
        // release() on it: that invalidates the SurfaceView and permanently blanks the preview.
        previewSurface = null
        pendingPreviewSurface = null
    }

    private fun releaseEncoderSurface() {
        encoderEglSurface?.let { egl?.destroySurface(it) }
        encoderEglSurface = null
        encoderSurface?.release()
        encoderSurface = null
    }

    private fun renderAndOutput(nowMs: Long, fromTick: Boolean) {
        val egl = this.egl ?: return
        val compositor = this.compositor ?: return
        val st = cameraSurfaceTexture ?: return
        compositor.skeletonOverlay = skeletonOverlay
        if (minFrameIntervalMs > 0 && nowMs - lastRenderUptimeMs < minFrameIntervalMs && !fromTick) {
            return
        }
        val t0 = SystemClock.uptimeMillis()
        try {
            egl.makeCurrent(pbufferSurface!!)
            var newFrame = false
            var tsNs = 0L
            if (!fromTick) {
                st.updateTexImage()
                st.getTransformMatrix(stMatrix)
                tsNs = st.timestamp
                newFrame = true
                lastCameraFrameTsMs = tsNs / 1_000_000L
            }
            val tsMs = lastCameraFrameTsMs

            // Prefer the tracking result that belongs to this camera frame, but always fall back to
            // the newest available result so the avatar keeps following even when the exact
            // timestamp is not in the history window (slow inference / clock skew).
            val tracking = if (gateMode == OutputMode.PRIVACY) {
                null
            } else {
                val window = if (newFrame) 150L else 400L
                trackingEngine.frameFor(tsMs, maxAgeMs = window)
                    ?: trackingEngine.frameFor(tsMs, maxAgeMs = 1000L)
                    ?: trackingEngine.latest.value
            }

            val dtSec = if (lastRenderUptimeMs == 0L) 0.016f else (nowMs - lastRenderUptimeMs) / 1000f
            val readback = compositor.renderFrame(
                cameraTexture, stMatrix, rotationQuarterTurns, mirror,
                tracking, segmentationEnabled, gateMode, trackingHealthy, dtSec,
            )
            if (newFrame && readback != null) {
                trackingEngine.submitFrame(tsMs, readback)
            }

            // outputs
            previewEglSurface?.let { surface ->
                egl.makeCurrent(surface)
                val (w, h) = egl.surfaceSize(surface)
                compositor.drawOutput(gateMode, w, h)
                egl.swap(surface)
            }
            encoderEglSurface?.let { surface ->
                egl.makeCurrent(surface)
                compositor.drawOutput(gateMode, compositor.width, compositor.height)
                egl.swap(surface)
            }

            lastRenderUptimeMs = nowMs
            frameCount++
            if (frameCount == 1L) callbacks?.onFirstFrame()
            callbacks?.onFrameRendered(tsMs, (SystemClock.uptimeMillis() - t0).toFloat())
        } catch (e: RuntimeException) {
            Log.e(TAG, "GL error in frame", e)
            callbacks?.onRenderError("gl_frame", e)
        } catch (e: Exception) {
            Log.e(TAG, "render error", e)
            callbacks?.onRenderError("render", e)
        }
    }

    fun isRunning(): Boolean = running.get()
}
