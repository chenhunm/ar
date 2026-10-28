package com.huanban.ar

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.*

class PetService : Service() {
    companion object {
        @Volatile var running = false
        internal var current = java.lang.ref.WeakReference<PetService>(null)

        fun interactIfRunning(c: Context) {
            if (running) c.startService(Intent(c, PetService::class.java).setAction("interact"))
        }
    }

    private lateinit var wm: WindowManager
    private var root: FrameLayout? = null
    private var host: RenderHost? = null
    private var params: WindowManager.LayoutParams? = null
    private var menu: LinearLayout? = null
    private var hidden = false
    private var through = false
    private var size = 280
    private var screenReceiver: BroadcastReceiver? = null

    override fun onBind(i: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        current = java.lang.ref.WeakReference(this)
        wm = getSystemService(WindowManager::class.java)
        val channel = NotificationChannel("pet", "幻伴桌宠控制", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        screenReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    if (i.action == Intent.ACTION_SCREEN_OFF) host?.pause()
                    else if (!hidden) host?.start()
                }
            }
        if (Build.VERSION.SDK_INT >= 33)
            registerReceiver(
                screenReceiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_SCREEN_ON)
                },
                RECEIVER_NOT_EXPORTED,
            )
        else
            registerReceiver(
                screenReceiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_SCREEN_ON)
                },
            )
    }

    private fun action(label: String, action: String) =
        Notification.Action.Builder(
                null,
                label,
                PendingIntent.getService(
                    this,
                    action.hashCode(),
                    Intent(this, PetService::class.java).setAction(action),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()

    private fun notification(): Notification =
        Notification.Builder(this, "pet")
            .setSmallIcon(com.huanban.ar.R.drawable.ic_pet)
            .setContentTitle("幻伴 3D 桌宠")
            .setContentText(
                if (hidden) "已隐藏 · 点击恢复" else if (through) "触摸穿透中 · 通知恢复控制" else "轻触互动，拖动人物移动"
            )
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .addAction(action("恢复控制", "show"))
            .addAction(action("隐藏", "hide"))
            .addAction(action("关闭", "stop"))
            .build()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!Settings.canDrawOverlays(this)) {
            Ui.toast(this, "悬浮窗权限未开启，桌宠未启动")
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            if (Build.VERSION.SDK_INT >= 34)
                startForeground(7, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(7, notification())
            running = true
            when (intent?.action) {
                "hide" -> hide()
                "interact" -> host?.interact()
                else -> show()
            }
        } catch (e: Exception) {
            android.util.Log.e("PetService", "桌宠启动失败", e)
            Ui.toast(this, "桌宠无法启动：${e.message}")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun show() {
        hidden = false
        through = false
        params?.alpha = 1f
        if (root == null) build()
        root?.visibility = View.VISIBLE
        params?.flags =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        update()
        host?.start()
        getSystemService(NotificationManager::class.java).notify(7, notification())
    }

    private fun hide() {
        hidden = true
        host?.pause()
        root?.visibility = View.GONE
        getSystemService(NotificationManager::class.java).notify(7, notification())
    }

    private fun build() {
        val prefs = getSharedPreferences("pet", 0)
        size = prefs.getInt("size", 280).coerceIn(160, 480)
        val p =
            WindowManager.LayoutParams(
                    Ui.dp(this, size),
                    Ui.dp(this, size + 40),
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT,
                )
                .apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = prefs.getInt("x", 20)
                    y = prefs.getInt("y", 200)
                }
        params = p
        val frame = FrameLayout(this)
        root = frame
        val h = RenderHost(this, transparent = true) { s -> android.util.Log.i("PetService", s) }
        host = h
        frame.addView(h.view, FrameLayout.LayoutParams(-1, Ui.dp(this, size)))
        val bottom = Ui.secondary(this, "幻伴 · 点击菜单") { toggleMenu() }
        frame.addView(bottom, FrameLayout.LayoutParams(-1, Ui.dp(this, 40), Gravity.BOTTOM))
        val panel =
            Ui.column(this).apply {
                background = Ui.round(Ui.panel)
                visibility = View.GONE
                setPadding(Ui.dp(this@PetService, 8), 0, Ui.dp(this@PetService, 8), 0)
            }
        menu = panel
        panel.addView(
            Ui.row(
                this,
                Ui.secondary(this, "放大") { resize(30) },
                Ui.secondary(this, "缩小") { resize(-30) },
                Ui.secondary(this, "换角色") {
                    val store = RoleStore(this)
                    val roles = store.roles()
                    if (roles.isNotEmpty()) {
                        val current = roles.indexOfFirst { it.id == h.role?.id }
                        val r = roles[(current + 1) % roles.size]
                        store.select(r)
                        h.load(r)
                    }
                },
            )
        )
        panel.addView(
            Ui.row(
                this,
                Ui.secondary(this, "挥手") { h.interact() },
                Ui.secondary(this, "开心") { h.expression(true) },
                Ui.secondary(this, "待机") { h.action(0) },
            )
        )
        panel.addView(
            Ui.row(
                this,
                Ui.secondary(this, "穿透") {
                    through = true
                    // Android 12+ rejects touches behind opaque untrusted overlays,
                    // even with FLAG_NOT_TOUCHABLE. Use the system opacity limit.
                    val limit = if (Build.VERSION.SDK_INT >= 31)
                        getSystemService(android.hardware.input.InputManager::class.java).maximumObscuringOpacityForTouch
                        else .8f
                    params?.alpha = minOf(.8f, limit.coerceIn(0f, 1f))
                    panel.visibility = View.GONE
                    params?.flags =
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    update()
                    getSystemService(NotificationManager::class.java).notify(7, notification())
                    Ui.toast(this, "已穿透触摸，从通知选择“恢复控制”")
                },
                Ui.secondary(this, "隐藏") { hide() },
                Ui.secondary(this, "关闭") { stopSelf() },
            )
        )
        frame.addView(panel, FrameLayout.LayoutParams(-1, Ui.dp(this, 170), Gravity.BOTTOM))
        var startX = 0
        var startY = 0
        var downX = 0f
        var downY = 0f
        var moved = false
        h.view.contentDescription = "可拖动的三维桌宠，轻触互动并显示菜单"
        h.view.setOnClickListener {
            h.interact()
            toggleMenu()
        }
        h.view.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = p.x
                    startY = p.y
                    downX = e.rawX
                    downY = e.rawY
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (kotlin.math.abs(dx) + kotlin.math.abs(dy) > Ui.dp(this, 8)) {
                        moved = true
                        p.x = startX + dx.toInt()
                        p.y = startY + dy.toInt()
                        clamp()
                        update()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        h.view.performClick()
                    } else persist()
                }
            }
            true
        }
        clamp()
        wm.addView(frame, p)
        val r = RoleStore(this).selected()
        if (r != null) h.load(r) else Ui.toast(this, "没有可用角色，请先打开应用准备角色库")
        h.start()
    }

    private fun resize(delta: Int) {
        size = (size + delta).coerceIn(160, 480)
        params?.width = Ui.dp(this, size)
        params?.height = Ui.dp(this, size + 40)
        host?.view?.layoutParams = FrameLayout.LayoutParams(-1, Ui.dp(this, size))
        clamp()
        update()
        persist()
    }

    private fun toggleMenu() {
        val m = menu ?: return
        m.visibility = if (m.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    private fun clamp() {
        val p = params ?: return
        val d = resources.displayMetrics
        p.x = p.x.coerceIn(0, (d.widthPixels - p.width).coerceAtLeast(0))
        p.y = p.y.coerceIn(0, (d.heightPixels - p.height - Ui.dp(this, 30)).coerceAtLeast(0))
    }

    private fun update() {
        val r = root ?: return
        val p = params ?: return
        try {
            wm.updateViewLayout(r, p)
        } catch (e: Exception) {
            android.util.Log.e("PetService", "更新窗口失败", e)
            stopSelf()
        }
    }

    private fun persist() {
        getSharedPreferences("pet", 0)
            .edit()
            .putInt("x", params?.x ?: 0)
            .putInt("y", params?.y ?: 0)
            .putInt("size", size)
            .apply()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        clamp()
        update()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (ResourcePolicy.pausePetForMemory(level)) {
            hide()
            Ui.toast(this, "系统内存紧张，桌宠已暂停；可通过通知恢复")
        }
    }

    override fun onDestroy() {
        running = false
        current.clear()
        host?.destroy()
        host = null
        root?.let { runCatching { wm.removeViewImmediate(it) } }
        root = null
        screenReceiver?.let { runCatching { unregisterReceiver(it) } }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
