package com.dana.ai

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/** User-enabled shortcut, NOT a background microphone or always-listening service. */
class QuickAccessNotification(private val context: Context) {
    companion object {
        const val CHANNEL_ID = "dana_quick_access"
        const val NOTIFICATION_ID = 1209
        const val ACTION_VOICE = "com.dana.ai.OPEN_VOICE_FROM_NOTIFICATION"
    }

    fun show(): Boolean {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!manager.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL_ID, "دسترسی سریع به دانا", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "میانبر اختیاری اجرای دانا؛ بدون شنود پس زمینه"
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        })
        val openIntent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_VOICE
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val open = PendingIntent.getActivity(
            context, 910, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notice = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_dana)
            .setContentTitle("دانا آماده است")
            .setContentText("برای فرمان فارسی لمس کنید")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "🎙 صحبت با دانا", open).build())
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        manager.notify(NOTIFICATION_ID, notice)
        return true
    }

    fun cancel() {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .cancel(NOTIFICATION_ID)
    }
}
