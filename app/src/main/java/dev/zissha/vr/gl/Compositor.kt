package dev.zissha.vr.gl

import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLES11Ext
import android.util.Log
import dev.zissha.vr.avatar.render.AvatarController
import dev.zissha.vr.tracking.SegmentationResult
import dev.zissha.vr.tracking.TrackingFrame
import dev.zissha.protocol.OutputMode
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * GPU composition pipeline (spec 6). All intermediate passes run in FBOs; no CPU bitmap
 * conversion of the composited frames. The final composite is shared by preview, recording
 * and RTMP output (spec 6.3).
 *
 * Passes:
 *   1. camera -> trackFBO (low-res RGBA, readback only for MediaPipe input)
 *   2. mask: upload -> temporal EMA -> separable blur (person mask, spec 5)
 *   3. avatar layer into transparent FBO with depth
 *   4. composite: real background + avatar masked into the person region
 *   5. safety gate: composite / frozen frame / privacy image selected for all outputs
 */
class Compositor(private val avatarController: AvatarController) {

    companion object {
        private const val TAG = "Compositor"
        const val TRACK_W = dev.zissha.vr.tracking.TrackingEngine.TRACK_W
        const val TRACK_H = dev.zissha.vr.tracking.TrackingEngine.TRACK_H
        const val MASK_SIZE = 256
        private const val RING_FRAMES = 2
    }

    // programs
    private var cameraProgram = 0
    private var blitProgram = 0
    private var maskTemporalProgram = 0
    private var maskBlurProgram = 0
    private var compositeProgram = 0

    // quad
    private var quadVbo = 0
    private var quadVao = 0

    // FBOs
    private var trackFbo: GlUtil.Fbo? = null
    private var avatarFbo: GlUtil.Fbo? = null
    private var compositeFbo: GlUtil.Fbo? = null
    private var maskFboA: GlUtil.Fbo? = null
    private var maskFboB: GlUtil.Fbo? = null

    // textures
    private var maskRawTex = 0
    private val ringTextures = IntArray(RING_FRAMES)
    private var privacyTex = 0
    private var maskHasData = false
    private var ringIndex = 0

    var width = 720
        private set
    var height = 1280
        private set

    private var trackingReadback: ByteBuffer? = null
    private var lastMaskTs = 0L

    // cached uniforms
    private val uCache = HashMap<Int, HashMap<String, Int>>()

