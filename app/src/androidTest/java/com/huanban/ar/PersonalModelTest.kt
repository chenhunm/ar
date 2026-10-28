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
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Executed locally only when the user's private archive is included. */
@RunWith(AndroidJUnit4::class)
class PersonalModelTest {
    @Test fun providedArchiveBecomesArDefaultAndRendersSkinnedModel() {
        val ins=InstrumentationRegistry.getInstrumentation();val context=ins.targetContext
        assumeTrue(context.assets.list("models")!!.contains("user-default.7z"))
        val store=RoleStore(context);store.ensureBuiltins()
        context.getSharedPreferences("settings",0).edit().remove("arRole").commit()
        assertEquals("user-default",store.arSelected()!!.id)
        val activity=ins.startActivitySync(Intent(context,StageActivity::class.java).putExtra("mode","ar").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as StageActivity
        fun host():RenderHost?=StageActivity::class.java.getDeclaredField("host").apply{isAccessible=true}.get(activity) as? RenderHost
        try {
            val deadline=SystemClock.elapsedRealtime()+180000
            while(host()?.node==null && SystemClock.elapsedRealtime()<deadline)Thread.sleep(250)
            val h=host()?:error("Scene unavailable")
            assertNotNull("用户模型未实际加载",h.node)
            assertEquals("user-default",h.role!!.id)
            assertTrue("人形骨骼动作未生成",h.node!!.animationCount>=7)
            val image=Bitmap.createBitmap(h.view.width,h.view.height,Bitmap.Config.ARGB_8888)
            val copy=CountDownLatch(1);var code=-1
            ins.runOnMainSync{FrameCapture.copy(h.view,image,{code=it;copy.countDown()})}
            assertTrue(copy.await(60,TimeUnit.SECONDS));assertEquals(android.view.PixelCopy.SUCCESS,code)
            var visible=0;var chromatic=0
            for(y in 0 until image.height step 2)for(x in 0 until image.width step 2){val c=image.getPixel(x,y);if(Color.alpha(c)>200){visible++;if(maxOf(Color.red(c),Color.green(c),Color.blue(c))-minOf(Color.red(c),Color.green(c),Color.blue(c))>20)chromatic++}}
            File(context.filesDir,"qa-user-default.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()
            assertTrue("角色没有输出可见三维像素",visible>200)
            assertTrue("没有检测到角色彩色材质",chromatic>50)
            File(context.filesDir,"qa-user-default.txt").writeText("role=${h.role!!.name} animations=${h.node!!.animationCount} visible=$visible chromatic=$chromatic API=${android.os.Build.VERSION.SDK_INT}; local software emulator, not Redmi K80\n")
        } finally {ins.runOnMainSync{activity.finish()}}
    }
}
