package com.huanban.ar

import android.content.Intent
import android.graphics.Bitmap
import android.os.*
import android.view.*
import android.widget.Button
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith

/** Real Filament surfaces and WindowManager, without depending on SystemUI accessibility focus. */
@RunWith(AndroidJUnit4::class)
class NativeIntegrationTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val context = ins.targetContext
    private val device = UiDevice.getInstance(ins)

    @Before fun prepareDevice() { TestDevice.prepare() }

    private fun waitFor(label: String, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 45000
        while (SystemClock.elapsedRealtime() < end) {
            if (condition()) return
            Thread.sleep(200)
        }
        fail("等待超时：$label")
    }

    private fun field(owner: Any, name: String): Any? =
        owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)

    private fun stage(mode: String): StageActivity {
        val store = RoleStore(context)
        store.ensureBuiltins()
        store.select(store.roles().first { it.id == "qing" })
        val activity =
            ins.startActivitySync(
                Intent(context, StageActivity::class.java)
                    .putExtra("mode", mode)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ) as StageActivity
        waitFor("模型加载") { (field(activity, "host") as? RenderHost)?.node != null }
        Thread.sleep(1500)
        return activity
    }

    private fun closeStage(activity: StageActivity) {
        ins.runOnMainSync { activity.finish() }
        waitFor("场景完整释放") { activity.isDestroyed && field(activity,"host") == null }
        ins.waitForIdleSync()
    }

    private fun capture(host: RenderHost, name: String): Bitmap {
        waitFor("Surface 尺寸") { host.view.width > 0 && host.view.height > 0 }
        val b = Bitmap.createBitmap(host.view.width, host.view.height, Bitmap.Config.ARGB_8888)
        val latch = CountDownLatch(1)
        var result = -1
        ins.runOnMainSync {
            FrameCapture.copy(
                host.view,
                b,
                {
                    result = it
                    latch.countDown()
                },
            )
        }
        assertTrue("PixelCopy 超时", latch.await(45, TimeUnit.SECONDS))
        if(result!=PixelCopy.SUCCESS)device.takeScreenshot(File(context.filesDir,"failure-"+name))
        assertEquals(PixelCopy.SUCCESS, result)
        File(context.filesDir, name).outputStream().use {
            b.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        var skin = 0
        for (y in 0 until b.height step 2) for (x in 0 until b.width step 2) {
            val c = b.getPixel(x, y)
            val r = android.graphics.Color.red(c)
            val g = android.graphics.Color.green(c)
            val blue = android.graphics.Color.blue(c)
            if (r > 40 && r > g + 2 && g > blue + 5) skin++
        }
        assertTrue("未检测到真实人物皮肤网格像素：$skin", skin > 20)
        return b
    }

    @Test
    fun skeletalStageActuallyRendersAndAnimates() {
        val prefs=context.getSharedPreferences("settings",0)
        val quality=prefs.getString("quality","balanced")
        prefs.edit().putString("quality","high").commit()
        val activity = stage("dance")
        try {
            val host = field(activity, "host") as RenderHost
            assertTrue(host.node!!.animationCount >= 7)
            val before = capture(host, "qa-stage-idle.png")
            val position = host.node!!.position
            ins.runOnMainSync {
                host.action(host.role!!.animations.indexOfFirst { it.contains("挥手") })
            }
            Thread.sleep(2000)
            val after = capture(host, "qa-stage-wave.png")
            assertEquals(position, host.node!!.position)
            var changed = 0
            for (y in 0 until before.height step 2) for (x in 0 until before.width step 2) if (
                before.getPixel(x, y) != after.getPixel(x, y)
            )
                changed++
            assertTrue("切换骨骼动作后图像未变化", changed > 30)
            File(context.filesDir, "qa-native-metrics.txt")
                .appendText(
                    "stage PSS_kB=${Debug.getPss()} nativeBytes=${Debug.getNativeHeapAllocatedSize()} changedPixels=$changed\n"
                )
            before.recycle()
            after.recycle()
        } finally {
            closeStage(activity)
            prefs.edit().putString("quality",quality).commit()
        }
    }

    @Test
    fun transparentPetRendersDragsResizesAndStops() {
        device.executeShellCommand("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
        context.getSharedPreferences("pet",0).edit().clear().commit()
        val activity = stage("preview")
        val store=RoleStore(context)
        val imported=store.importBytes("集成测试导入角色.glb",context.assets.open("models/momo.glb").use{it.readBytes()})
        store.select(imported)
        try {
            repeat(2) {
                ins.runOnMainSync {
                    context.startForegroundService(
                        Intent(context, PetService::class.java).setAction("show")
                    )
                }
                waitFor("桌宠模型") {
                    PetService.current.get()?.let {
                        (field(it, "host") as? RenderHost)?.node != null
                    } == true
                }
                val service = PetService.current.get()!!
                val host = field(service, "host") as RenderHost
                Thread.sleep(1500)
                val b = capture(host, "qa-pet.png")
                var transparent = 0
                for (y in 0 until b.height step 4) for (x in 0 until b.width step 4) if (
                    android.graphics.Color.alpha(b.getPixel(x, y)) == 0
                )
                    transparent++
                assertTrue("桌宠背景不透明", transparent > 50)
                for(y in 0..8)for(x in 0..8)assertEquals("透明背景存在噪点",0,android.graphics.Color.alpha(b.getPixel(x,y)))
                b.recycle()
                val p = field(service, "params") as WindowManager.LayoutParams
                val oldX = p.x
                ins.runOnMainSync {
                    val t = SystemClock.uptimeMillis()
                    for ((action, xy) in
                        listOf(
                            MotionEvent.ACTION_DOWN to 30f,
                            MotionEvent.ACTION_MOVE to 90f,
                            MotionEvent.ACTION_UP to 90f,
                        )) {
                        val e = MotionEvent.obtain(t, t + 10, action, xy, xy, 0)
                        host.view.dispatchTouchEvent(e)
                        e.recycle()
                    }
                }
                assertTrue("桌宠没有拖动", p.x != oldX)
                val root = field(service, "root") as ViewGroup
                fun button(v: View, label: String = "放大"): Button? {
                    if (v is Button && v.text.toString() == label) return v
                    if (v is ViewGroup)
                        for (i in 0 until v.childCount) button(v.getChildAt(i), label)?.let {
                            return it
                        }
                    return null
                }
                val width = p.width
                ins.runOnMainSync { button(root)!!.performClick() }
                assertTrue(p.width > width)
                ins.runOnMainSync { button(root,"穿透")!!.performClick() }
                assertTrue(p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
                assertTrue(p.alpha <= .8f)
                ins.runOnMainSync { context.startService(Intent(context,PetService::class.java).setAction("show")) }
                waitFor("穿透后恢复控制") { field(service,"through") == false }
                assertEquals(1f,p.alpha)
                ins.runOnMainSync { service.onTrimMemory(20) }
                assertFalse(field(service, "hidden") as Boolean)
                ins.runOnMainSync {
                    context.startService(Intent(context, PetService::class.java).setAction("hide"))
                }
                waitFor("隐藏桌宠") { field(service, "hidden") == true }
                ins.runOnMainSync {
                    context.startService(Intent(context, PetService::class.java).setAction("show"))
                }
                waitFor("恢复桌宠") { field(service, "hidden") == false }
                ins.runOnMainSync { context.stopService(Intent(context, PetService::class.java)) }
                waitFor("关闭桌宠") { !PetService.running && PetService.current.get() == null && field(service,"host") == null }
            }
        } finally {
            ins.runOnMainSync {
                context.stopService(Intent(context, PetService::class.java))
                activity.finish()
            }
            waitFor("预览场景释放") { activity.isDestroyed && field(activity,"host") == null }
            store.select(store.roles().first{it.id=="qing"})
            store.delete(imported)
        }
    }

    @Test
    fun rotationRetainsLoadedSceneAndUsableViewport() {
        val activity = stage("dance")
        try {
            val host = field(activity, "host") as RenderHost
            device.setOrientationLeft()
            waitFor("横屏布局") {
                val canvas = field(activity, "canvas") as View
                activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE &&
                    (field(activity,"root") as View).let { it.width > it.height } &&
                    canvas.height > 100 && host.view.width == canvas.width && host.view.height == canvas.height
            }
            assertSame(host, field(activity, "host"))
            Thread.sleep(700)
            val image = capture(host, "qa-landscape.png")
            image.recycle()
            device.setOrientationNatural()
            waitFor("竖屏布局") { activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT }
            assertSame(host, field(activity, "host"))
        } finally {
            device.setOrientationNatural()
            device.unfreezeRotation()
            closeStage(activity)
        }
    }

    @Test
    fun importedGltfAndVrmActuallyRender() {
        val activity = stage("preview")
        val store = RoleStore(context)
        val imported = mutableListOf<Role>()
        try {
            val raw = context.assets.open("models/qing.glb").use { it.readBytes() }
            val (gltf, bin) = ModelCodec.unpack(raw)
            val uv = java.nio.ByteBuffer.allocate(24*2*4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            repeat(6) { for (coordinate in floatArrayOf(0f,0f,1f,0f,1f,1f,0f,1f)) uv.putFloat(coordinate) }
            val meshBytes = bin + uv.array()
            val views = gltf.getJSONArray("bufferViews")
            val uvView = views.length()
            views.put(org.json.JSONObject().put("buffer",0).put("byteOffset",bin.size).put("byteLength",uv.array().size))
            val accessors = gltf.getJSONArray("accessors")
            val uvAccessor = accessors.length()
            accessors.put(org.json.JSONObject().put("bufferView",uvView).put("componentType",5126).put("count",24).put("type","VEC2"))
            val primitives = gltf.getJSONArray("meshes").getJSONObject(0).getJSONArray("primitives")
            for(i in 0 until primitives.length()) primitives.getJSONObject(i).getJSONObject("attributes").put("TEXCOORD_0",uvAccessor)
            val texture = Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888)
            texture.setPixels(intArrayOf(0xffff00ff.toInt(),0xff00ffff.toInt(),0xff00ffff.toInt(),0xffff00ff.toInt()),0,2,0,0,2,2)
            val png = java.io.ByteArrayOutputStream().also { texture.compress(Bitmap.CompressFormat.PNG,100,it) }.toByteArray()
            texture.recycle()
            gltf.put("images",org.json.JSONArray().put(org.json.JSONObject().put("uri","textures/衣料.png")))
            gltf.put("textures",org.json.JSONArray().put(org.json.JSONObject().put("source",0)))
            gltf.getJSONArray("materials").getJSONObject(0).getJSONObject("pbrMetallicRoughness")
                .put("baseColorFactor",org.json.JSONArray(listOf(1f,1f,1f,1f)))
                .put("baseColorTexture",org.json.JSONObject().put("index",0))
            gltf.getJSONArray("buffers").getJSONObject(0).put("uri", "person.bin").put("byteLength",meshBytes.size)
            imported += store.importBytes("关联资源人物.gltf", gltf.toString().toByteArray()) { path ->
                when(path) { "person.bin" -> meshBytes; "textures/衣料.png" -> png; else -> error("错误关联路径 $path") }
            }
            for (version in 0..1) {
                val (json, data) = ModelCodec.unpack(raw)
                val human = org.json.JSONObject()
                val legacy = org.json.JSONArray()
                val nodes = json.getJSONArray("nodes")
                for (i in 0 until nodes.length()) {
                    val name = nodes.getJSONObject(i).optString("name")
                    val bone = when {
                        name.endsWith("Left") -> "left" + name.removeSuffix("Left").replaceFirstChar { it.uppercaseChar() }
                        name.endsWith("Right") -> "right" + name.removeSuffix("Right").replaceFirstChar { it.uppercaseChar() }
                        else -> name
                    }
                    if (bone in listOf("hips", "spine", "chest", "neck", "head") ||
                        bone.matches(Regex("(left|right)(UpperArm|LowerArm|Hand|UpperLeg|LowerLeg|Foot)"))) {
                        human.put(bone, org.json.JSONObject().put("node", i))
                        legacy.put(org.json.JSONObject().put("bone", bone).put("node", i))
                    }
                }
                val extension = if (version == 0)
                    org.json.JSONObject().put("specVersion", "0.0").put("humanoid", org.json.JSONObject().put("humanBones", legacy))
                    else org.json.JSONObject().put("specVersion", "1.0").put("humanoid", org.json.JSONObject().put("humanBones", human))
                json.put("extensions", org.json.JSONObject().put(if (version == 0) "VRM" else "VRMC_vrm", extension))
                imported += store.importBytes("基础VRM$version.vrm", ModelCodec.pack(json, data))
            }
            val host = field(activity, "host") as RenderHost
            for ((index, role) in imported.withIndex()) {
                val loaded = CountDownLatch(1)
                ins.runOnMainSync { host.load(role) { loaded.countDown() } }
                assertTrue("导入角色未实际加载", loaded.await(45, TimeUnit.SECONDS))
                assertTrue(host.node!!.animationCount >= 7)
                val bitmap = capture(host, "qa-import-$index.png")
                if(index == 0) {
                    var textured = 0
                    for(y in 0 until bitmap.height step 2) for(x in 0 until bitmap.width step 2) {
                        val c=bitmap.getPixel(x,y)
                        if(android.graphics.Color.red(c)>android.graphics.Color.green(c)+15 && android.graphics.Color.blue(c)>android.graphics.Color.green(c)+15) textured++
                    }
                    assertTrue("关联 PNG 纹理没有出现在实际画面",textured>20)
                }
                bitmap.recycle()
            }
            // Cancel stale model requests and verify the last request wins.
            val loaded = CountDownLatch(1)
            ins.runOnMainSync {
                repeat(20) { host.load(imported[it % imported.size]) }
                host.load(imported.last()) { loaded.countDown() }
            }
            assertTrue(loaded.await(45, TimeUnit.SECONDS))
            assertEquals(imported.last().id, host.role!!.id)
        } finally {
            closeStage(activity)
            imported.forEach { store.delete(it) }
        }
    }

    @Test
    fun sustainedModesCollectRealResourceMeasurements() {
        val output = File(context.filesDir, "qa-stability.txt")
        output.writeText("API=${Build.VERSION.SDK_INT} device=${Build.MODEL}; cloud software emulator; not Redmi K80\n")
        fun sample(label: String, host: RenderHost) {
            val samples = java.util.Collections.synchronizedList(mutableListOf<String>())
            ins.runOnMainSync { host.onStats = { samples.add(it) } }
            val start = SystemClock.elapsedRealtime()
            val cpu = Process.getElapsedCpuTime()
            val pss = Debug.getPss()
            Thread.sleep(60_000)
            val elapsed = SystemClock.elapsedRealtime() - start
            output.appendText("$label elapsedMs=$elapsed cpuMs=${Process.getElapsedCpuTime()-cpu} pssStartKB=$pss pssEndKB=${Debug.getPss()} nativeBytes=${Debug.getNativeHeapAllocatedSize()} samples=${samples.toList()}\n")
            assertTrue("没有实际帧回调", samples.size > 5)
            assertNotNull(host.node)
        }
        val dance = stage("dance")
        try { sample("dance", field(dance, "host") as RenderHost) }
        finally { closeStage(dance) }
        device.executeShellCommand("pm grant ${context.packageName} android.permission.CAMERA")
        val ar = stage("ar")
        val camera = field(ar, "camera") as? CameraBackdrop
        try {
            assertNotNull("无 ARCore 的测试设备必须进入相机后备模式", camera)
            val texture = field(ar, "cameraTexture") as android.view.TextureView
            val oldTimestamp = texture.surfaceTexture!!.timestamp
            ins.runOnMainSync { repeat(3) { camera!!.stop(); camera.start() } }
            waitFor("快速暂停恢复后输出新的相机帧") { field(camera!!,"session") != null && texture.surfaceTexture!!.timestamp > oldTimestamp }
            sample("camera-fallback", field(ar, "host") as RenderHost)
            ins.runOnMainSync {
                val frame = texture.bitmap
                assertNotNull("相机没有输出画面", frame)
                File(context.filesDir,"qa-camera.png").outputStream().use { frame!!.compress(Bitmap.CompressFormat.PNG,100,it) }
                frame!!.recycle()
            }
            device.setOrientationLeft()
            waitFor("相机横屏尺寸") { texture.width > texture.height }
            Thread.sleep(1500)
            ins.runOnMainSync {
                val values = FloatArray(9)
                texture.getTransform(android.graphics.Matrix()).getValues(values)
                assertTrue(values.all { it.isFinite() })
                // A real quarter turn, not the old unchanged portrait matrix.
                assertTrue(kotlin.math.abs(values[1]) > .1f && kotlin.math.abs(values[3]) > .1f)
                texture.bitmap!!.let { frame ->
                    File(context.filesDir,"qa-camera-landscape.png").outputStream().use { frame.compress(Bitmap.CompressFormat.PNG,100,it) }
                    frame.recycle()
                }
            }
            device.setOrientationNatural()
            device.unfreezeRotation()
        } finally {
            device.setOrientationNatural()
            device.unfreezeRotation()
            closeStage(ar)
        }
        waitFor("相机资源释放") { camera == null ||
            (field(camera,"camera") == null && field(camera,"session") == null && field(camera,"surface") == null &&
             field(camera,"pendingOpens") == 0 && field(camera,"pendingCloses") == 0) }
        device.executeShellCommand("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
        try {
            ins.runOnMainSync { context.startForegroundService(Intent(context,PetService::class.java).setAction("show")) }
            waitFor("独立桌宠加载") { PetService.current.get()?.let { (field(it,"host") as? RenderHost)?.node != null } == true }
            val service = PetService.current.get()!!
            sample("desktop-pet", field(service,"host") as RenderHost)
            ins.runOnMainSync { service.onTrimMemory(80) }
            waitFor("严重内存压力保护") { field(service,"hidden") == true }
            ins.runOnMainSync { context.startService(Intent(context,PetService::class.java).setAction("show")) }
            waitFor("内存压力后恢复") { field(service,"hidden") == false }
        } finally {
            val closing = PetService.current.get()
            ins.runOnMainSync { context.stopService(Intent(context,PetService::class.java)) }
            waitFor("前台服务释放") { PetService.current.get() == null && (closing == null || field(closing,"host") == null) }
        }
        assertEquals("全部视图退出后不应残留引擎引用",0,field(RenderEngine,"users"))
        System.gc()
        Thread.sleep(1000)
        output.appendText("afterClosePssKB=${Debug.getPss()} nativeBytes=${Debug.getNativeHeapAllocatedSize()}\n")
    }

    @Test
    fun gpuCapabilityDecisionMatchesAvailableDriver() {
        val activity=ins.startActivitySync(Intent(context,StageActivity::class.java).putExtra("mode","preview").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as StageActivity
        try {
            val status=field(activity,"status") as android.widget.TextView
            if(status.text.toString().contains("旧版 SwiftShader 4")) {
                assertNull(field(activity,"host"))
                Thread.sleep(2000)
                assertFalse(activity.isFinishing)
            } else {
                waitFor("受支持驱动必须加载真实模型") { (field(activity,"host") as? RenderHost)?.node!=null }
                assertNotNull(field(activity,"host"))
            }
        } finally { closeStage(activity) }
    }
}
