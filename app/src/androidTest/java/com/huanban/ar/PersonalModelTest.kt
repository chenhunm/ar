package com.huanban.ar

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real authorized archive import and Filament output in all three modes. */
@RunWith(AndroidJUnit4::class)
class PersonalModelTest {
    private val ins=InstrumentationRegistry.getInstrumentation()
    private val context=ins.targetContext
    private val device=UiDevice.getInstance(ins)
    @Before fun prepareDevice() { TestDevice.prepare() }
    private fun field(owner:Any,name:String):Any? =
        owner.javaClass.getDeclaredField(name).apply{isAccessible=true}.get(owner)
    private fun waitFor(label:String, timeout:Long=180000, condition:()->Boolean) {
        val end=SystemClock.elapsedRealtime()+timeout
        while(SystemClock.elapsedRealtime()<end) {
            if(condition()) return
            Thread.sleep(200)
        }
        fail("等待超时："+label)
    }
    private fun capture(host:RenderHost,name:String,transparent:Boolean=false) {
        waitFor("模型表面") { host.node!=null && host.view.width>0 && host.view.height>0 }
        assertTrue("用户骨骼动作缺失",host.node!!.animationCount>=7)
        val end=SystemClock.elapsedRealtime()+60000
        var visible=0;var chromatic=0;var clear=0;var facePixels=0
        do {
            val image=Bitmap.createBitmap(host.view.width,host.view.height,Bitmap.Config.ARGB_8888)
            val done=CountDownLatch(1);var code=-1
            ins.runOnMainSync{FrameCapture.copy(host.view,image,{code=it;done.countDown()})}
            try {
                assertTrue(done.await(45,TimeUnit.SECONDS));assertEquals(android.view.PixelCopy.SUCCESS,code)
                visible=0;chromatic=0;clear=0;facePixels=0
                for(y in 0 until image.height step 2)for(x in 0 until image.width step 2) {
                    val c=image.getPixel(x,y)
                    if(Color.alpha(c)==0) clear++
                    if(Color.alpha(c)>200 && maxOf(Color.red(c),Color.green(c),Color.blue(c))>35) {
                        visible++
                        if(maxOf(Color.red(c),Color.green(c),Color.blue(c))-minOf(Color.red(c),Color.green(c),Color.blue(c))>20) chromatic++
                        // This supplied avatar has blue back hair. Visible warm face
                        // pixels in the upper half catch a model facing away even
                        // when geometry/material presence assertions pass.
                        if(y<image.height/2 && Color.red(c)>150 && Color.green(c)>80 &&
                            Color.red(c)>Color.green(c)*1.05f && Color.red(c)>Color.blue(c)*1.1f) facePixels++
                    }
                }
                File(context.filesDir,name+".png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}
            } finally {image.recycle()}
            if(visible>200 && chromatic>50 && facePixels>20)break
            Thread.sleep(250)
        } while(SystemClock.elapsedRealtime()<end)
        assertTrue("用户角色没有可见网格像素："+visible,visible>200)
        assertTrue("用户角色没有彩色材质像素："+chromatic,chromatic>50)
        assertTrue("默认朝向未露出角色面部："+facePixels,facePixels>20)
        if(transparent)assertTrue("用户桌宠背景不透明",clear>50)
        File(context.filesDir,"qa-user-model.txt").appendText(name+" role="+host.role!!.id+" animations="+host.node!!.animationCount+" visible="+visible+" chromatic="+chromatic+" facePixels="+facePixels+" transparent="+clear+" API="+android.os.Build.VERSION.SDK_INT+"; emulator, not Redmi K80\n")
    }
    private fun stage(mode:String,roleId:String) {
        val activity=ins.startActivitySync(Intent(context,StageActivity::class.java).putExtra("mode",mode).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as StageActivity
        try {
            waitFor("用户模型实际加载") { (field(activity,"host") as? RenderHost)?.node!=null }
            val host=field(activity,"host") as RenderHost
            assertEquals(roleId,host.role!!.id)
            capture(host,"qa-user-"+mode)
        } finally {
            ins.runOnMainSync{activity.finish()}
            waitFor("用户场景资源释放",45000){activity.isDestroyed && field(activity,"host")==null}
        }
    }
    @Test fun providedArchiveImportsAndActuallyRendersInArDanceAndPet() {
        assertTrue("用户默认模型没有打包",context.assets.list("models")!!.contains("user-default.7z"))
        val store=RoleStore(context);store.ensureBuiltins()
        context.getSharedPreferences("settings",0).edit().remove("arRole").commit()
        assertEquals("user-default",store.arSelected()!!.id)
        device.executeShellCommand("pm grant com.huanban.ar android.permission.CAMERA")
        stage("ar","user-default")
        device.executeShellCommand("am start -n com.huanban.ar.test/com.huanban.ar.FixtureGrantActivity")
        val uri=DocumentsContract.buildDocumentUri("com.huanban.ar.test.fixtures","user-default.7z")
        waitFor("真实压缩包读取权限",15000) {
            context.checkUriPermission(uri,android.os.Process.myPid(),android.os.Process.myUid(),Intent.FLAG_GRANT_READ_URI_PERMISSION)==android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        val imported=store.importFile(uri)
        try {
            assertTrue(store.file(imported).length()>10000000)
            assertTrue(imported.animations.size>=7)
            store.select(imported)
            stage("dance",imported.id)
            device.executeShellCommand("appops set com.huanban.ar SYSTEM_ALERT_WINDOW allow")
            context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            waitFor("桌宠前台入口",45000){device.hasObject(By.desc("AR 模式"))}
            ins.runOnMainSync{context.startForegroundService(Intent(context,PetService::class.java).setAction("show"))}
            waitFor("用户桌宠模型加载"){PetService.current.get()?.let{(field(it,"host") as? RenderHost)?.node!=null}==true}
            val service=PetService.current.get()!!
            val host=field(service,"host") as RenderHost
            assertEquals(imported.id,host.role!!.id)
            capture(host,"qa-user-pet",transparent=true)
        } finally {
            ins.runOnMainSync{context.stopService(Intent(context,PetService::class.java))}
            waitFor("用户桌宠资源释放",45000){PetService.current.get()==null}
            store.select(store.roles().first{it.id=="qing"})
            store.delete(imported)
        }
    }
}
