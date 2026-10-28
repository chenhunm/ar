package com.huanban.ar

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@FixMethodOrder(org.junit.runners.MethodSorters.NAME_ASCENDING)
class AppRegressionTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val context = ins.targetContext
    private val device = UiDevice.getInstance(ins)

    @Before fun prepareDevice() { TestDevice.prepare() }

    private fun tap(text: String) {
        var target: androidx.test.uiautomator.UiObject2? = null
        val end=android.os.SystemClock.elapsedRealtime()+3000
        while(target==null && android.os.SystemClock.elapsedRealtime()<end) {
            target=device.findObject(By.text(text)) ?: device.findObject(By.desc(text))
            if(target==null) Thread.sleep(100)
        }
        if(target==null) target=device.findObject(By.scrollable(true))
            ?.scrollUntil(Direction.DOWN,Until.findObject(By.text(text)))
        assertNotNull("找不到 $text",target)
        target!!.click()
    }

    private fun launch() {
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        assertTrue(device.wait(Until.hasObject(By.desc("AR 模式")), 45000))
    }

    @Test
    fun a_homeNavigationAndSettings() {
        launch()
        tap("应用设置")
        tap("保存设置")
        tap("‹ 返回首页")
        assertTrue(device.wait(Until.hasObject(By.desc("AR 模式")), 10000))
        tap("角色管理")
        assertTrue(device.wait(Until.hasObject(By.text("导入 GLB / VRM 文件")), 10000))
        assertTrue(
            device.hasObject(By.textContains("青禾")) || device.hasObject(By.descContains("青禾"))
        )
    }

    @Test
    fun b_storeImportGlbGltfVrmAndDeletion() {
        val s = RoleStore(context)
        s.ensureBuiltins()
        val b = context.assets.open("models/qing.glb").use { it.readBytes() }
        val glb = s.importBytes("测试导入.glb", b)
        assertTrue(s.file(glb).exists())
        s.update(glb.copy(name = "测试改名", scale = 1.5f, action = 1))
        assertEquals(1.5f, s.roles().first { it.id == glb.id }.scale)
        s.select(glb)
        assertEquals(glb.id, s.selected()?.id)
        val (j, bin) = ModelCodec.unpack(b)
        j.getJSONArray("buffers").getJSONObject(0).put("uri", "test.bin")
        val gltf = s.importBytes("测试目录.gltf", j.toString().toByteArray()) { bin }
        assertTrue(s.file(gltf).exists())
        j.getJSONArray("buffers").getJSONObject(0).remove("uri")
        j.put(
            "extensions",
            org.json.JSONObject().put("VRM", org.json.JSONObject().put("specVersion", "0.0")),
        )
        val vrm = s.importBytes("测试VRM.vrm", ModelCodec.pack(j, bin))
        assertTrue(vrm.warning.contains("VRM"))
        for (r in listOf(glb, gltf, vrm)) s.delete(r)
        assertFalse(s.file(glb).exists())
        s.select(s.roles().first())
    }

    @Test
    fun c_invalidImportsDontPersist() {
        val s = RoleStore(context)
        s.ensureBuiltins()
        val before = s.roles().size
        for (b in listOf(byteArrayOf(), "坏模型".toByteArray(), ByteArray(12))) {
            var rejected = false
            try {
                s.importBytes("坏.glb", b)
            } catch (e: Exception) {
                rejected = true
            }
            assertTrue(rejected)
        }
        assertEquals(before, s.roles().size)
    }

    @Test
    fun d_danceLoadPauseScreenshotAndRepeatedExit() {
        launch()
        repeat(3) {
            tap("舞蹈模式")
            assertTrue(device.wait(Until.hasObject(By.text("选择动作")), 20000))
            assertTrue(device.wait(Until.hasObject(By.textContains("ms")), 45000))
            tap("选择动作")
            assertTrue(device.wait(Until.hasObject(By.text("幻伴·轻舞")), 10000))
            tap("幻伴·轻舞")
            tap("播放 / 暂停")
            tap("播放 / 暂停")
            if (it == 0) {
                tap("截图")
                Thread.sleep(1500)
            }
            device.pressBack()
            assertTrue(device.wait(Until.hasObject(By.desc("AR 模式")), 15000))
        }
    }

    @Test
    fun e_localChatAndKeyEncryption() {
        val engine = ChatEngine(context)
        context
            .getSharedPreferences("settings", 0)
            .edit()
            .remove("apiBase")
            .remove("apiModel")
            .commit()
        engine.saveKey("test-not-real-key")
        assertEquals("test-not-real-key", engine.readKey())
        assertFalse(
            context
                .getSharedPreferences("settings", 0)
                .getString("apiKey", "")!!
                .contains("test-not-real-key")
        )
        engine.saveKey("")
        assertEquals("", engine.readKey())
        assertTrue(engine.respond("你好").startsWith("【本地规则】"))
        engine.add("user", "自动化测试")
        assertTrue(engine.history().length() > 0)
        engine.clear()
        assertEquals(0, engine.history().length())
        launch()
        tap("人物对话")
        device.findObject(By.clazz("android.widget.EditText"))!!.text = "你好"
        tap("发送")
        assertTrue(device.wait(Until.hasObject(By.textContains("【本地规则】")),30000))
        tap("停止朗读")
        tap("‹ 返回首页")
        tap("人物对话")
        assertTrue(device.wait(Until.hasObject(By.textContains("你好，我在这里")),10000))
        // AOSP may lack recognition; Google images may offer an external UI.
        // Cancel any external recognizer and retain the same editable chat.
        tap("语音输入")
        Thread.sleep(500)
        repeat(3) {
            assertFalse("语音入口出现崩溃或 ANR 对话框",
                device.hasObject(By.res("android:id/aerr_close")) ||
                device.hasObject(By.res("android:id/aerr_wait")))
            if (device.currentPackageName != context.packageName) {
                device.pressBack()
                Thread.sleep(250)
            }
        }
        assertTrue(device.wait(Until.hasObject(By.text("发送")),10000))
    }

    @Test
    fun f_petPermissionDenialSurvives() {
        device.executeShellCommand("appops set ${context.packageName} SYSTEM_ALERT_WINDOW deny")
        launch()
        tap("桌宠模式")
        assertTrue(device.wait(Until.hasObject(By.text("启动 / 恢复桌宠")), 10000))
        tap("启动 / 恢复桌宠")
        assertTrue(device.hasObject(By.text("启动 / 恢复桌宠")))
    }

    @Test
    fun g_petRepeatedStartStopAndImportedModel() {
        device.executeShellCommand("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
        val s = RoleStore(context)
        s.ensureBuiltins()
        val r =
            s.importBytes("桌宠测试.glb", context.assets.open("models/momo.glb").use { it.readBytes() })
        s.select(r)
        launch()
        tap("桌宠模式")
        try {
            repeat(3) { cycle ->
                tap("启动 / 恢复桌宠")
                if(android.os.Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    val allow=device.wait(Until.findObject(By.res(java.util.regex.Pattern.compile(".*:id/permission_allow_button"))),10000)
                    assertNotNull("未出现通知授权",allow);allow!!.click()
                }
                assertTrue(device.wait(Until.hasObject(By.text("幻伴 · 点击菜单")), 30000))
                tap("幻伴 · 点击菜单")
                assertTrue(device.wait(Until.hasObject(By.text("放大")), 10000))
                tap("放大")
                if (cycle == 0) {
                    device.pressHome()
                    assertTrue(device.wait(Until.hasObject(By.text("幻伴 · 点击菜单")), 10000))
                    launch()
                }
                tap("关闭")
                if (cycle == 0) tap("桌宠模式")
                Thread.sleep(500)
            }
        } finally {
            context.stopService(Intent(context,PetService::class.java))
            val end=android.os.SystemClock.elapsedRealtime()+45000
            while(PetService.current.get()!=null && android.os.SystemClock.elapsedRealtime()<end) Thread.sleep(100)
            assertNull("桌宠清理未完成",PetService.current.get())
            s.select(s.roles().first{it.id=="qing"});s.delete(r)
        }
    }

    @Test
    fun h_safStreamsAndTreeResources() {
        val store = RoleStore(context)
        store.ensureBuiltins()
        val authority = "com.huanban.ar.test.fixtures"
        device.executeShellCommand(
            // This fixture finishes inside onCreate and never draws a frame.
            // `am start -W` can wait forever for that nonexistent first draw.
            // Start without -W; the bounded URI permission poll below is the
            // actual readiness condition for the DocumentsProvider.
            "am start -n com.huanban.ar.test/com.huanban.ar.FixtureGrantActivity"
        )
        val grants = listOf("fixture.glb","fixture.vrm").map {
            android.provider.DocumentsContract.buildDocumentUri(authority,it)
        } + android.provider.DocumentsContract.buildTreeDocumentUri(authority,"root")
        val deadline=android.os.SystemClock.elapsedRealtime()+45000
        while(grants.any { context.checkUriPermission(it,android.os.Process.myPid(),android.os.Process.myUid(),Intent.FLAG_GRANT_READ_URI_PERMISSION)!=android.content.pm.PackageManager.PERMISSION_GRANTED } && android.os.SystemClock.elapsedRealtime()<deadline) Thread.sleep(200)
        assertTrue("文件提供器授权进程未完成",grants.all { context.checkUriPermission(it,android.os.Process.myPid(),android.os.Process.myUid(),Intent.FLAG_GRANT_READ_URI_PERMISSION)==android.content.pm.PackageManager.PERMISSION_GRANTED })
        val glb =
            store.importFile(
                android.provider.DocumentsContract.buildDocumentUri(authority, "fixture.glb")
            )
        val vrm =
            store.importFile(
                android.provider.DocumentsContract.buildDocumentUri(authority, "fixture.vrm")
            )
        val gltf =
            store.importTree(
                android.provider.DocumentsContract.buildTreeDocumentUri(authority, "root")
            )
        assertTrue(glb.animations.isNotEmpty())
        assertTrue(vrm.warning.contains("VRM"))
        assertTrue(gltf.animations.isNotEmpty())
        for (r in listOf(glb, vrm, gltf)) store.delete(r)
    }

    @Test
    fun i_arFallbackCameraAndDeniedPermission() {
        device.executeShellCommand("pm revoke ${context.packageName} android.permission.CAMERA")
        launch()
        tap("AR 模式")
        val deny =
            device.wait(
                Until.findObject(
                    By.res(java.util.regex.Pattern.compile(".*:id/permission_deny_button"))
                ),
                10000,
            )
        deny?.click()
        assertTrue(device.wait(Until.hasObject(By.desc("AR 模式")), 10000))
        device.executeShellCommand("pm grant ${context.packageName} android.permission.CAMERA")
        context.startActivity(
            Intent(context, StageActivity::class.java)
                .putExtra("mode", "ar")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        assertTrue(device.wait(Until.hasObject(By.text("追踪 / 叠加")), 30000))
        tap("追踪 / 叠加")
        tap("摄像头叠加（无空间锚定）")
        assertTrue(device.wait(Until.hasObject(By.textContains("无空间锚定")), 45000))
        Thread.sleep(2000)
        tap("截图")
        Thread.sleep(1500)
        device.pressHome()
        Thread.sleep(1000)
        launch()
    }

    @Test
    fun j_videoRecordingCreatesDecodableFile() {
        val startedAfter = System.currentTimeMillis()/1000
        launch()
        tap("舞蹈模式")
        assertTrue(device.wait(Until.hasObject(By.textContains("ms")), 45000))
        tap("录制视频")
        if(android.os.Build.VERSION.SDK_INT >= 34) {
            val modes=device.wait(Until.findObject(By.res(java.util.regex.Pattern.compile("com.android.systemui:id/screen_share_mode_(options|spinner)"))),10000)
            assertNotNull("未出现录屏范围选择",modes);modes!!.click()
            val entire=device.wait(Until.findObject(By.text(java.util.regex.Pattern.compile("(?i).*(entire screen|整个屏幕|整个画面).*"))),5000)
            assertNotNull("未找到整个屏幕选项",entire);entire!!.click()
        }
        val approve = device.wait(Until.findObject(By.res("android:id/button1")), 15000)
        assertNotNull("未出现系统录屏授权", approve)
        approve!!.click()
        val deadline = System.currentTimeMillis() + 20000
        while (!RecordingService.running && System.currentTimeMillis() < deadline) Thread.sleep(250)
        assertTrue(RecordingService.running)
        Thread.sleep(3500)
        context.stopService(Intent(context, RecordingService::class.java))
        Thread.sleep(2500)
        assertFalse(RecordingService.running)
        val uri = android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val end = android.os.SystemClock.elapsedRealtime()+30000
        var finalized = false
        while (!finalized && android.os.SystemClock.elapsedRealtime()<end) {
            context.contentResolver.query(uri,arrayOf("_size","is_pending"),"_display_name LIKE ? AND date_added >= ?",arrayOf("HuanBan_%",startedAfter.toString()),"date_added DESC")?.use { cursor ->
                finalized = cursor.moveToFirst() && cursor.getLong(0)>1000 && cursor.getInt(1)==0
            }
            if(!finalized) Thread.sleep(200)
        }
        assertTrue("新录制视频未完成保存",finalized)
        context.contentResolver
            .query(
                uri,
                arrayOf("_id", "_size"),
                "_display_name LIKE ? AND date_added >= ?",
                arrayOf("HuanBan_%",startedAfter.toString()),
                "date_added DESC",
            )!!
            .use { c ->
                assertTrue("未生成视频", c.moveToFirst())
                assertTrue(c.getLong(1) > 1000)
                val video = android.content.ContentUris.withAppendedId(uri, c.getLong(0))
                val retriever = android.media.MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, video)
                    assertTrue(
                        retriever
                            .extractMetadata(
                                android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
                            )!!
                            .toLong() > 0
                    )
                    val frame=retriever.getFrameAtTime(0)
                    assertNotNull("视频无法解码出图像",frame)
                    var colored=0
                    for(y in 0 until frame!!.height step 8)for(x in 0 until frame.width step 8)if(android.graphics.Color.red(frame.getPixel(x,y))+android.graphics.Color.green(frame.getPixel(x,y))+android.graphics.Color.blue(frame.getPixel(x,y))>80) colored++
                    assertTrue("录制视频画面全黑",colored>50)
                    java.io.File(context.filesDir,"qa-video.png").outputStream().use { frame.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
                    frame.recycle()
                } finally {
                    retriever.release()
                }
            }
        device.pressBack()
        assertTrue(device.wait(Until.hasObject(By.desc("AR 模式")),15000))
        ins.waitForIdleSync()
    }

    @Test
    fun k_concurrentImportsKeepBothRoles() {
        val store = RoleStore(context)
        store.ensureBuiltins()
        val bytes = context.assets.open("models/qing.glb").use { it.readBytes() }
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val futures =
                pool.invokeAll(
                    listOf(
                        java.util.concurrent.Callable {
                            RoleStore(context).importBytes("并发一.glb", bytes)
                        },
                        java.util.concurrent.Callable {
                            RoleStore(context).importBytes("并发二.glb", bytes)
                        },
                    )
                )
            val imported = futures.map { it.get() }
            val ids = store.roles().map { it.id }
            assertTrue(imported.all { it.id in ids })
            imported.forEach { store.delete(it) }
        } finally {
            pool.shutdownNow()
        }
    }
    @Test fun m_unreachableAiRestoresInteractiveChat() {
        val prefs=context.getSharedPreferences("settings",0)
        val oldBase=prefs.getString("apiBase",null)
        val oldModel=prefs.getString("apiModel",null)
        try {
            prefs.edit().putString("apiBase","https://127.0.0.1:9/v1").putString("apiModel","offline-test").commit()
            launch()
            tap("人物对话")
            device.findObject(By.clazz("android.widget.EditText"))!!.text="网络中断测试"
            tap("发送")
            assertTrue(device.wait(Until.gone(By.text("回复中…")),15000))
            assertTrue(device.hasObject(By.text("发送")))
            tap("‹ 返回首页")
            assertTrue(device.wait(Until.hasObject(By.desc("AR 模式")),10000))
        } finally { prefs.edit().putString("apiBase",oldBase).putString("apiModel",oldModel).commit() }
    }

    @Test fun l_builtinUpdatePreservesRolePreferences() {
        val store=RoleStore(context);store.ensureBuiltins()
        val original=store.roles().first{it.id=="qing"}
        try {
            store.update(original.copy(name="保留自定义名称",scale=1.25f,action=2))
            context.getSharedPreferences("settings",0).edit().putString("builtinRevision.qing","obsolete").commit()
            store.ensureBuiltins()
            val updated=store.roles().first{it.id=="qing"}
            assertEquals("保留自定义名称",updated.name);assertEquals(1.25f,updated.scale);assertEquals(2,updated.action)
            assertTrue(store.file(updated).length()>0)
        } finally {store.update(original)}
    }

    @Test fun n_androidTextureMimeUsesNativeTokens() {
        val json=org.json.JSONObject().put("images",org.json.JSONArray().put(org.json.JSONObject().put("mimeType","image/png"))).put("name",("衣料"+92.toChar()+"/角色"))
        val bytes=ModelCodec.pack(json,byteArrayOf(1,2,3,4))
        val h=java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val raw=String(bytes,20,h.getInt(12),Charsets.UTF_8)
        assertTrue(raw.contains("image/png"));assertFalse(raw.contains("image"+92.toChar()+"/png"))
        assertEquals(("衣料"+92.toChar()+"/角色"),ModelCodec.unpack(bytes).first.getString("name"))
    }
}
