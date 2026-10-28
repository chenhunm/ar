package com.huanban.ar

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.*
import android.provider.MediaStore

/**
 * Explicit user-consented screen capture. No microphone, camera or audio is captured separately.
 */
class RecordingService : Service() {
    companion object {
        @Volatile var running = false
    }

    private var recorder: MediaRecorder? = null
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var fd: ParcelFileDescriptor? = null
    private var uri: Uri? = null
    private var started = false

    override fun onBind(i: Intent?) = null

    override fun onStartCommand(i: Intent?, flags: Int, id: Int): Int {
        if (i?.action == "stop") {
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel("record", "幻伴视频录制", NotificationManager.IMPORTANCE_LOW)
            )
            val stop =
                PendingIntent.getService(
                    this,
                    9,
                    Intent(this, RecordingService::class.java).setAction("stop"),
                    PendingIntent.FLAG_IMMUTABLE,
                )
            val n =
                Notification.Builder(this, "record")
                    .setSmallIcon(R.drawable.ic_pet)
                    .setContentTitle("幻伴正在录制屏幕")
                    .setContentText("不录声音 · 点击停止并保存至相册")
                    .setOngoing(true)
                    .setContentIntent(stop)
                    .addAction(Notification.Action.Builder(null, "停止并保存", stop).build())
                    .build()
            startForeground(8, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            @Suppress("DEPRECATION")
            val data = i?.getParcelableExtra<Intent>("data") ?: error("缺少录屏授权")
            val p =
                getSystemService(MediaProjectionManager::class.java)
                    .getMediaProjection(i.getIntExtra("code", Activity.RESULT_CANCELED), data)
            projection = p
            p.registerCallback(
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        stopSelf()
                    }
                },
                Handler(Looper.getMainLooper()),
            )
            val dm = resources.displayMetrics
            val ratio = minOf(1f, 1080f / dm.widthPixels)
            val width = (dm.widthPixels * ratio).toInt() / 2 * 2
            val height = (dm.heightPixels * ratio).toInt() / 2 * 2
            val values =
                ContentValues().apply {
                    put(
                        MediaStore.Video.Media.DISPLAY_NAME,
                        "HuanBan_${System.currentTimeMillis()}.mp4",
                    )
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/HuanBanAR")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            val u =
                contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                    ?: error("无法创建视频")
            uri = u
            fd = contentResolver.openFileDescriptor(u, "w") ?: error("无法写入视频")
            @Suppress("DEPRECATION")
            val rec = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
            recorder = rec
            rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            rec.setVideoSize(width, height)
            rec.setVideoFrameRate(30)
            rec.setVideoEncodingBitRate(5_000_000)
            rec.setOutputFile(fd!!.fileDescriptor)
            rec.prepare()
            display =
                p.createVirtualDisplay(
                    "HuanBan capture",
                    width,
                    height,
                    dm.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    rec.surface,
                    null,
                    null,
                )
            rec.start()
            started = true
            running = true
        } catch (e: Exception) {
            android.util.Log.e("Recording", "录制失败", e)
            Ui.toast(this, "录制失败：${e.message}")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        var ok = false
        if (started)
            try {
                recorder?.stop()
                ok = true
            } catch (e: Exception) {
                android.util.Log.e("Recording", "停止失败", e)
            }
        recorder?.release()
        recorder = null
        display?.release()
        display = null
        projection?.stop()
        projection = null
        fd?.close()
        fd = null
        uri?.let { u ->
            if (ok) {
                contentResolver.update(
                    u,
                    ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) },
                    null,
                    null,
                )
                Ui.toast(this, "视频已保存至 Movies/HuanBanAR")
            } else contentResolver.delete(u, null, null)
        }
        uri = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
