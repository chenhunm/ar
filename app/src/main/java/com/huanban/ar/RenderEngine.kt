package com.huanban.ar

import android.opengl.EGLContext
import android.opengl.EGL14
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.os.Looper
import com.google.android.filament.Engine
import io.github.sceneview.SceneView

/** All windows share one engine/context, released only after the last view has drained its work. */
internal object RenderEngine {
    private var engine: Engine? = null
    private var egl: EGLContext? = null
    private var display: EGLDisplay? = null
    private var pbuffer: EGLSurface? = null
    private var users = 0

    fun acquire(): Engine {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (engine == null) {
            val context = SceneView.createEglContext()
            egl = context
            display = EGL14.eglGetCurrentDisplay()
            pbuffer = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
            val uniforms = IntArray(1)
            GLES30.glGetIntegerv(GLES30.GL_MAX_VERTEX_UNIFORM_VECTORS, uniforms, 0)
            val renderer=GLES30.glGetString(GLES30.GL_RENDERER).orEmpty()
            val version=GLES30.glGetString(GLES30.GL_VERSION).orEmpty()
            android.util.Log.i("HuanBanGpu", "renderer=$renderer version=$version vertexUniforms=${uniforms[0]}")
            if (GpuCompatibility.isKnownBrokenDriver(renderer,version,uniforms[0])) {
                destroyContext()
                throw UnsupportedOperationException(
                    "当前旧版 SwiftShader 4 软件驱动存在已确认的骨骼着色器故障（GPU 顶点 uniform 上限 ${uniforms[0]}）。请改用 ANGLE 软件后端或受支持的安卓设备。"
                )
            }
            try { engine = SceneView.createEngine(context) }
            catch (e: Throwable) { destroyContext(); throw e }
        }
        users++
        return engine!!
    }

    fun release() {
        check(Looper.myLooper() == Looper.getMainLooper())
        check(users > 0)
        users--
        if (users == 0) {
            try {
                engine?.flushAndWait()
                engine?.destroy()
            } finally {
                engine = null
                destroyContext()
            }
        }
    }
    /** SceneView 2.2.1's helper creates a current 1x1 pbuffer, but its destroy helper
     * only calls eglDestroyContext. We own both objects: unbind before destruction
     * and release the pbuffer too. Never terminate HWUI's shared EGL display.
     */
    private fun destroyContext() {
        val d = display
        val c = egl
        val surface = pbuffer
        if (d != null && d != EGL14.EGL_NO_DISPLAY) {
            if (c != null && EGL14.eglGetCurrentContext() == c)
                check(EGL14.eglMakeCurrent(d,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT)) { "无法解除共享 GL 上下文" }
            if (surface != null && surface != EGL14.EGL_NO_SURFACE)
                check(EGL14.eglDestroySurface(d,surface)) { "无法释放共享 GL 缓冲表面" }
            if (c != null && c != EGL14.EGL_NO_CONTEXT)
                check(EGL14.eglDestroyContext(d,c)) { "无法释放共享 GL 上下文" }
        }
        pbuffer = null
        egl = null
        display = null
    }

}
