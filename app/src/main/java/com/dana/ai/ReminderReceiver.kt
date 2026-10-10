package com.dana.ai

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

class ReminderReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_REMIND = "com.dana.ai.ACTION_REMIND"
        const val EXTRA_ID = "reminder_id"
        private const val CHANNEL_ID = "dana_user_reminders"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_REMIND) return
        val store = ReminderStore(context)
        val item = store.get(intent.getIntExtra(EXTRA_ID, -1)) ?: return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!manager.areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            // Keep it in the local list so a denied permission does not silently erase it.
            return
        }
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "یادآوری‌های دانا", NotificationManager.IMPORTANCE_HIGH)
        )
        val openApp = PendingIntent.getActivity(
            context, item.id, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setContentTitle("یادآوری دانا")
            .setContentText(item.text)
            .setSmallIcon(R.drawable.ic_dana)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .build()
        manager.notify(item.id, notification)
        store.remove(item.id)
    }
}