    fun initialize() {
        cameraProgram = GlUtil.createProgram(ShaderLib.QUAD_VS, ShaderLib.CAMERA_FS)
        blitProgram = GlUtil.createProgram(ShaderLib.QUAD_VS, ShaderLib.BLIT_FS)
        maskTemporalProgram = GlUtil.createProgram(ShaderLib.QUAD_VS, ShaderLib.MASK_TEMPORAL_FS)
        maskBlurProgram = GlUtil.createProgram(ShaderLib.QUAD_VS, ShaderLib.MASK_BLUR_FS)
        compositeProgram = GlUtil.createProgram(ShaderLib.QUAD_VS, ShaderLib.COMPOSITE_FS)

        // full-screen quad: two triangles
        val quad = floatArrayOf(
            -1f, -1f, 1f, -1f, -1f, 1f,
            -1f, 1f, 1f, -1f, 1f, 1f,
        )
        quadVbo = IntArray(1).also { GLES30.glGenBuffers(1, it, 0) }[0]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadVbo)
        val quadBuffer = ByteBuffer.allocateDirect(quad.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(quad); position(0)
            }
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER, quad.size * 4, quadBuffer, GLES30.GL_STATIC_DRAW,
        )
        quadVao = IntArray(1).also { GLES30.glGenVertexArrays(1, it, 0) }[0]
        GLES30.glBindVertexArray(quadVao)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glBindVertexArray(0)

        maskRawTex = GlUtil.createTextureLuminance(MASK_SIZE, MASK_SIZE)
        for (i in 0 until RING_FRAMES) ringTextures[i] = GlUtil.createTextureRgba(width, height, linear = false)
        setOutputSize(width, height)
    }

    fun setOutputSize(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        if (w == width && h == height && compositeFbo != null) return
        width = w
        height = h
        trackFbo?.release()
        avatarFbo?.release()
        compositeFbo?.release()
        trackFbo = GlUtil.createFbo(TRACK_W, TRACK_H)
        avatarFbo = GlUtil.createFbo(width, height, withDepth = true)
        compositeFbo = GlUtil.createFbo(width, height)
        // ring textures resize
        for (i in 0 until RING_FRAMES) {
            if (ringTextures[i] != 0) GlUtil.deleteTexture(ringTextures[i])
            ringTextures[i] = GlUtil.createTextureRgba(width, height, linear = false)
        }
        maskFboA?.release()
        maskFboB?.release()
        maskFboA = GlUtil.createFbo(MASK_SIZE, MASK_SIZE)
        maskFboB = GlUtil.createFbo(MASK_SIZE, MASK_SIZE)
        maskHasData = false
        ringIndex = 0
        avatarController.updateProjection(width, height)
        Log.i(TAG, "output size ${width}x$height")
    }

    /** Upload a user-selected privacy image; scaled to the output size. */
    fun setPrivacyImage(bitmap: Bitmap?) {
        if (privacyTex != 0) GlUtil.deleteTexture(privacyTex)
        privacyTex = 0
        bitmap ?: return
        privacyTex = GlUtil.createTextureRgba(bitmap.width, bitmap.height)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, privacyTex)
        android.opengl.GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
    }

    /**
     * Render one camera frame. Returns the tracking input RGBA buffer (already flipped into
     * view space) or null when tracking is disabled.
     */
    fun renderFrame(
        cameraTex: Int,
        stMatrix: FloatArray,
        rotation: Int,
        mirror: Boolean,
        tracking: TrackingFrame?,
        segmentationEnabled: Boolean,
        gateMode: OutputMode,
        trackingHealthy: Boolean,
        dtSec: Float,
    ): ByteBuffer? {
        if (compositeFbo == null) return null

        // ---- pass 1: tracking input ------------------------------------------------
        var readback: ByteBuffer? = null
        if (gateMode != OutputMode.PRIVACY) {
            trackFbo!!.bind()
            GLES30.glClearColor(0f, 0f, 0f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            drawCamera(cameraTex, stMatrix, rotation, mirror, 1f, cameraProgram)
            readback = readTrackFbo()
        }

        // ---- pass 2: mask ------------------------------------------------------------
        if (segmentationEnabled && gateMode == OutputMode.LIVE) {
            tracking?.segmentation?.let { seg ->
                if (seg.tsMs > lastMaskTs) {
                    uploadMask(seg)
                    lastMaskTs = seg.tsMs
                }
            }
            if (maskHasData) smoothMask()
        }

        // ---- pass 3: avatar ----------------------------------------------------------
        avatarFbo!!.bind()
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        if (gateMode == OutputMode.LIVE && avatarController.hasAvatar()) {
            avatarController.update(tracking, dtSec, springEnabled = true)
            avatarController.draw()
        }

        // ---- pass 4: composite ---------------------------------------------------------
        compositeFbo!!.bind()
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        // background
        if (gateMode != OutputMode.PRIVACY) {
            drawCamera(cameraTex, stMatrix, rotation, mirror, 1f, cameraProgram)
        }
        // avatar layer with person mask
        GLES30.glUseProgram(compositeProgram)
        GLES30.glBindVertexArray(quadVao)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, avatarFbo!!.texture)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, maskFboB?.texture ?: 0)
        uniform(compositeProgram, "uAvatar")?.let { GLES30.glUniform1i(it, 0) }
        uniform(compositeProgram, "uMask")?.let { GLES30.glUniform1i(it, 1) }
        uniform(compositeProgram, "uMaskEnabled")?.let {
            // without mask data the avatar layer is shown with its own alpha over the background
            GLES30.glUniform1f(
                it, if (segmentationEnabled && maskHasData && gateMode == OutputMode.LIVE) 1f else 0f,
            )
        }
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 6)

        // ---- pass 5: safety gate source selection + ring store --------------------------
        if (gateMode == OutputMode.LIVE && trackingHealthy) {
            copyCompositeToRing()
        }
        GLES30.glBindVertexArray(0)
        return readback
    }

    /**
     * Blit the current gate source to the bound surface. Must be called with the target
     * EGL surface already current.
     */
    fun drawOutput(gateMode: OutputMode, surfaceWidth: Int, surfaceHeight: Int) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        val source = when (gateMode) {
            OutputMode.LIVE -> compositeFbo?.texture ?: 0
            OutputMode.FREEZE -> ringTextures[ringIndex]
            OutputMode.PRIVACY -> privacyTex
        }
        if (source == 0) {
            // nothing to show yet: black frame (never raw camera, spec 7)
            return
        }
        GLES30.glUseProgram(blitProgram)
        GLES30.glBindVertexArray(quadVao)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, source)
        uniform(blitProgram, "uTex")?.let { GLES30.glUniform1i(it, 0) }
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 6)
        GLES30.glBindVertexArray(0)
    }

    private fun drawCamera(
        cameraTex: Int,
        stMatrix: FloatArray,
        rotation: Int,
        mirror: Boolean,
        brightness: Float,
        program: Int,
    ) {
        GLES30.glUseProgram(program)
        GLES30.glBindVertexArray(quadVao)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTex)
        uniform(program, "uTex")?.let { GLES30.glUniform1i(it, 0) }
        uniform(program, "uSTMatrix")?.let { GLES30.glUniformMatrix4fv(it, 1, false, stMatrix, 0) }
        uniform(program, "uRotation")?.let { GLES30.glUniform1i(it, rotation) }
        uniform(program, "uMirror")?.let { GLES30.glUniform1f(it, if (mirror) 1f else 0f) }
        uniform(program, "uBrightness")?.let { GLES30.glUniform1f(it, brightness) }
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 6)
        GLES30.glBindVertexArray(0)
    }

    private fun readTrackFbo(): ByteBuffer? {
        val needed = TRACK_W * TRACK_H * 4
        var buf = trackingReadback
        if (buf == null || buf.capacity() != needed) {
            buf = ByteBuffer.allocateDirect(needed).order(ByteOrder.nativeOrder())
            trackingReadback = buf
        }
        buf.clear()
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, trackFbo!!.id)
        GLES30.glReadPixels(0, 0, TRACK_W, TRACK_H, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        buf.position(0)
        return buf
    }

    private fun uploadMask(seg: SegmentationResult) {
        if (seg.width != MASK_SIZE || seg.height != MASK_SIZE) {
            Log.w(TAG, "unexpected mask size ${seg.width}x${seg.height}; expected $MASK_SIZE")
        }
        val w = minOf(seg.width, MASK_SIZE)
        val h = minOf(seg.height, MASK_SIZE)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, maskRawTex)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        val bytes = ByteArray(w * h)
        for (y in 0 until h) {
            val srcRow = y * seg.width
            for (x in 0 until w) {
                bytes[y * w + x] = (seg.mask[srcRow + x].coerceIn(0f, 1f) * 255f).toInt().toByte()
            }
        }
        val bb = ByteBuffer.allocateDirect(bytes.size).put(bytes).apply { position(0) }
        GLES30.glTexSubImage2D(
            GLES30.GL_TEXTURE_2D, 0, 0, 0, w, h,
            GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, bb,
        )
        maskHasData = true
    }

    private fun smoothMask() {
        val a = maskFboA!!
        val b = maskFboB!!
        // temporal blend: prev=ping, new=raw -> pong
        b.bind()
        GLES30.glUseProgram(maskTemporalProgram)
        GLES30.glBindVertexArray(quadVao)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, a.texture)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, maskRawTex)
        uniform(maskTemporalProgram, "uPrev")?.let { GLES30.glUniform1i(it, 0) }
        uniform(maskTemporalProgram, "uNew")?.let { GLES30.glUniform1i(it, 1) }
        uniform(maskTemporalProgram, "uAlpha")?.let { GLES30.glUniform1f(it, 0.45f) }
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 6)
        // blur horizontal: pong -> ping
        a.bind()
        GLES30.glUseProgram(maskBlurProgram)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, b.texture)
        uniform(maskBlurProgram, "uTex")?.let { GLES30.glUniform1i(it, 0) }
        uniform(maskBlurProgram, "uStep")?.let { GLES30.glUniform2f(it, 1.5f / MASK_SIZE, 0f) }
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 6)
        // blur vertical: ping -> pong
        b.bind()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, a.texture)
        uniform(maskBlurProgram, "uTex")?.let { GLES30.glUniform1i(it, 0) }
        uniform(maskBlurProgram, "uStep")?.let { GLES30.glUniform2f(it, 0f, 1.5f / MASK_SIZE) }
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 6)
        GLES30.glBindVertexArray(0)
    }

    private fun copyCompositeToRing() {
        ringIndex = (ringIndex + 1) % RING_FRAMES
        val tex = ringTextures[ringIndex]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, compositeFbo!!.id)
        GLES30.glCopyTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, 0, 0, width, height)
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, 0)
        GlUtil.checkGlError("copyCompositeToRing")
    }

    private fun uniform(program: Int, name: String): Int? {
        val perProgram = uCache.getOrPut(program) { HashMap() }
        val cached = perProgram[name]
        if (cached != null) return if (cached < 0) null else cached
        val loc = GLES30.glGetUniformLocation(program, name)
        perProgram[name] = loc
        return if (loc < 0) null else loc
    }

    fun release() {
        trackFbo?.release()
        avatarFbo?.release()
        compositeFbo?.release()
        maskFboA?.release()
        maskFboB?.release()
        if (maskRawTex != 0) GlUtil.deleteTexture(maskRawTex)
        for (i in 0 until RING_FRAMES) {
            if (ringTextures[i] != 0) GlUtil.deleteTexture(ringTextures[i])
            ringTextures[i] = 0
        }
        if (privacyTex != 0) GlUtil.deleteTexture(privacyTex)
        privacyTex = 0
        if (quadVbo != 0) GLES30.glDeleteBuffers(1, intArrayOf(quadVbo), 0)
        if (quadVao != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(quadVao), 0)
        for (p in intArrayOf(cameraProgram, blitProgram, maskTemporalProgram, maskBlurProgram, compositeProgram)) {
            if (p != 0) GLES30.glDeleteProgram(p)
        }
        uCache.clear()
        maskHasData = false
    }
}
