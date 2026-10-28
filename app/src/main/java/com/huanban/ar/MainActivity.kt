package com.huanban.ar

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var store: RoleStore
    private var initialized = false
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var chatInput: EditText? = null
    private var page = "home"
    private val prefs by lazy { getSharedPreferences("settings", 0) }
    private val filePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null)
                work("导入模型", { store.importFile(uri) }) { r ->
                    store.select(r)
                    roles()
                    Ui.toast(
                        this,
                        "已导入 ${r.name}${if(r.warning.isNotBlank()) "\n${r.warning}" else ""}",
                    )
                }
        }
    private val treePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null)
                work("归档 GLTF 关联资源", { store.importTree(uri) }) { r ->
                    store.select(r)
                    roles()
                    Ui.toast(this, "已导入 ${r.name}")
                }
        }
    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            if (ok) stage("ar") else Ui.toast(this, "摄像头权限未授予，可在设置中开启；其他模式仍可使用")
        }
    private val notifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            Ui.toast(this, if (it) "通知权限已开启" else "通知未授权，前台服务仍可在系统活动应用中关闭")
        }
    private val speech =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let {
                chatInput?.setText(it)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(
            this,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (page != "home") home() else finish()
                }
            },
        )
        store = RoleStore(this)
        val col = Ui.column(this)
        col.addView(Ui.title(this, "幻伴 AR"))
        col.addView(Ui.text(this, "正在准备内置角色…"))
        Ui.insets(this, Ui.scroll(this, col))
        work("准备角色库", { store.ensureBuiltins() }) {
            initialized = true
            home()
        }
        tts =
            TextToSpeech(this) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                if (ttsReady)
                    ttsReady =
                        (tts?.setLanguage(Locale.SIMPLIFIED_CHINESE)
                            ?: TextToSpeech.LANG_NOT_SUPPORTED) >= 0
            }
    }

    override fun onResume() {
        super.onResume()
        if (initialized && page == "home") home()
    }

    private fun layout(title: String): LinearLayout {
        val col = Ui.column(this)
        if (title != "幻伴 AR") col.addView(Ui.secondary(this, "‹ 返回首页") { home() })
        col.addView(Ui.title(this, title))
        Ui.insets(this, Ui.scroll(this, col))
        return col
    }

    private fun <T> work(label: String, task: () -> T, done: (T) -> Unit) {
        val dialog = AlertDialog.Builder(this).setMessage("$label…").setCancelable(false).create()
        dialog.show()
        io.execute {
            val result = runCatching { task() }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                dialog.dismiss()
                result.fold(done) {
                    Ui.toast(this, "$label 失败：${it.message}")
                    android.util.Log.e("HuanBan", label, it)
                }
            }
        }
    }

    fun home() {
        page = "home"
        val col = layout("幻伴 AR")
        col.addView(Ui.text(this, "HUANBAN · 让陪伴走进生活", 13f, Ui.mint))
        col.addView(Ui.text(this, "当前角色：${store.selected()?.name ?: "尚无角色"}", 17f))
        col.addView(
            Ui.card(this, "AR 模式", "打开摄像头，将角色带进现实；支持设备启用空间追踪。") {
                if (
                    checkSelfPermission(Manifest.permission.CAMERA) ==
                        PackageManager.PERMISSION_GRANTED
                )
                    stage("ar")
                else cameraPermission.launch(Manifest.permission.CAMERA)
            }
        )
        col.addView(Ui.card(this, "桌宠模式", "透明实时 3D 悬浮窗 · 可拖动、缩放、互动") { pet() })
        col.addView(Ui.card(this, "舞蹈模式", "骨骼舞蹈与动作 · 视角控制 · 截图与录制") { stage("dance") })
        col.addView(Ui.card(this, "角色管理", "内置角色与 GLB / GLTF / VRM 导入、预览和编辑") { roles() })
        col.addView(Ui.card(this, "人物对话", "文字、语音输入与朗读 · 本地规则 / 自选 AI 接口") { chat() })
        col.addView(Ui.secondary(this, "应用设置") { settings() })
        col.addView(Ui.text(this, "内置模型离线可用 · 无广告 · 无需账号", 12f, Ui.muted))
    }

    private fun stage(mode: String) {
        startActivity(Intent(this, StageActivity::class.java).putExtra("mode", mode))
    }

    private fun pet() {
        page = "pet"
        val col = layout("桌宠模式")
        col.addView(Ui.text(this, "悬浮窗状态：${if(Settings.canDrawOverlays(this)) "已授权" else "未授权"}"))
        col.addView(
            Ui.text(this, "拖动人物移动位置；轻触显示菜单。菜单可切换角色、调整大小、穿透触摸、隐藏或关闭。隐藏和穿透后可通过通知恢复控制。", 15f, Ui.muted)
        )
        col.addView(
            Ui.button(this, "开启悬浮窗权限") {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName"),
                    )
                )
            }
        )
        col.addView(
            Ui.button(this, "启动 / 恢复桌宠") {
                if (!Settings.canDrawOverlays(this)) {
                    Ui.toast(this, "请先开启悬浮窗权限")
                    return@button
                }
                try {
                    startForegroundService(Intent(this, PetService::class.java).setAction("show"))
                    if (
                        Build.VERSION.SDK_INT >= 33 &&
                            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                                PackageManager.PERMISSION_GRANTED
                    )
                        notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                } catch (e: Exception) {
                    Ui.toast(this, "启动失败：${e.message}")
                }
            }
        )
        col.addView(
            Ui.secondary(this, "关闭桌宠") {
                stopService(Intent(this, PetService::class.java))
                Ui.toast(this, "桌宠已关闭")
            }
        )
        col.addView(
            Ui.secondary(this, "系统电池设置") {
                try {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (e: Exception) {
                    Ui.toast(this, "系统未提供该页面")
                }
            }
        )
        col.addView(Ui.text(this, "HyperOS 若主动清理后台服务，需要在系统应用管理中允许本应用后台运行。", 14f, Ui.muted))
    }

    private fun roles() {
        page = "roles"
        val col = layout("角色管理")
        col.addView(Ui.button(this, "导入 GLB / VRM 文件") { filePicker.launch(arrayOf("*/*")) })
        col.addView(Ui.secondary(this, "导入 GLTF 及关联资源目录") { treePicker.launch(null) })
        col.addView(
            Ui.text(
                this,
                "单个模型及关联资源合计 ≤64 MB；纹理 ≤4096²、总像素 ≤3200 万；顶点 ≤75 万。VRM 支持基础 PBR 显示与可识别的表情，不含 MToon、头发物理与布料碰撞。",
                13f,
                Ui.muted,
            )
        )
        for (r in store.roles()) {
            col.addView(
                Ui.card(
                    this,
                    "${if(store.selected()?.id==r.id) "✓ " else ""}${r.name}",
                    "${if(r.builtin) "内置原创角色" else "导入角色"} · ${r.animations.size} 个动作${if(r.warning.isBlank()) "" else "\n${r.warning}"}",
                ) {
                    roleDialog(r)
                }
            )
        }
    }

    private fun roleDialog(role: Role) {
        AlertDialog.Builder(this)
            .setTitle(role.name)
            .setItems(arrayOf("设为当前角色", "3D 预览", "编辑名称 / 比例 / 默认动作", "删除角色")) { _, i ->
                when (i) {
                    0 -> {
                        store.select(role)
                        roles()
                    }
                    1 -> {
                        store.select(role)
                        stage("preview")
                    }
                    2 -> editRole(role)
                    3 -> {
                        if (role.builtin) Ui.toast(this, "保留两个内置角色以确保离线可用")
                        else
                            AlertDialog.Builder(this)
                                .setMessage("删除 ${role.name}？")
                                .setPositiveButton("删除") { _, _ ->
                                    store.delete(role)
                                    roles()
                                }
                                .setNegativeButton("取消", null)
                                .show()
                    }
                }
            }
            .show()
    }

    private fun editRole(r: Role) {
        val col = Ui.column(this)
        val name = Ui.edit(this, "角色名称", r.name)
        col.addView(name)
        var scale = r.scale
        var action = r.action
        col.addView(
            Ui.slider(this, "显示比例", 290, ((scale - .1f) * 100).toInt()) { scale = .1f + it / 100f }
        )
        val actions = Spinner(this)
        val list = if (r.animations.isEmpty()) listOf("无骨骼动作") else r.animations
        actions.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, list)
        actions.setSelection(action.coerceIn(list.indices))
        col.addView(actions)
        val d =
            AlertDialog.Builder(this)
                .setTitle("角色设置")
                .setView(Ui.scroll(this, col))
                .setPositiveButton("保存", null)
                .setNegativeButton("取消", null)
                .create()
        d.setOnShowListener {
            d.getButton(-1).setOnClickListener {
                val n = name.text.toString().trim()
                if (n.isBlank() || n.length > 40) {
                    name.error = "请输入 1～40 字名称"
                    return@setOnClickListener
                }
                action = actions.selectedItemPosition
                store.update(r.copy(name = n, scale = scale, action = action))
                d.dismiss()
                roles()
            }
        }
        d.show()
    }

    private fun settings() {
        page = "settings"
        val col = layout("应用设置")
        col.addView(Ui.text(this, "渲染与声音", 20f, Ui.mint))
        val fps = Spinner(this)
        fps.adapter =
            ArrayAdapter(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("节能 15 FPS", "均衡 30 FPS", "流畅 60 FPS"),
            )
        fps.setSelection(listOf(15, 30, 60).indexOf(prefs.getInt("fps", 30)).coerceAtLeast(0))
        col.addView(fps)
        val quality = Spinner(this)
        quality.adapter =
            ArrayAdapter(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("均衡：关闭额外阴影", "高画质：阴影与环境遮蔽"),
            )
        quality.setSelection(if (prefs.getString("quality", "balanced") == "high") 1 else 0)
        col.addView(quality)
        var volume = prefs.getInt("volume", 80)
        col.addView(Ui.slider(this, "角色语音音量", 100, volume) { volume = it })
        col.addView(Ui.text(this, "角色性格", 20f, Ui.mint))
        val personality =
            Ui.edit(this, "性格描述", prefs.getString("personality", "温和、简洁、关心学习与生活").orEmpty())
        col.addView(personality)
        col.addView(Ui.text(this, "AI 接口（可留空）", 20f, Ui.mint))
        col.addView(
            Ui.text(
                this,
                "兼容 OpenAI Chat Completions。填写供应商 HTTPS 基础地址（通常以 /v1 结尾）。配置后，对话会发送到你选择的服务；API 密钥在设备中加密保存。留空时明确标注为本地规则。",
                13f,
                Ui.muted,
            )
        )
        val base = Ui.edit(this, "https://服务地址/v1", prefs.getString("apiBase", "").orEmpty())
        val model = Ui.edit(this, "模型名称", prefs.getString("apiModel", "").orEmpty())
        val key = Ui.edit(this, "新 API Key（留空保留）", "", true)
        col.addView(base)
        col.addView(model)
        col.addView(key)
        col.addView(
            Ui.button(this, "保存设置") {
                val endpoint = base.text.toString().trim()
                if (
                    endpoint.isNotBlank() &&
                        runCatching {
                                java.net.URL(endpoint).let {
                                    it.protocol == "https" &&
                                        it.host.isNotBlank() &&
                                        it.userInfo == null
                                }
                            }
                            .getOrDefault(false)
                            .not()
                ) {
                    base.error = "请输入有效的 HTTPS 地址"
                    return@button
                }
                try {
                    if (key.text.isNotBlank()) ChatEngine(this).saveKey(key.text.toString())
                    prefs
                        .edit()
                        .putInt("fps", listOf(15, 30, 60)[fps.selectedItemPosition])
                        .putString(
                            "quality",
                            if (quality.selectedItemPosition == 1) "high" else "balanced",
                        )
                        .putInt("volume", volume)
                        .putString("personality", personality.text.toString().take(300))
                        .putString("apiBase", endpoint)
                        .putString("apiModel", model.text.toString().trim())
                        .apply()
                    Ui.toast(this, "已保存，渲染设置下次进入场景生效")
                } catch (e: Exception) {
                    Ui.toast(this, "保存失败：${e.message}")
                }
            }
        )
        col.addView(
            Ui.secondary(this, "清除 AI 接口与密钥") {
                ChatEngine(this).saveKey("")
                prefs.edit().remove("apiBase").remove("apiModel").apply()
                settings()
            }
        )
        col.addView(
            Ui.secondary(this, "系统权限管理") {
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName"),
                    )
                )
            }
        )
        col.addView(
            Ui.secondary(this, "清空本地对话记录") {
                AlertDialog.Builder(this)
                    .setMessage("清空全部本地对话？")
                    .setPositiveButton("清空") { _, _ ->
                        ChatEngine(this).clear()
                        Ui.toast(this, "已清空")
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        )
    }

    private fun chat() {
        page = "chat"
        val col = layout("人物对话")
        col.addView(Ui.text(this, "未配置接口时使用本地规则；语音功能取决于系统识别和 TTS 服务。", 13f, Ui.muted))
        val engine = ChatEngine(this)
        val h = engine.history()
        for (i in maxOf(0, h.length() - 30) until h.length()) {
            val m = h.getJSONObject(i)
            col.addView(
                Ui.text(
                    this,
                    (if (m.getString("role") == "user") "你：" else "幻伴：") + m.getString("content"),
                    15f,
                    if (m.getString("role") == "user") Ui.mint else android.graphics.Color.WHITE,
                )
            )
        }
        val input = Ui.edit(this, "说点什么…")
        input.setSingleLine(false)
        chatInput = input
        col.addView(input)
        col.addView(
            Ui.row(
                this,
                Ui.button(this, "发送") {
                    val s = input.text.toString().trim()
                    if (s.isBlank()) return@button
                    if (s.length > 2000) {
                        input.error = "单次输入不超过 2000 字"
                        return@button
                    }
                    engine.add("user", s)
                    work("回复中", { engine.respond(s) }) { reply ->
                        engine.add("assistant", reply)
                        chat()
                        speak(reply)
                        PetService.interactIfRunning(this)
                    }
                },
                Ui.secondary(this, "语音输入") {
                    try {
                        speech.launch(
                            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                                .putExtra(
                                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                                )
                                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                                .putExtra(RecognizerIntent.EXTRA_PROMPT, "对幻伴说话")
                        )
                    } catch (e: Exception) {
                        Ui.toast(this, "系统没有可用语音识别服务，请使用文字输入")
                    }
                },
            )
        )
        col.addView(Ui.secondary(this, "停止朗读") { tts?.stop() })
    }

    private fun speak(s: String) {
        if (!ttsReady) {
            Ui.toast(this, "系统中文语音合成不可用，回复已显示为文字")
            return
        }
        val b =
            Bundle().apply {
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, prefs.getInt("volume", 80) / 100f)
            }
        tts?.speak(s.substringAfter('】'), TextToSpeech.QUEUE_FLUSH, b, "reply")
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        io.shutdownNow()
        super.onDestroy()
    }
}
