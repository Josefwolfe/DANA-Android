package com.dana.ai

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Only reschedules alarms after reboot; never starts microphone/background listening. */
class ReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            ReminderScheduler(context).restoreAfterBoot(ReminderStore(context))
            DatedAlarmScheduler(context).restoreAfterBoot()
        } else if (intent?.action == android.app.AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED) {
            // Reschedule existing inexact alarms with precise timing after explicit consent.
            DatedAlarmScheduler(context).restoreAfterBoot()
        }
    }
}
