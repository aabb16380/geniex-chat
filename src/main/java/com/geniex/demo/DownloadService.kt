package com.geniex.demo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * 后台模型下载服务：让下载在退到后台/锁屏后继续。
 * 目前只提供通知骨架，具体下载任务在下一步接入 MainActivity 的下载协程。
 */
class DownloadService : Service() {
    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        startForeground(NOTIFY_ID, buildNotification("正在下载模型", "准备中…"))
        return START_NOT_STICKY
    }

    private fun createChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "模型下载", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun buildNotification(
        title: String,
        text: String,
    ): Notification =
        Notification
            .Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "model_download"
        private const val NOTIFY_ID = 1001
    }
}
