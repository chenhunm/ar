package com.huanban.ar

import android.app.Service
import android.content.*
import android.os.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Native file parsers run in a separate process; parser crashes cannot kill the UI. */
object NativeModelCodec {
    val extensions = setOf("fbx", "pmx", "blend", "obj")
    external fun convertNative(source: String, output: String, root: String): String?

    fun convert(context: Context, source: File, root: File): ByteArray {
        check(Looper.myLooper() != Looper.getMainLooper()) { "模型转换必须在工作线程执行" }
        val output=File(root,"converted-${java.util.UUID.randomUUID()}.glb")
        val done=CountDownLatch(1)
        var error: String?=null
        val reply=Messenger(Handler(Looper.getMainLooper()) { message ->
            error=message.data.getString("error");done.countDown();true
        })
        val connection=object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                try {
                    Messenger(binder).send(Message.obtain(null,1).apply {
                        data=Bundle().apply { putString("source",source.canonicalPath);putString("output",output.canonicalPath);putString("root",root.canonicalPath) }
                        replyTo=reply
                    })
                } catch(e: Exception) {error="无法启动模型转换：${e.message}";done.countDown()}
            }
            override fun onServiceDisconnected(name: ComponentName) {error="模型转换进程异常退出，文件可能损坏或过大";done.countDown()}
            override fun onNullBinding(name: ComponentName) {error="模型转换服务不可用";done.countDown()}
        }
        check(context.bindService(Intent(context,ModelConversionService::class.java),connection,Context.BIND_AUTO_CREATE)) {"模型转换服务启动失败"}
        try {
            check(done.await(120,TimeUnit.SECONDS)) {"模型转换超时，请使用较小模型或导出 GLB"}
            check(error==null) {"模型转换失败：$error"}
            require(output.length() in 1..ModelCodec.MAX_BYTES.toLong()) {"转换结果为空或超过 64 MB"}
            return output.readBytes()
        } finally {context.unbindService(connection);output.delete()}
    }
}

class ModelConversionService : Service() {
    private val executor=java.util.concurrent.Executors.newSingleThreadExecutor()
    private val messenger=Messenger(Handler(Looper.getMainLooper()) { message ->
        val response=message.replyTo
        val request=Bundle(message.data)
        executor.execute {
            val failure=runCatching {
                System.loadLibrary("huanban_import")
                val root=File(request.getString("root")!!).canonicalFile
                require(root.path.startsWith(cacheDir.canonicalPath+File.separator)) {"模型目录无效"}
                val source=File(request.getString("source")!!).canonicalFile
                val output=File(request.getString("output")!!).canonicalFile
                require(source.path.startsWith(root.path+File.separator) && output.path.startsWith(root.path+File.separator))
                NativeModelCodec.convertNative(source.path,output.path,root.path)?.let {error(it)}
            }.exceptionOrNull()?.message
            runCatching { response.send(Message.obtain(null,2).apply {data=Bundle().apply{putString("error",failure)}}) }
        }
        true
    })
    override fun onBind(intent: Intent?): IBinder=messenger.binder
    override fun onUnbind(intent: Intent?): Boolean {
        // All native allocations, including a timed-out parser, are reclaimed.
        if (android.app.Application.getProcessName().endsWith(":model_import"))
            Process.killProcess(Process.myPid())
        return false
    }
    override fun onDestroy() {executor.shutdownNow();super.onDestroy()}
}
