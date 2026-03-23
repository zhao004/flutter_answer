package com.example.flutter_answer.overlay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.flutter_answer.R

/**
 * 使用前台服务托住进程，避免悬浮窗在应用切后台后被系统过早回收。
 */
class NativeOverlayService : Service() {

    override fun onCreate() {
        super.onCreate()
        NativeOverlayController.initialize(applicationContext)
        ensureNotificationChannel()
        startForeground(notificationId, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        NativeOverlayController.hideOverlay(notify = false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            channelId,
            "悬浮窗控制",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "保持答题悬浮窗在后台持续可用。"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification() = NotificationCompat.Builder(this, channelId)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle("AI自动答题悬浮窗运行中")
        .setContentText("原生悬浮窗已启用，可随时回到应用配置。")
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .build()

    companion object {
        private const val channelId = "flutter_answer_native_overlay"
        private const val notificationId = 3007

        fun ensureRunning(context: Context) {
            val intent = Intent(context, NativeOverlayService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, NativeOverlayService::class.java))
        }
    }
}
