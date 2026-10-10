package com.dana.ai

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/** Inexact by design: no SCHEDULE_EXACT_ALARM permission or permanent worker. */
class ReminderScheduler(private val context: Context) {
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private fun pending(id: Int) = PendingIntent.getBroadcast(
        context, id,
        Intent(context, ReminderReceiver::class.java).setAction(ReminderReceiver.ACTION_REMIND).putExtra(ReminderReceiver.EXTRA_ID, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun schedule(item: ReminderStore.Reminder) {
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, item.dueAt, pending(item.id))
    }

    fun cancel(item: ReminderStore.Reminder) = alarmManager.cancel(pending(item.id))

    fun cancelAll(store: ReminderStore) {
        store.all().forEach(::cancel)
        store.clear()
    }

    fun restoreAfterBoot(store: ReminderStore) {
        store.pending().forEach(::schedule)
    }
}
