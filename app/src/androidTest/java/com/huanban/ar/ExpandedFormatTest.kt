package com.huanban.ar

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExpandedFormatTest {
    private val ins=InstrumentationRegistry.getInstrumentation()
    private val context=ins.targetContext

    private fun render(role:Role,name:String) {
        RoleStore(context).select(role)
        val activity=ins.startActivitySync(Intent(context,StageActivity::class.java).putExtra("mode","preview").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as StageActivity
        fun host()=StageActivity::class.java.getDeclaredField("host").apply{isAccessible=true}.get(activity) as? RenderHost
        try {
            val end=SystemClock.elapsedRealtime()+90000
            while(host()?.node==null && SystemClock.elapsedRealtime()<end)Thread.sleep(200)
            val h=host()?:error("Scene unavailable");assertNotNull("转换模型未实际加载",h.node)
            Thread.sleep(1200)
            val image=Bitmap.createBitmap(h.view.width,h.view.height,Bitmap.Config.ARGB_8888)
            var result=-1;val done=CountDownLatch(1)
            ins.runOnMainSync {FrameCapture.copy(h.view,image,{result=it;done.countDown()})}
            assertTrue(done.await(60,TimeUnit.SECONDS));assertEquals(android.view.PixelCopy.SUCCESS,result)
            var bright=0
            for(y in image.height/4 until image.height*3/4 step 2)for(x in image.width/4 until image.width*3/4 step 2){val c=image.getPixel(x,y);if(maxOf(Color.red(c),Color.green(c),Color.blue(c))>100)bright++}
            File(context.filesDir,"qa-format-$name.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()
            assertTrue("转换后的网格没有出现在中心视口",bright>50)
        } finally {ins.runOnMainSync{activity.finish()};ins.waitForIdleSync()}
    }

    @Test fun fbxAndPmxConvertInSeparateProcessAndActuallyRender() {
        val store=RoleStore(context);store.ensureBuiltins()
        for(extension in listOf("fbx","pmx")) {
            val bytes=ins.context.assets.open("fixtures/original-triangle.$extension").use{it.readBytes()}
            val role=store.importBytes("原生转换.$extension",bytes)
            try {
                val j=ModelCodec.unpack(store.file(role).readBytes()).first
                assertTrue(j.getJSONArray("meshes").length()>0)
                if(extension=="pmx")assertTrue(j.getJSONArray("skins").length()>0)
                render(role,extension)
            } finally {store.select(store.roles().first{it.id=="qing"});store.delete(role)}
        }
    }

    @Test fun corruptNativeModelDoesNotPreventNextValidImport() {
        val store=RoleStore(context);store.ensureBuiltins();val before=store.roles().size
        assertThrows(IllegalStateException::class.java){store.importBytes("broken.fbx",byteArrayOf(1,2,3,4,5))}
        assertEquals(before,store.roles().size)
        val role=store.importBytes("recovery.obj","v 0 0 0\nv 1 0 0\nv 0 1 0\nf 1 2 3\n".toByteArray())
        try {render(role,"obj-recovery")}
        finally {store.select(store.roles().first{it.id=="qing"});store.delete(role)}
    }

    @Test fun pmdAndXpsAsciiActuallyRenderAfterConversion() {
        val store=RoleStore(context);store.ensureBuiltins()
        for(extension in listOf("pmd","ascii")) {
            val bytes=ins.context.assets.open("fixtures/original-triangle.$extension").use{it.readBytes()}
            val role=store.importBytes("旧格式.$extension",bytes)
            try {render(role,extension)}
            finally {store.select(store.roles().first{it.id=="qing"});store.delete(role)}
        }
    }
}
