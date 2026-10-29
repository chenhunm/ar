package com.huanban.ar

import android.Manifest
import android.content.Intent
import android.graphics.Color
import android.os.SystemClock
import android.view.TextureView
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import java.io.File
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceCompatibilityTest {
    private val ins=InstrumentationRegistry.getInstrumentation()
    private val context=ins.targetContext
    private val device=UiDevice.getInstance(ins)
    @Before fun prepare()=TestDevice.prepare()
    private fun field(owner:Any,name:String):Any?=owner.javaClass.getDeclaredField(name).apply{isAccessible=true}.get(owner)
    private fun waitFor(label:String,timeout:Long=60000,condition:()->Boolean) {
        val end=SystemClock.elapsedRealtime()+timeout
        while(SystemClock.elapsedRealtime()<end) {if(condition())return;Thread.sleep(100)}
        fail("等待超时：$label")
    }
    private fun withSelection(test:(RoleStore)->Unit) {
        val prefs=context.getSharedPreferences("settings",0)
        val role=prefs.getString("role",null);val ar=prefs.getString("arRole",null)
        val marker=prefs.getBoolean("suppliedAvatarDefault.v1",false)
        try {test(RoleStore(context))}
        finally {
            prefs.edit().apply {
                if(role==null)remove("role")else putString("role",role)
                if(ar==null)remove("arRole")else putString("arRole",ar)
                putBoolean("suppliedAvatarDefault.v1",marker)
            }.commit()
        }
    }
    @Test fun legacyGeometricDefaultsMigrateOnceAndLaterChoicesRemain()=withSelection { store ->
        val prefs=context.getSharedPreferences("settings",0)
        prefs.edit().putString("role","qing").putString("arRole","momo")
            .remove("suppliedAvatarDefault.v1").commit()
        store.ensureBuiltins()
        assertEquals("user-default",store.selected()!!.id)
        assertEquals("user-default",store.arSelected()!!.id)
        store.select(store.roles().first{it.id=="qing"});store.ensureBuiltins()
        assertEquals("用户后续主动选择应保留","qing",store.selected()!!.id)
        assertEquals("qing",store.arSelected()!!.id)
        val imported=store.importBytes("保留导入选择.glb",context.assets.open("models/qing.glb").use{it.readBytes()})
        try {
            store.select(imported);prefs.edit().remove("suppliedAvatarDefault.v1").commit();store.ensureBuiltins()
            assertEquals("已有导入角色不能被迁移覆盖",imported.id,store.selected()!!.id)
        } finally {store.delete(imported)}
    }
    @Test fun freshInstallUsesProvidedModelOnHomeAndDanceWithoutManualSelection()=withSelection { store ->
        val prefs=context.getSharedPreferences("settings",0)
        prefs.edit().remove("role").remove("arRole").remove("suppliedAvatarDefault.v1").commit()
        store.ensureBuiltins()
        assertEquals("user-default",store.selected()!!.id)
        assertEquals("user-default",store.arSelected()!!.id)
        context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        waitFor("首页实际显示用户角色") {device.hasObject(By.textContains("当前角色：蓝色大肥鱼"))}
        val activity=ins.startActivitySync(Intent(context,StageActivity::class.java).putExtra("mode","dance")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as StageActivity
        try {
            waitFor("默认舞蹈角色加载") {(field(activity,"host") as? RenderHost)?.node!=null}
            val host=field(activity,"host") as RenderHost
            assertEquals("user-default",host.role!!.id)
            assertTrue(store.file(host.role!!).length()>10000000)
            device.takeScreenshot(File(context.filesDir,"qa-default-home-dance.png"))
        } finally {
            ins.runOnMainSync{activity.finish()}
            waitFor("默认场景释放"){activity.isDestroyed && field(activity,"host")==null}
        }
    }
    @Test fun cameraSurfaceRecreationAndManualRetryProduceNewRealFrames() {
        val store=RoleStore(context);store.ensureBuiltins()
        device.executeShellCommand("pm grant ${context.packageName} ${Manifest.permission.CAMERA}")
        val activity=ins.startActivitySync(Intent(context,StageActivity::class.java).putExtra("mode","ar")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as StageActivity
        try {
            val camera=field(activity,"camera") as CameraBackdrop
            val texture=field(activity,"cameraTexture") as TextureView
            val canvas=field(activity,"canvas") as FrameLayout
            waitFor("直接摄像头首帧") {field(camera,"session")!=null && (texture.surfaceTexture?.timestamp ?: 0)>0}
            val old=texture.surfaceTexture!!
            ins.runOnMainSync{canvas.removeView(texture)}
            waitFor("旧表面释放") {old.isReleased && field(camera,"session")==null}
            assertEquals("表面销毁不能关闭运行意图",true,field(camera,"active"))
            ins.runOnMainSync{canvas.addView(texture,0,FrameLayout.LayoutParams(-1,-1))}
            waitFor("重建表面自动恢复相机") {
                texture.surfaceTexture!==old && field(camera,"session")!=null && (texture.surfaceTexture?.timestamp ?: 0)>0
            }
            val before=texture.surfaceTexture!!.timestamp
            ins.runOnMainSync{camera.restart()}
            waitFor("手动重试输出新帧") {
                val timestamp=texture.surfaceTexture?.timestamp ?: 0
                field(camera,"session")!=null && timestamp>0 && timestamp!=before
            }
            var bright=0
            ins.runOnMainSync {
                val image=camera.capturePreview()!!
                for(y in 0 until image.height step 8)for(x in 0 until image.width step 8) {
                    val c=image.getPixel(x,y)
                    if(maxOf(Color.red(c),Color.green(c),Color.blue(c))>100)bright++
                }
                File(context.filesDir,"qa-camera-recreated.png").outputStream().use{image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
                image.recycle()
            }
            assertTrue("相机重建后画面全黑",bright>100)
            File(context.filesDir,"qa-camera-recovery.txt").writeText(camera.diagnostics()+"\nbright=$bright\n")
        } finally {
            ins.runOnMainSync{activity.finish()}
            waitFor("相机场景释放"){activity.isDestroyed && field(activity,"host")==null}
        }
    }
}
