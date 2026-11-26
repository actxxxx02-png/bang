package com.promptcut.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Keeps the app alive while it watches or renders long videos, so the work continues with the screen off
 * or while the user switches apps. Shows a progress notification.
 */
class WorkService : Service() {

    companion object {
        private const val CHANNEL = "promptcut_work"
        private const val ID = 42
        @Volatile private var running = false
        @Volatile private var lastUpdate = 0L

        fun start(ctx: Context, text: String) {
            if (running) return
            runCatching {
                ContextCompat.startForegroundService(ctx, Intent(ctx, WorkService::class.java).putExtra("text", text))
                running = true
            }
        }

        fun update(ctx: Context, text: String, progress: Int) {
            if (!running) return
            val now = System.currentTimeMillis()
            if (now - lastUpdate < 800) return // notifications are rate-limited by Android
            lastUpdate = now
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return
            ensureChannel(ctx)
            runCatching { NotificationManagerCompat.from(ctx).notify(ID, build(ctx, text, progress)) }
        }

        fun stop(ctx: Context) {
            if (!running) return
            running = false
            ctx.stopService(Intent(ctx, WorkService::class.java))
        }

        fun notifyCompleted(ctx: Context, title: String, message: String) {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return
            ensureChannel(ctx)
            val notif = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_cut)
                .setContentTitle(title)
                .setContentText(message)
                .setAutoCancel(true)
                .setContentIntent(
                    PendingIntent.getActivity(
                        ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                        PendingIntent.FLAG_IMMUTABLE,
                    )
                )
                .build()
            runCatching { NotificationManagerCompat.from(ctx).notify(ID + 1, notif) }
        }

        private fun ensureChannel(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(
                NotificationChannel(CHANNEL, "Editing progress", NotificationManager.IMPORTANCE_LOW)
            )
        }

        private fun build(ctx: Context, text: String, progress: Int) =
            NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_cut)
                .setContentTitle("PromptCut is working")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setProgress(100, progress.coerceAtLeast(0), progress < 0)
                .setContentIntent(
                    PendingIntent.getActivity(
                        ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                        PendingIntent.FLAG_IMMUTABLE,
                    )
                )
                .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel(this)
        val type = when {
            Build.VERSION.SDK_INT >= 35 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            else -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        try {
            ServiceCompat.startForeground(this, ID, build(this, intent?.getStringExtra("text") ?: "Working…", -1), type)
            if (!running) {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        } catch (e: Exception) {
            running = false
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        running = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    override fun onDestroy() {
        running = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
