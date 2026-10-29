package com.huanban.ar

import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.view.PixelCopy
import android.view.TextureView
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*

/** Check SurfaceFlinger's final display/video, not just the two input buffers.
 * The camera is stopped on its last real frame and animation is paused so that
 * source buffers can be compared with a screenshot or a later decoded MP4 frame.
 */
object CameraCompositeAssertions {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private fun field(owner: Any, name: String): Any? =
        owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)

    private fun waitFor(label: String, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 45000
        while (SystemClock.elapsedRealtime() < end) {
            if (condition()) return
            Thread.sleep(100)
        }
        fail("等待超时：$label")
    }

    fun freeze(activity: StageActivity) {
        val camera = field(activity, "camera") as? CameraBackdrop ?: error("未进入相机叠加模式")
        val texture = field(activity, "cameraTexture") as TextureView
        waitFor("相机必须已输出真实帧") {
            field(camera, "session") != null && (texture.surfaceTexture?.timestamp ?: 0L) > 0L
        }
        ins.runOnMainSync {
            (field(activity, "host") as RenderHost).playing = false
            camera.stop()
        }
        waitFor("相机停止在最后一帧") {
            field(camera, "pendingCloses") == 0 && field(camera, "pendingOpens") == 0
        }
        Thread.sleep(600)
    }

    data class Result(val background: Int, val matchedBackground: Int, val skin: Int, val matchedSkin: Int) {
        val backgroundRatio get() = matchedBackground.toFloat() / background.coerceAtLeast(1)
        val skinRatio get() = matchedSkin.toFloat() / skin.coerceAtLeast(1)
    }

    private data class Sources(val model: Bitmap, val background: Bitmap, val location: IntArray)

    private fun measure(image: Bitmap, source: Sources): Result {
        val (model, background, location) = source
        var candidates=0;var matches=0;var skin=0;var skinMatches=0
        fun near(a:Int,b:Int) = kotlin.math.abs(Color.red(a)-Color.red(b))<40 &&
            kotlin.math.abs(Color.green(a)-Color.green(b))<40 && kotlin.math.abs(Color.blue(a)-Color.blue(b))<40
        for(y in 8 until model.height-8 step 4) for(x in 8 until model.width-8 step 4) {
            val sx=x+location[0];val sy=y+location[1]
            if(sx !in 0 until image.width || sy !in 0 until image.height)continue
            val m=model.getPixel(x,y)
            val c=background.getPixel(x*background.width/model.width,y*background.height/model.height)
            val displayed=image.getPixel(sx,sy)
            if(Color.alpha(m)==0 && maxOf(Color.red(c),Color.green(c),Color.blue(c))>100) {
                candidates++;if(near(c,displayed))matches++
            }
            if(Color.alpha(m)>250 && Color.red(m)>40 && Color.red(m)>Color.green(m)+2 && Color.green(m)>Color.blue(m)+5) {
                skin++;if(near(m,displayed))skinMatches++
            }
        }
        return Result(candidates,matches,skin,skinMatches)
    }

    private fun sources(activity: StageActivity): Sources {
        val host = field(activity, "host") as RenderHost
        val location = IntArray(2)
        var camera: Bitmap? = null
        ins.runOnMainSync {
            host.view.getLocationOnScreen(location)
            camera = (field(activity, "camera") as CameraBackdrop).capturePreview()
        }
        val background = camera ?: error("相机像素缺失")
        val started = SystemClock.elapsedRealtime()
        val deadline = started + 30000
        var attempts = 0
        try {
            while (true) {
                val model = Bitmap.createBitmap(host.view.width, host.view.height, Bitmap.Config.ARGB_8888)
                var ready = false
                try {
                    val done = CountDownLatch(1)
                    var result = -1
                    ins.runOnMainSync {
                        host.view.getLocationOnScreen(location)
                        FrameCapture.copy(host.view, model, { result = it; done.countDown() })
                    }
                    assertTrue("三维画面读取超时", done.await(
                        (deadline-SystemClock.elapsedRealtime()).coerceAtLeast(1), TimeUnit.MILLISECONDS))
                    assertEquals(PixelCopy.SUCCESS, result)
                    // After the system recording dialog, SUCCESS can describe a
                    // transparent buffer queued during Activity pause/resume.
                    // Require the same skin samples used by compare(), rather
                    // than accepting an empty input layer as a composition test.
                    var skin = 0
                    for (y in 8 until model.height-8 step 4) for (x in 8 until model.width-8 step 4) {
                        val c = model.getPixel(x,y)
                        if (Color.alpha(c)>250 && Color.red(c)>40 &&
                            Color.red(c)>Color.green(c)+2 && Color.green(c)>Color.blue(c)+5) skin++
                    }
                    attempts++
                    File(activity.filesDir,"qa-composite-source-timing.txt").appendText(
                        "attempt=$attempts elapsed_ms=${SystemClock.elapsedRealtime()-started} skin=$skin\n")
                    if (skin>20) {
                        ready = true
                        return Sources(model,background,location)
                    }
                    if (attempts==1) File(activity.filesDir,"qa-composite-empty-model.png").outputStream().use {
                        model.compress(Bitmap.CompressFormat.PNG,100,it)
                    }
                    assertTrue("三维源图没有人物像素：$skin（等待上限30秒）",SystemClock.elapsedRealtime()<deadline)
                } finally { if (!ready) model.recycle() }
                Thread.sleep(200)
            }
        } catch (error: Throwable) { background.recycle(); throw error }
    }

    fun screen(activity: StageActivity, name: String, expectCamera: Boolean = true): Result {
        // Read the queued 3D buffer first. A screenshot taken before PixelCopy
        // can precede the first model frame after the system permission dialog.
        val source = sources(activity)
        val started=SystemClock.elapsedRealtime()
        val deadline=started+30000
        var attempts=0
        var consumed=false
        try {
            while(true) {
                val screenshot=ins.uiAutomation.takeScreenshot() ?: error("无法读取最终显示画面")
                try {
                    val measured=measure(screenshot,source)
                    attempts++
                    File(activity.filesDir,"qa-composite-display-timing.txt").appendText(
                        "$name attempt=$attempts elapsed_ms=${SystemClock.elapsedRealtime()-started} $measured\n")
                    val visible=measured.background>100 && measured.skin>20 && measured.skinRatio>.70f &&
                        (if(expectCamera)measured.backgroundRatio>.80f else measured.backgroundRatio<.10f)
                    if(visible || SystemClock.elapsedRealtime()>=deadline) {
                        // compare() retains every original assertion and saves the
                        // final display and both input buffers, including failures.
                        consumed=true
                        return compare(activity,screenshot,name,expectCamera,source)
                    }
                    if(attempts==1) File(activity.filesDir,"pending-$name.png").outputStream().use {
                        screenshot.compress(Bitmap.CompressFormat.PNG,100,it)
                    }
                } finally {screenshot.recycle()}
                Thread.sleep(200)
            }
        } finally {
            if(!consumed) {source.model.recycle();source.background.recycle()}
        }
    }

    fun frame(activity: StageActivity, image: Bitmap, name: String, expectCamera: Boolean = true): Result =
        compare(activity, image, name, expectCamera, sources(activity))

    private fun compare(activity: StageActivity, image: Bitmap, name: String, expectCamera: Boolean,
        source: Sources): Result {
        val (model, background, location) = source
        try {
            val bounds = activity.getSystemService(android.view.WindowManager::class.java)
                .maximumWindowMetrics.bounds
            assertEquals("视频/截图应保持显示器宽度", bounds.width(), image.width)
            assertEquals("视频/截图应保持显示器高度，不能误用应用可用区域", bounds.height(), image.height)
            val measured = measure(image,source)
            val dir = activity.filesDir
            File(dir, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            File(dir, "$name-camera.png").outputStream().use { background.compress(Bitmap.CompressFormat.PNG, 100, it) }
            File(dir, "$name-model.png").outputStream().use { model.compress(Bitmap.CompressFormat.PNG, 100, it) }
            File(dir, "qa-camera-composition.txt").appendText("$name API=${android.os.Build.VERSION.SDK_INT} $measured backgroundRatio=${measured.backgroundRatio} skinRatio=${measured.skinRatio}\n")
            assertTrue("相机必须提供足量明亮样本，不能让全黑帧通过", measured.background > 100)
            assertTrue("最终合成画面必须保留三维人物：$measured", measured.skin > 20 && measured.skinRatio > .70f)
            if (expectCamera)
                assertTrue("最终画面缺少相机背景：$measured", measured.backgroundRatio > .80f)
            else
                assertTrue("旧的窗口下方 Surface 应复现背景丢失：$measured", measured.backgroundRatio < .10f)
            return measured
        } finally { background.recycle(); model.recycle() }
    }
}
