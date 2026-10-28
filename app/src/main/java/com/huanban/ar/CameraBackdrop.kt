package com.huanban.ar

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.os.Handler
import android.view.Surface
import android.view.TextureView

/** Camera2 fallback only. It deliberately has no spatial-tracking claims. */
class CameraBackdrop(val context: Context, val texture: TextureView, val report: (String) -> Unit) {
    // Camera2 processes frames in the HAL; state callbacks stay on a live main handler.
    // Never quit a callback looper while an asynchronous open is still pending.
    private val handler = Handler(context.mainLooper)
    @Volatile private var pendingOpens = 0
    @Volatile private var pendingCloses = 0
    private val closing = mutableSetOf<CameraDevice>()
    private var failedGeneration = -1
    @Volatile private var camera: CameraDevice? = null
    @Volatile private var session: CameraCaptureSession? = null
    @Volatile private var surface: Surface? = null
    @Volatile private var active = false
    private var generation = 0
    private var previewSize: android.util.Size? = null
    private var sensorOrientation = 90
    private val displays = context.getSystemService(android.hardware.display.DisplayManager::class.java)
    private val displayListener = object : android.hardware.display.DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) {}
        override fun onDisplayRemoved(id: Int) {}
        override fun onDisplayChanged(id: Int) { configureTransform() }
    }

    /** TextureView applies SENSOR_ORIENTATION. Undo stretching, compensate for display
     * rotation, and crop uniformly. https://developer.android.com/media/camera/camera2/camera-preview */
    private fun configureTransform() {
        val size = previewSize ?: return
        val width = texture.width.toFloat()
        val height = texture.height.toFloat()
        if (width <= 0 || height <= 0) return
        val degrees = when (texture.display?.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        val swapped = sensorOrientation % 180 != 0
        val sourceWidth = (if (swapped) size.height else size.width).toFloat()
        val sourceHeight = (if (swapped) size.width else size.height).toFloat()
        val outputWidth = if (degrees % 180 == 0) sourceWidth else sourceHeight
        val outputHeight = if (degrees % 180 == 0) sourceHeight else sourceWidth
        val scale = maxOf(width / outputWidth, height / outputHeight)
        texture.setTransform(android.graphics.Matrix().apply {
            setScale(sourceWidth / width, sourceHeight / height, width / 2, height / 2)
            postRotate(-degrees.toFloat(), width / 2, height / 2)
            postScale(scale, scale, width / 2, height / 2)
        })
    }

    @Synchronized fun start() {
        if (active) return
        generation++
        active = true
        displays.registerDisplayListener(displayListener, Handler(context.mainLooper))
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) { open(s) }
            override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) { configureTransform() }
            override fun onSurfaceTextureDestroyed(s: SurfaceTexture): Boolean { stop(); return true }
            override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
        }
        if (texture.isAvailable) texture.surfaceTexture?.let { open(it) }
    }

    @Synchronized private fun open(st: SurfaceTexture) {
        if (!active || st.isReleased || camera != null || pendingOpens > 0 || pendingCloses > 0 || failedGeneration == generation) return
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            texture.post { report("未授予摄像头权限，可返回后重新授权") }
            return
        }
        val ticket = generation
        try {
            val manager = context.getSystemService(CameraManager::class.java)
            val id = manager.cameraIdList.firstOrNull {
                manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: error("没有后置摄像头")
            val ch = manager.getCameraCharacteristics(id)
            val sizes = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(SurfaceTexture::class.java) ?: error("没有预览尺寸")
            val size = sizes.filter { it.width <= 1280 }.maxByOrNull { it.width * it.height }
                ?: sizes.minBy { it.width * it.height }
            st.setDefaultBufferSize(size.width, size.height)
            previewSize = size
            sensorOrientation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            texture.post { configureTransform() }
            val continuousAf = ch.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
                ?.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) == true
            val callback = object : CameraDevice.StateCallback() {
                private var resolved = false
                fun resolve() = synchronized(this@CameraBackdrop) {
                    if (!resolved) { resolved = true; pendingOpens-- }
                }
                override fun onOpened(c: CameraDevice) { resolve(); opened(c, st, ticket, handler, continuousAf) }
                override fun onDisconnected(c: CameraDevice) { resolve(); ended(c, ticket, "摄像头已断开，可返回后重试") }
                override fun onError(c: CameraDevice, error: Int) { resolve(); ended(c, ticket, "摄像头被占用或不可用：$error，可返回后重试") }
                override fun onClosed(c: CameraDevice) { closed(c) }
            }
            pendingOpens++
            try { manager.openCamera(id, callback, handler) }
            catch (e: Exception) { callback.resolve(); throw e }
        } catch (e: Exception) {
            failedGeneration = ticket
            texture.post { if (active && ticket == generation) report("摄像头不可用：${e.message}") }
        }
    }

    /** All callbacks and stop() share a lock and a generation. An old onOpened/onConfigured
     * cannot attach a closed Surface or overwrite a new session after rapid pause/resume. */
    @Synchronized private fun opened(c: CameraDevice, st: SurfaceTexture, ticket: Int, handler: Handler, autofocus: Boolean) {
        if (!active || ticket != generation || st.isReleased) { closeDevice(c); return }
        camera = c
        try {
            val output = Surface(st)
            surface = output
            @Suppress("DEPRECATION")
            c.createCaptureSession(listOf(output), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) { configured(c, s, output, ticket, handler, autofocus) }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    s.close()
                    ended(c, ticket, "摄像头配置失败，可返回后重试")
                }
            }, handler)
        } catch (e: Exception) { ended(c, ticket, "摄像头初始化失败：${e.message}") }
    }

    @Synchronized private fun configured(c: CameraDevice, s: CameraCaptureSession, output: Surface, ticket: Int, handler: Handler, autofocus: Boolean) {
        if (!active || ticket != generation || camera !== c) { s.close(); return }
        session = s
        try {
            val request = c.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(output)
                if (autofocus) set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }
            s.setRepeatingRequest(request.build(), null, handler)
        } catch (e: Exception) { ended(c, ticket, "摄像头预览失败：${e.message}") }
    }

    @Synchronized private fun closeDevice(c: CameraDevice) {
        if (closing.add(c)) {
            pendingCloses = closing.size
            c.close()
        }
    }

    @Synchronized private fun closed(c: CameraDevice) {
        closing.remove(c)
        pendingCloses = closing.size
        if (active) texture.surfaceTexture?.let { open(it) }
    }

    @Synchronized private fun ended(c: CameraDevice, ticket: Int, message: String) {
        closeDevice(c)
        if (ticket != generation) return
        failedGeneration = ticket
        session?.close(); session = null
        if (camera === c) camera = null
        surface?.release(); surface = null
        texture.post { if (active && ticket == generation) report(message) }
    }

    @Synchronized fun stop() {
        active = false
        generation++
        displays.unregisterDisplayListener(displayListener)
        session?.close(); session = null
        camera?.let { closeDevice(it) }; camera = null
        surface?.release(); surface = null
    }
}
