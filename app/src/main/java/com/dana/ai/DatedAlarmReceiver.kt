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
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build

/** Date-specific alarm-clock event. Posts an alarm-sound notification; not a full-screen clock UI. */
class DatedAlarmReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_RING = "com.dana.ai.DATED_ALARM"
        const val EXTRA_ID = "alarm_id"
        private const val CHANNEL = "dana_dated_alarm_sound"
    }
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_RING) return
        val alarm = DatedAlarmScheduler(context).remove(intent.getIntExtra(EXTRA_ID, -1)) ?: return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            return
        val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        val channel = NotificationChannel(CHANNEL, "آلارم‌های تاریخ‌دار دانا", NotificationManager.IMPORTANCE_HIGH)
            .apply {
                setSound(sound, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                enableVibration(true)
            }
        manager.createNotificationChannel(channel)
        val open = PendingIntent.getActivity(context, alarm.id,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(alarm.id, Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_dana)
            .setContentTitle("⏰ آلارم دانا")
            .setContentText(alarm.label)
            .setCategory(Notification.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build())
    }
}
