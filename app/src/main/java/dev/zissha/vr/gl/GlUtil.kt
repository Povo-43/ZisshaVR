package dev.zissha.vr.gl

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLU
import android.util.Log

/** OpenGL ES 3.0 helpers. All calls must happen on the owning GL thread. */
object GlUtil {

    private const val TAG = "GlUtil"

    fun checkGlError(op: String) {
        val error = GLES30.glGetError()
        if (error != GLES30.GL_NO_ERROR) {
            Log.w(TAG, "$op: glError 0x${Integer.toHexString(error)} ${GLU.gluErrorString(error)}")
        }
    }

    fun compileShader(type: Int, src: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, src)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == GLES30.GL_FALSE) {
            val log = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            throw IllegalStateException("shader compile failed: $log\n$src")
        }
        return shader
    }

    fun createProgram(vsSrc: String, fsSrc: String): Int {
        val vs = compileShader(GLES30.GL_VERTEX_SHADER, vsSrc)
        val fs = compileShader(GLES30.GL_FRAGMENT_SHADER, fsSrc)
        val program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vs)
        GLES30.glAttachShader(program, fs)
        GLES30.glLinkProgram(program)
        val status = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        if (status[0] == GLES30.GL_FALSE) {
            val log = GLES30.glGetProgramInfoLog(program)
            GLES30.glDeleteProgram(program)
            throw IllegalStateException("program link failed: $log")
        }
        return program
    }

    fun createTextureRgba(width: Int, height: Int, linear: Boolean = true): Int {
        val tex = IntArray(1)
        GLES30.glGenTextures(1, tex, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0])
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, width, height, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null,
        )
        val filter = if (linear) GLES30.GL_LINEAR else GLES30.GL_NEAREST
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, filter)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, filter)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        return tex[0]
    }

    fun createTextureLuminance(width: Int, height: Int): Int {
        val tex = IntArray(1)
        GLES30.glGenTextures(1, tex, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0])
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, width, height, 0,
            GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, null,
        )
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        return tex[0]
    }

    /** Float RGBA texture for bone matrices (sampled with texelFetch, no filtering). */
    fun createTextureBoneMatrices(boneCount: Int): Int {
        val tex = IntArray(1)
        GLES30.glGenTextures(1, tex, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0])
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA32F, 1, boneCount * 4, 0,
            GLES30.GL_RGBA, GLES30.GL_FLOAT, null,
        )
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        return tex[0]
    }

    fun createOesTexture(): Int {
        val tex = IntArray(1)
        GLES30.glGenTextures(1, tex, 0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0])
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        return tex[0]
    }

    class Fbo(val id: Int, val texture: Int, val width: Int, val height: Int, val hasDepth: Boolean) {
        fun bind() {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, id)
            GLES30.glViewport(0, 0, width, height)
        }

        fun release() {
            val ids = intArrayOf(id)
            GLES30.glDeleteFramebuffers(1, ids, 0)
            if (hasDepth) {
                GLES30.glDeleteRenderbuffers(1, intArrayOf(depthBuffer), 0)
            }
            GLES30.glDeleteTextures(1, intArrayOf(texture), 0)
        }

        var depthBuffer: Int = 0
    }

    fun createFbo(width: Int, height: Int, withDepth: Boolean = false): Fbo {
        val tex = createTextureRgba(width, height, linear = false)
        val fbo = IntArray(1)
        GLES30.glGenFramebuffers(1, fbo, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tex, 0)
        var depth = 0
        if (withDepth) {
            depth = IntArray(1).also { GLES30.glGenRenderbuffers(1, it, 0) }[0]
            GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, depth)
            GLES30.glRenderbufferStorage(GLES30.GL_RENDERBUFFER, GLES30.GL_DEPTH_COMPONENT24, width, height)
            GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT, GLES30.GL_RENDERBUFFER, depth)
        }
        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            throw IllegalStateException("FBO incomplete: 0x${Integer.toHexString(status)}")
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        return Fbo(fbo[0], tex, width, height, withDepth).also { it.depthBuffer = depth }
    }

    fun deleteTexture(tex: Int) {
        GLES30.glDeleteTextures(1, intArrayOf(tex), 0)
    }
}

/** EGL context + surface management for the render thread. */
class EglCore {

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var config: EGLConfig? = null
    var context: EGLContext = EGL14.EGL_NO_CONTEXT
        private set

    /** Query the pixel size of a surface. */
    fun surfaceSize(surface: EGLSurface): Pair<Int, Int> {
        val w = IntArray(1)
        val h = IntArray(1)
        EGL14.eglQuerySurface(display, surface, EGL14.EGL_WIDTH, w, 0)
        EGL14.eglQuerySurface(display, surface, EGL14.EGL_HEIGHT, h, 0)
        return w[0] to h[0]
    }

    fun release() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
        display = EGL14.EGL_NO_DISPLAY
        context = EGL14.EGL_NO_CONTEXT
        config = null
    }

    fun createShared(sharedContext: EglCore? = null) {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }

        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_DEPTH_SIZE, 24,
            EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        check(
            EGL14.eglChooseConfig(display, attribList, 0, configs, 0, configs.size, numConfigs, 0) &&
                numConfigs[0] > 0
        ) { "eglChooseConfig failed" }
        config = configs[0]

        val ctxAttribs = intArrayOf(EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
        context = EGL14.eglCreateContext(
            display, config, sharedContext?.context ?: EGL14.EGL_NO_CONTEXT, ctxAttribs, 0
        )
        checkEglError("eglCreateContext")
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext returned no context" }
    }

    fun createWindowSurface(surface: Any): EGLSurface {
        val attribs = intArrayOf(EGL14.EGL_NONE)
        val eglSurface = EGL14.eglCreateWindowSurface(display, config, surface, attribs, 0)
        checkEglError("eglCreateWindowSurface")
        check(eglSurface != null && eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed" }
        return eglSurface
    }

    fun createPbufferSurface(width: Int, height: Int): EGLSurface {
        val attribs = intArrayOf(EGL14.EGL_WIDTH, width, EGL14.EGL_HEIGHT, height, EGL14.EGL_NONE)
        val eglSurface = EGL14.eglCreatePbufferSurface(display, config, attribs, 0)
        checkEglError("eglCreatePbufferSurface")
        check(eglSurface != null && eglSurface != EGL14.EGL_NO_SURFACE) { "pbuffer failed" }
        return eglSurface
    }

    fun makeCurrent(surface: EGLSurface) {
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent failed" }
    }

    fun makeNothingCurrent() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
    }

    fun swap(surface: EGLSurface): Boolean = EGL14.eglSwapBuffers(display, surface)

    fun destroySurface(surface: EGLSurface) {
        if (surface != null && surface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(display, surface)
        }
    }

    private fun checkEglError(op: String) {
        val error = EGL14.eglGetError()
        if (error != EGL14.EGL_SUCCESS) {
            throw IllegalStateException("$op failed: 0x${Integer.toHexString(error)}")
        }
    }

    companion object {
        private const val EGL_CONTEXT_CLIENT_VERSION = 0x3098
        private const val EGL_OPENGL_ES3_BIT = 0x0040
        private const val EGL_RECORDABLE_ANDROID = 0x3142
    }
}
