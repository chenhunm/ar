package com.huanban.ar

import android.Manifest
import android.app.AlertDialog
import android.content.*
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.*
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.*
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.math.Position

class StageActivity : ComponentActivity() {
    private lateinit var root: LinearLayout
    private lateinit var canvas: FrameLayout
    private lateinit var content: LinearLayout
    private lateinit var controlScroll: ScrollView
    private lateinit var status: TextView
    private var host: RenderHost? = null
    private var camera: CameraBackdrop? = null
    private var cameraTexture: TextureView? = null
    private var extraSkybox: com.google.android.filament.Skybox? = null
    private var anchor: AnchorNode? = null
    private var frame: Frame? = null
    private var resumed = false
    private var mode = "dance"
    private var fallback = false
    private lateinit var store: RoleStore
    private var stateScale = 1f
    private var stateYaw = 0f
    private val recordResult =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            if (r.resultCode == RESULT_OK && r.data != null) {
                try {
                    startForegroundService(
                        Intent(this, RecordingService::class.java)
                            .setAction("start")
                            .putExtra("code", r.resultCode)
                            .putExtra("data", r.data)
                    )
                    Ui.toast(this, "开始录制当前屏幕，可通过通知停止。录制不含声音。")
                } catch (e: Exception) {
                    Ui.toast(this, "无法开始录制：${e.message}")
                }
            } else Ui.toast(this, "录屏授权取消")
        }
    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            if (ok) setupCameraMode()
            else {
                status.text = "摄像头权限未授予"
                Ui.toast(this, "摄像头权限被拒绝，请在系统权限页开启")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mode = intent.getStringExtra("mode") ?: "dance"
        store = RoleStore(this)
        stateScale = savedInstanceState?.getFloat("zoom", 1f) ?: 1f
        stateYaw = savedInstanceState?.getFloat("yaw", 0f) ?: 0f
        buildUi()
        if (mode == "ar") {
            if (
                checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            )
                setupCameraMode()
            else cameraPermission.launch(Manifest.permission.CAMERA)
        } else setup(false)
    }

    private fun buildUi() {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        Ui.insets(this, root)
        val header =
            Ui.row(
                this,
                Ui.secondary(this, "‹ 返回") { finish() },
                Ui.text(
                    this,
                    when (mode) {
                        "ar" -> "AR 模式"
                        "preview" -> "角色预览"
                        else -> "舞蹈模式"
                    },
                    21f,
                    Ui.mint,
                ),
            )
        root.addView(header)
        canvas = FrameLayout(this).apply { setBackgroundColor(Color.rgb(31, 43, 58)) }
        content = LinearLayout(this)
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        content.addView(canvas)
        status = Ui.text(this, "准备场景…", 12f, Ui.muted)
        status.maxLines = 3
        root.addView(status)
        val controls =
            Ui.column(this).apply {
                setPadding(
                    Ui.dp(this@StageActivity, 12),
                    0,
                    Ui.dp(this@StageActivity, 12),
                    Ui.dp(this@StageActivity, 4),
                )
            }
        controlScroll = Ui.scroll(this, controls)
        content.addView(controlScroll)
        arrangeForOrientation()
        controls.addView(
            Ui.row(
                this,
                Ui.secondary(this, "切换角色") { pickRole() },
                Ui.secondary(this, "选择动作") { pickAction() },
                Ui.secondary(this, "触摸互动") {
                    host?.interact()
                    Ui.toast(this, "你好呀")
                },
            )
        )
        controls.addView(
            Ui.row(
                this,
                Ui.secondary(this, "播放 / 暂停") {
                    host?.let {
                        it.playing = !it.playing
                        Ui.toast(this, if (it.playing) "播放" else "暂停")
                    }
                },
                Ui.secondary(this, "循环开关") {
                    host?.let {
                        it.looping = !it.looping
                        Ui.toast(this, if (it.looping) "循环开启" else "循环关闭")
                    }
                },
                Ui.secondary(this, "表情") {
                    AlertDialog.Builder(this)
                        .setItems(arrayOf("开心", "眨眼")) { _, i -> host?.expression(i == 0) }
                        .show()
                },
            )
        )
        controls.addView(
            Ui.slider(this, "人物大小", 190, 90) {
                host?.let { h ->
                    h.zoom = .1f + it / 100f
                    h.updateTransform()
                }
            }
        )
        controls.addView(
            Ui.row(
                this,
                Ui.secondary(this, "左转") {
                    host?.let {
                        it.yaw -= 15
                        it.updateTransform()
                    }
                },
                Ui.secondary(this, "右转") {
                    host?.let {
                        it.yaw += 15
                        it.updateTransform()
                    }
                },
                Ui.secondary(this, "速度") {
                    AlertDialog.Builder(this)
                        .setItems(arrayOf("0.5 倍", "0.75 倍", "1 倍", "1.25 倍", "1.5 倍", "2 倍")) {
                            _,
                            i ->
                            host?.speed = listOf(.5f, .75f, 1f, 1.25f, 1.5f, 2f)[i]
                        }
                        .show()
                },
            )
        )
        controls.addView(
            Ui.row(
                this,
                Ui.secondary(this, "截图") { snapshot() },
                Ui.secondary(this, "录制视频") {
                    if (RecordingService.running)
                        stopService(Intent(this, RecordingService::class.java))
                    else {
                        val pm = getSystemService(MediaProjectionManager::class.java)
                        recordResult.launch(pm.createScreenCaptureIntent())
                    }
                },
                Ui.secondary(this, if (mode == "ar") "追踪 / 叠加" else "场景背景") {
                    if (mode == "ar") chooseAr() else backgrounds()
                },
            )
        )
        if (mode == "ar")
            controls.addView(Ui.text(this, "叠加：单指移动，双指缩放；追踪：点击已检测平面放置/重新放置。", 12f, Ui.muted))
        else controls.addView(Ui.text(this, "单指拖动调整视角 · 双指缩放 · 轻触互动", 12f, Ui.muted))
    }

    private fun arrangeForOrientation() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        content.orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        canvas.layoutParams = if (landscape) LinearLayout.LayoutParams(0, -1, 1f)
            else LinearLayout.LayoutParams(-1, 0, 1f)
        controlScroll.layoutParams = if (landscape)
            LinearLayout.LayoutParams(Ui.dp(this, 300), -1)
            else LinearLayout.LayoutParams(-1, Ui.dp(this, if (mode == "ar") 245 else 265))
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        arrangeForOrientation()
    }

    private fun setupCameraMode() {
        val availability =
            runCatching { ArCoreApk.getInstance().checkAvailability(this) }.getOrNull()
        if (availability == ArCoreApk.Availability.SUPPORTED_INSTALLED) setup(true)
        else {
            fallback = true
            setup(false)
            status.text = "摄像头叠加模式（无空间锚定）\nARCore 未安装、不可用或尚未确认支持；可在“追踪 / 叠加”中重新检查。"
        }
    }

    private fun chooseAr() {
        AlertDialog.Builder(this)
            .setItems(arrayOf("摄像头叠加（无空间锚定）", "检查并启用 ARCore 真实追踪")) { _, i ->
                if (i == 0) {
                    fallback = true
                    // Re-selecting the active fallback mode must not tear down its
                    // camera, first-frame load and GL context while they are starting.
                    if (host?.ar != false || camera == null) setup(false)
                } else
                    try {
                        val a = ArCoreApk.getInstance().checkAvailability(this)
                        if (a.isSupported) {
                            val result = ArCoreApk.getInstance().requestInstall(this, true)
                            if (result == ArCoreApk.InstallStatus.INSTALLED) {
                                fallback = false
                                setup(true)
                            } else Ui.toast(this, "安装完成后可再次点击启用追踪")
                        } else Ui.toast(this, "设备未确认支持 ARCore；继续使用摄像头叠加")
                    } catch (e: Exception) {
                        fallback = true
                        setup(false)
                        Ui.toast(this, "ARCore 不可用：${e.message}，已使用摄像头叠加")
                    }
            }
            .show()
    }

    private fun setup(realAr: Boolean) {
        val keepEngine = host != null
        if (keepEngine) RenderEngine.acquire()
        try { setupScene(realAr) }
        finally { if (keepEngine) RenderEngine.release() }
    }

    private fun setupScene(realAr: Boolean) {
        closeScene()
        canvas.removeAllViews()
        frame = null
        if (mode == "ar" && !realAr) {
            fallback = true
            cameraTexture =
                TextureView(this).also {
                    canvas.addView(it, FrameLayout.LayoutParams(-1, -1))
                    camera = CameraBackdrop(this, it) { s -> status.text = s }
                }
        }
        try {
            val h =
                RenderHost(this, ar = realAr, transparent = mode == "ar" && !realAr) { s ->
                    status.text =
                        (if (mode == "ar")
                            if (realAr) "ARCore 真实空间追踪 · 点击平面放置\n" else "摄像头叠加 · 无空间锚定\n"
                        else "") + s
                }
            host = h
            canvas.addView(h.view, FrameLayout.LayoutParams(-1, -1))
            h.onStats = { s -> status.contentDescription = s }
            if (realAr) {
                val ar = h.view as ARSceneView
                ar.onSessionFailed = { e ->
                    canvas.post {
                        if (!isFinishing) {
                            Ui.toast(this, "ARCore 会话失败，已切换摄像头叠加：${e.message}")
                            fallback = true
                            setup(false)
                        }
                    }
                }
                ar.onSessionUpdated = { _, f -> frame = f }
            }
            gestures(h, realAr)
            val role = store.selected() ?: error("角色库为空")
            h.load(role) {
                if (realAr) h.node?.isVisible = false
                h.zoom = stateScale * role.scale
                h.yaw = stateYaw
                h.updateTransform()
            }
            if (resumed) {
                h.start()
                camera?.start()
            }
        } catch (e: Exception) {
            status.text = "场景初始化失败：${e.message}"
            android.util.Log.e("Stage", "初始化失败", e)
        }
    }

    private fun gestures(h: RenderHost, realAr: Boolean) {
        var ox = 0f
        var oy = 0f
        var downX = 0f
        var downY = 0f
        var pitch = 0f
        var azimuth = 0f
        var distance = 3f
        var scaled = false
        val scale =
            ScaleGestureDetector(
                this,
                object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    override fun onScale(d: ScaleGestureDetector): Boolean {
                        scaled = true
                        if (mode == "ar") {
                            h.zoom = (h.zoom * d.scaleFactor).coerceIn(.1f, 3f)
                            h.updateTransform()
                        } else {
                            distance = (distance / d.scaleFactor).coerceIn(1.5f, 7f)
                            h.view.cameraNode.position =
                                Position(
                                    (distance * kotlin.math.sin(azimuth) * kotlin.math.cos(pitch)),
                                    distance * kotlin.math.sin(pitch),
                                    distance * kotlin.math.cos(azimuth) * kotlin.math.cos(pitch),
                                )
                            h.view.cameraNode.lookAt(Position(0f))
                        }
                        return true
                    }
                },
            )
        h.view.contentDescription = "实时三维角色，可拖动、缩放和互动"
        h.view.setOnClickListener { h.interact() }
        h.view.setOnTouchListener { _, e ->
            scale.onTouchEvent(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    ox = e.x
                    oy = e.y
                    downX = e.x
                    downY = e.y
                    scaled = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!scale.isInProgress && e.pointerCount == 1) {
                        val dx = (e.x - ox) / canvas.width.coerceAtLeast(1)
                        val dy = (e.y - oy) / canvas.height.coerceAtLeast(1)
                        if (mode == "ar" && !realAr) h.move(dx * 3, -dy * 3)
                        else if (!realAr) {
                            azimuth -= dx * 4
                            pitch = (pitch + dy * 3).coerceIn(-1.1f, 1.1f)
                            h.view.cameraNode.position =
                                Position(
                                    distance * kotlin.math.sin(azimuth) * kotlin.math.cos(pitch),
                                    distance * kotlin.math.sin(pitch),
                                    distance * kotlin.math.cos(azimuth) * kotlin.math.cos(pitch),
                                )
                            h.view.cameraNode.lookAt(Position(0f))
                        }
                    }
                    ox = e.x
                    oy = e.y
                }
                MotionEvent.ACTION_UP -> {
                    if (
                        !scaled &&
                            kotlin.math.abs(e.x - downX) + kotlin.math.abs(e.y - downY) <
                                Ui.dp(this, 10)
                    ) {
                        if (realAr) place(e)
                        h.view.performClick()
                    }
                }
            }
            true
        }
    }

    private fun place(e: MotionEvent) {
        val h = host ?: return
        val n = h.node ?: return
        val hit =
            frame?.hitTest(e)?.firstOrNull {
                val plane = it.trackable
                plane is Plane &&
                    plane.trackingState == TrackingState.TRACKING &&
                    plane.isPoseInPolygon(it.hitPose)
            }
                ?: run {
                    Ui.toast(this, "缓慢移动手机，检测到地面后再点击")
                    return
                }
        val ar = h.view as? ARSceneView ?: return
        try {
            n.parent = null
            ar.removeChildNode(n)
            anchor?.let {
                ar.removeChildNode(it)
                it.destroy()
            }
            anchor =
                AnchorNode(ar.engine, hit.createAnchor()).also {
                    ar.addChildNode(it)
                    it.addChildNode(n)
                }
            n.isVisible = true
            h.updateTransform()
        } catch (ex: Exception) {
            Ui.toast(this, "放置失败：${ex.message}")
        }
    }

    private fun pickRole() {
        val list = store.roles()
        AlertDialog.Builder(this)
            .setTitle("选择角色")
            .setItems(list.map { it.name }.toTypedArray()) { _, i ->
                store.select(list[i])
                stateScale = 1f
                stateYaw = 0f
                setup(host?.ar == true)
            }
            .show()
    }

    private fun pickAction() {
        val r = host?.role ?: return
        if (r.animations.isEmpty()) {
            Ui.toast(this, "模型没有自带动画，也没有兼容的内置骨骼动作")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("骨骼动作")
            .setItems(r.animations.toTypedArray()) { _, i -> host?.action(i) }
            .show()
    }

    private fun backgrounds() {
        AlertDialog.Builder(this)
            .setItems(arrayOf("深蓝影棚", "浅色工作室", "夜幕舞台")) { _, i ->
                val c =
                    listOf(Color.rgb(31, 43, 58), Color.rgb(205, 218, 225), Color.rgb(35, 19, 53))[
                        i]
                canvas.setBackgroundColor(c)
                val h = host ?: return@setItems
                val sky =
                    com.google.android.filament.Skybox.Builder()
                        .color(
                            floatArrayOf(
                                Color.red(c) / 255f,
                                Color.green(c) / 255f,
                                Color.blue(c) / 255f,
                                1f,
                            )
                        )
                        .build(h.view.engine)
                val old = extraSkybox
                extraSkybox = sky
                h.view.scene.skybox = sky
                if (old != null) runCatching { h.view.engine.destroySkybox(old) }
            }
            .show()
    }

    private var captureInFlight=false

    private fun snapshot() {
        if(captureInFlight){Ui.toast(this,"正在等待画面并保存截图…");return}
        val h = host ?: return
        if (h.view.width < 1 || h.node == null) {
            Ui.toast(this, "模型尚未准备好")
            return
        }
        captureInFlight=true
        val model = Bitmap.createBitmap(h.view.width, h.view.height, Bitmap.Config.ARGB_8888)
        try {
            FrameCapture.copy(
                h.view,
                model,
                { code ->
                    captureInFlight=false
                    if (code != PixelCopy.SUCCESS) {
                        model.recycle()
                        Ui.toast(this, "截图失败：$code")
                        return@copy
                    }
                    try {
                        val result =
                            if (mode == "ar" && fallback) {
                                val bg = cameraTexture?.bitmap ?: error("摄像头画面尚未准备好")
                                val b =
                                    Bitmap.createBitmap(
                                        model.width,
                                        model.height,
                                        Bitmap.Config.ARGB_8888,
                                    )
                                Canvas(b).apply {
                                    drawBitmap(bg, null, Rect(0, 0, b.width, b.height), null)
                                    drawBitmap(model, 0f, 0f, null)
                                }
                                bg.recycle()
                                b
                            } else model
                        val values =
                            ContentValues().apply {
                                put(
                                    MediaStore.Images.Media.DISPLAY_NAME,
                                    "HuanBan_${System.currentTimeMillis()}.png",
                                )
                                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/HuanBanAR")
                                put(MediaStore.Images.Media.IS_PENDING, 1)
                            }
                        val uri =
                            contentResolver.insert(
                                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                values,
                            ) ?: error("无法创建图片")
                        try {
                            contentResolver.openOutputStream(uri)?.use {
                                check(result.compress(Bitmap.CompressFormat.PNG, 100, it))
                            } ?: error("无法写入图片")
                            contentResolver.update(
                                uri,
                                ContentValues().apply {
                                    put(MediaStore.Images.Media.IS_PENDING, 0)
                                },
                                null,
                                null,
                            )
                        } catch (e: Exception) {
                            contentResolver.delete(uri, null, null)
                            throw e
                        }
                        if (result !== model) result.recycle()
                        Ui.toast(this, "已保存至相册 Pictures/HuanBanAR")
                    } catch (e: Exception) {
                        Ui.toast(this, "保存失败：${e.message}")
                    } finally {
                        model.recycle()
                    }
                },
            )
        } catch (e: Exception) {
            captureInFlight=false
            model.recycle()
            Ui.toast(this, "截图不可用：${e.message}")
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        host?.start()
        camera?.start()
    }

    override fun onPause() {
        resumed = false
        camera?.stop()
        host?.pause()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putFloat("zoom", host?.let { it.zoom / (it.role?.scale ?: 1f) } ?: 1f)
        outState.putFloat("yaw", host?.yaw ?: 0f)
        super.onSaveInstanceState(outState)
    }

    private fun closeScene() {
        extraSkybox?.let { sky ->
            host?.let { h ->
                h.view.scene.skybox = null
                h.view.engine.destroySkybox(sky)
            }
        }
        extraSkybox = null
        camera?.stop()
        camera = null
        cameraTexture = null
        anchor?.let {
            host?.view?.removeChildNode(it)
            host?.node?.parent = null
            it.destroy()
        }
        anchor = null
        host?.destroy()
        host = null
    }

    override fun onDestroy() {
        closeScene()
        super.onDestroy()
    }
}
