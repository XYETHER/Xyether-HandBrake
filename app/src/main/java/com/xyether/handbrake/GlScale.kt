package com.xyether.handbrake

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * GPU rescale stage: decoder -> SurfaceTexture -> bilinear shader -> encoder input surface.
 *
 * Single-threaded GL (grafika style): ALL EGL/GL calls run on the caller's thread
 * (the engine loop). A tiny helper looper only delivers frame-available notifications,
 * so there are no per-frame thread handoffs — decode/GL/encode stay pipelined.
 */
class GlScalePass(
    sharedEglContext: EGLContext?,
    private val encoderSurface: Any,     // Surface from encoder.createInputSurface()
    srcW: Int, srcH: Int,
    val dstW: Int, val dstH: Int,
) {
    private val texId: Int
    private val st: SurfaceTexture
    private val disp: EGLDisplay
    private val ctx: EGLContext
    private val draw: EGLSurface
    private val locA: Int
    private val locU: Int
    private val locMatrix: Int
    private val matrix = FloatArray(16)
    private val program: Int
    private val quadBuf = java.nio.ByteBuffer.allocateDirect(8 * 4).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0)
    }
    @Volatile private var released = false
    private val frameAvail = Semaphore(0)
    private val notifyLooper = android.os.HandlerThread("hb-gl-notify").apply { start() }

    /** Decoder renders into this (wraps the SurfaceTexture). */
    val decoderSurface: android.view.Surface by lazy { android.view.Surface(st) }

    init {
        // --- EGL on the CURRENT thread ---
        disp = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (disp === EGL14.EGL_NO_DISPLAY) error("no EGL display")
        val ver = IntArray(2)
        if (!EGL14.eglInitialize(disp, ver, 0, ver, 1)) error("eglInitialize failed")
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            0x3142, 1, // EGL_RECORDABLE_ANDROID
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_NONE)
        val cfgs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        if (!EGL14.eglChooseConfig(disp, attribs, 0, cfgs, 0, 1, num, 0) || num[0] == 0)
            error("no EGL config")
        ctx = EGL14.eglCreateContext(
            disp, cfgs[0],
            sharedEglContext ?: EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        if (ctx === EGL14.EGL_NO_CONTEXT) error("eglCreateContext failed")
        draw = EGL14.eglCreateWindowSurface(disp, cfgs[0], encoderSurface, intArrayOf(EGL14.EGL_NONE), 0)
        if (draw === EGL14.EGL_NO_SURFACE) error("eglCreateWindowSurface failed")
        if (!EGL14.eglMakeCurrent(disp, draw, draw, ctx)) error("eglMakeCurrent failed")

        // --- external OES texture + SurfaceTexture (callback = counter only) ---
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        texId = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        st = SurfaceTexture(texId)
        st.setDefaultBufferSize(srcW, srcH)
        st.setOnFrameAvailableListener({
            frameAvail.release()               // notify only — NO GL here
        }, android.os.Handler(notifyLooper.looper))

        // --- shader program ---
        val vs = """
            attribute vec2 aPos;
            varying vec2 vUv;
            uniform mat4 uMatrix;
            void main() {
                vUv = (uMatrix * vec4((aPos + 1.0) / 2.0, 0.0, 1.0)).xy;
                gl_Position = vec4(aPos, 0.0, 1.0);
            }""".trimIndent()
        val fs = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTex;
            varying vec2 vUv;
            void main() { gl_FragColor = texture2D(uTex, vUv); }""".trimIndent()
        val prog = GLES20.glCreateProgram()
        program = prog
        fun compile(type: Int, src: String): Int {
            val sh = GLES20.glCreateShader(type)
            GLES20.glShaderSource(sh, src)
            GLES20.glCompileShader(sh)
            val status = IntArray(1)
            GLES20.glGetShaderiv(sh, GLES20.GL_COMPILE_STATUS, status, 0)
            check(status[0] != 0) { GLES20.glGetShaderInfoLog(sh) }
            return sh
        }
        val vert = compile(GLES20.GL_VERTEX_SHADER, vs)
        val frag = compile(GLES20.GL_FRAGMENT_SHADER, fs)
        GLES20.glAttachShader(prog, vert)
        GLES20.glAttachShader(prog, frag)
        GLES20.glLinkProgram(prog)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, linked, 0)
        if (linked[0] == 0) error("shader link failed: ${GLES20.glGetProgramInfoLog(prog)}")
        GLES20.glDeleteShader(vert)
        GLES20.glDeleteShader(frag)
        GLES20.glUseProgram(prog)
        locMatrix = GLES20.glGetUniformLocation(prog, "uMatrix")
        locA = GLES20.glGetAttribLocation(prog, "aPos")
        locU = GLES20.glGetUniformLocation(prog, "uTex")
        GLES20.glUniform1i(locU, 0)
        GLES20.glViewport(0, 0, dstW, dstH)
    }

    /** Block until the decoder has painted one frame into the texture (or timeout). */
    fun awaitFrame(): Boolean = frameAvail.tryAcquire(2, TimeUnit.SECONDS)

    /** Consume the latest decoded frame. Call after awaitFrame()==true. */
    fun consumeFrame() {
        st.updateTexImage()
        st.getTransformMatrix(matrix)
    }

    /** Draw the consumed frame scaled into the encoder surface and present it. */
    fun presentFrame(ptsNs: Long) {
        GLES20.glUniformMatrix4fv(locMatrix, 1, false, matrix, 0)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        quadBuf.position(0)
        GLES20.glEnableVertexAttribArray(locA)
        GLES20.glVertexAttribPointer(locA, 2, GLES20.GL_FLOAT, false, 0, quadBuf)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(locA)
        android.opengl.EGLExt.eglPresentationTimeANDROID(disp, draw, ptsNs)
        if (!EGL14.eglSwapBuffers(disp, draw)) error("eglSwapBuffers failed")
    }

    /** Detach the GL STS from the decoder surface before releasing (must be same thread). */
    fun detachTexture() {
        try { st.detachFromGLContext() } catch (_: Exception) {}
    }

    fun release() {
        if (released) return
        released = true
        try { decoderSurface.release() } catch (_: Exception) {}
        try { st.release() } catch (_: Exception) {}
        try {
            GLES20.glDeleteProgram(program)
            GLES20.glDeleteTextures(1, intArrayOf(texId), 0)
            EGL14.eglMakeCurrent(disp, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(disp, draw)
            EGL14.eglDestroyContext(disp, ctx)
            EGL14.eglTerminate(disp)
        } catch (_: Exception) {}
        notifyLooper.quitSafely()
    }
}

