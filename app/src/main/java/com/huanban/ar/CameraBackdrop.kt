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
    /** TextureView.getBitmap omits setTransform. Apply the same crop/rotation
     * as the live preview so saved images and final-display checks agree. */
    fun capturePreview(): android.graphics.Bitmap? {
        val raw = texture.bitmap ?: return null
        try {
            val result = android.graphics.Bitmap.createBitmap(texture.width, texture.height, android.graphics.Bitmap.Config.ARGB_8888)
            android.graphics.Canvas(result).apply {
                drawColor(android.graphics.Color.BLACK)
                drawBitmap(raw, texture.getTransform(android.graphics.Matrix()), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
            }
            return result
        } finally { raw.recycle() }
    }

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
    private data class Preview(val id:String,val size:android.util.Size,val sensor:Int,val autofocus:Boolean)
    private var previews = emptyList<Preview>()
    private var previewIndex = 0
    private var failures = 0
    private var reportedGeneration = -1
    private var lastMessage = "相机尚未启动"
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
        previews = emptyList(); previewIndex = 0; failures = 0
        message("正在连接后置摄像头…")
        displays.registerDisplayListener(displayListener, Handler(context.mainLooper))
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) { open(s) }
            override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) { configureTransform() }
            override fun onSurfaceTextureDestroyed(s: SurfaceTexture): Boolean {
                // Surface destruction is not an Activity stop. Keep the desired
                // running state so onSurfaceTextureAvailable can reopen it.
                synchronized(this@CameraBackdrop) { generation++; closeCurrent() }
                return true
            }
            override fun onSurfaceTextureUpdated(s: SurfaceTexture) {
                synchronized(this@CameraBackdrop) {
                    if(active && session!=null && s.timestamp>0 && reportedGeneration!=generation) {
                        reportedGeneration=generation;failures=0;message("相机已开启")
                    }
                }
            }
        }
        if (texture.isAvailable) texture.surfaceTexture?.let { open(it) }
    }

    @Synchronized private fun open(st: SurfaceTexture) {
        if (!active || !texture.isAvailable || texture.surfaceTexture!==st || st.isReleased || camera != null || pendingOpens > 0 || pendingCloses > 0 || failedGeneration == generation) return
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            message("尚未授予摄像头权限，请点“相机权限”开启")
            return
        }
        val ticket = generation
        try {
            val manager = context.getSystemService(CameraManager::class.java)
            if(previews.isEmpty()) {
                previews=manager.cameraIdList.sortedBy { if(it=="0") "" else it }.flatMap { id ->
                    runCatching {
                    val ch=manager.getCameraCharacteristics(id)
                    val capabilities=ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
                    if(ch.get(CameraCharacteristics.LENS_FACING)!=CameraCharacteristics.LENS_FACING_BACK ||
                        !capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE)) emptyList()
                    else {
                        val sizes=ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                            ?.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
                        val preferred=listOf(android.util.Size(1280,720),android.util.Size(640,480))
                            .filter { it in sizes }
                        val safe=sizes.filter { it.width.toLong()*it.height<=1280L*960 }
                            .sortedBy { it.width.toLong()*it.height }
                        (preferred+safe+sizes.sortedBy { it.width.toLong()*it.height }.take(1)).distinct().take(4).map {
                            Preview(id,it,ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
                                ch.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
                                    ?.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)==true)
                        }
                    }
                    }.getOrElse { error ->
                        android.util.Log.w("HuanBanCamera","Skipping unavailable camera $id",error)
                        emptyList<Preview>()
                    }
                }
            }
            require(previews.isNotEmpty()) { "没有可用的后置预览摄像头" }
            val choice=previews[previewIndex.coerceIn(previews.indices)]
            val id=choice.id;val size=choice.size
            st.setDefaultBufferSize(size.width, size.height)
            previewSize = size
            sensorOrientation = choice.sensor
            texture.post { configureTransform() }
            val continuousAf = choice.autofocus
            android.util.Log.i("HuanBanCamera","open generation=$ticket camera=$id size=$size attempt=${failures+1}")
            val callback = object : CameraDevice.StateCallback() {
                private var resolved = false
                fun resolve() = synchronized(this@CameraBackdrop) {
                    if (!resolved) { resolved = true; pendingOpens-- }
                }
                override fun onOpened(c: CameraDevice) { resolve(); opened(c, st, ticket, handler, continuousAf) }
                override fun onDisconnected(c: CameraDevice) { resolve(); ended(c,ticket,"摄像头连接已断开") }
                override fun onError(c: CameraDevice, error: Int) {
                    resolve()
                    val text=when(error) {
                        ERROR_CAMERA_IN_USE,ERROR_MAX_CAMERAS_IN_USE -> "摄像头被其他应用占用"
                        ERROR_CAMERA_DISABLED -> "摄像头被系统禁用，请检查相机权限和系统摄像头隐私开关"
                        else -> "摄像头服务暂时不可用（$error）"
                    }
                    ended(c,ticket,text,retryable=error!=ERROR_CAMERA_DISABLED,
                        nextPreview=error!=ERROR_CAMERA_IN_USE && error!=ERROR_MAX_CAMERAS_IN_USE)
                }
                override fun onClosed(c: CameraDevice) { closed(c) }
            }
            pendingOpens++
            try { manager.openCamera(id, callback, handler) }
            catch (e: Exception) { callback.resolve(); throw e }
        } catch (e: Exception) {
            android.util.Log.e("HuanBanCamera","open failed generation=$ticket",e)
            retry(ticket,"摄像头不可用：${e.message}",e !is SecurityException && previews.isNotEmpty(),false)
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
                    ended(c,ticket,"摄像头预览配置失败",nextPreview=true)
                }
            }, handler)
        } catch (e: Exception) { ended(c,ticket,"摄像头初始化失败：${e.message}",nextPreview=true) }
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
            val st=texture.surfaceTexture
            val before=st?.timestamp ?: 0L
            handler.postDelayed({synchronized(this@CameraBackdrop) {
                if(active && generation==ticket && session===s && st!=null && !st.isReleased && st.timestamp==before)
                    ended(c,ticket,"相机尚未输出画面",nextPreview=true)
            }},8000)
        } catch (e: Exception) { ended(c,ticket,"摄像头预览失败：${e.message}",nextPreview=true) }
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
        if(camera===c) {
            camera=null;session?.close();session=null;surface?.release();surface=null
            retry(generation,"相机连接已关闭",true,false)
        }
        if (active) texture.surfaceTexture?.let { open(it) }
    }

    @Synchronized private fun ended(c: CameraDevice, ticket: Int, message: String, retryable:Boolean=true, nextPreview:Boolean=false) {
        closeDevice(c)
        if (ticket != generation) return
        session?.close(); session = null
        if (camera === c) camera = null
        surface?.release(); surface = null
        retry(ticket,message,retryable,nextPreview)
    }

    @Synchronized private fun retry(ticket:Int,text:String,retryable:Boolean,nextPreview:Boolean) {
        if(!active || ticket!=generation || failedGeneration==ticket)return
        failedGeneration=ticket
        if(!retryable || failures>=3) {message("$text。可点“重试相机”重新连接");return}
        failures++
        if(nextPreview && previewIndex+1<previews.size)previewIndex++
        message("$text，正在重新连接（$failures/3）…")
        handler.postDelayed({synchronized(this@CameraBackdrop) {
            if(active && generation==ticket) {
                generation++
                texture.surfaceTexture?.let {open(it)}
            }
        }},failures*500L)
    }

    private fun message(text:String) {
        lastMessage=text
        android.util.Log.i("HuanBanCamera",text)
        report(text)
    }

    @Synchronized fun diagnostics():String = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} / Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})\n$lastMessage\nactive=$active camera=${camera!=null} session=${session!=null} pendingOpen=$pendingOpens pendingClose=$pendingCloses preview=$previewSize generation=$generation"

    @Synchronized fun restart() {stop();start()}

    private fun closeCurrent() {
        session?.close(); session = null
        camera?.let { closeDevice(it) }; camera = null
        surface?.release(); surface = null
    }

    @Synchronized fun stop() {
        active = false
        generation++
        displays.unregisterDisplayListener(displayListener)
        closeCurrent()
    }
}
