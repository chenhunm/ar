package com.huanban.ar

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.SurfaceView

/** Wait asynchronously for an actual presented buffer; model parsing may finish before first draw. */
internal object FrameCapture {
    fun copy(view:SurfaceView, bitmap:Bitmap, complete:(Int)->Unit, timeoutMillis:Long=30000L) {
        val handler=Handler(Looper.getMainLooper())
        val deadline=SystemClock.elapsedRealtime()+timeoutMillis
        fun attempt() {
            if(bitmap.isRecycled){complete(PixelCopy.ERROR_DESTINATION_INVALID);return}
            if(!view.isAttachedToWindow || !view.holder.surface.isValid){complete(PixelCopy.ERROR_SOURCE_INVALID);return}
            try {
                PixelCopy.request(view,bitmap,{code->
                    if((code==PixelCopy.ERROR_SOURCE_NO_DATA || code==PixelCopy.ERROR_TIMEOUT) && SystemClock.elapsedRealtime()<deadline)handler.postDelayed({attempt()},150)
                    else complete(code)
                },handler)
            } catch(e:IllegalArgumentException){complete(PixelCopy.ERROR_SOURCE_INVALID)}
        }
        if(Looper.myLooper()==Looper.getMainLooper())attempt() else handler.post{attempt()}
    }
}
