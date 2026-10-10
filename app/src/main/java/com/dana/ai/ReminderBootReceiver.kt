package com.dana.ai

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Only reschedules alarms after reboot; never starts microphone/background listening. */
class ReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            ReminderScheduler(context).restoreAfterBoot(ReminderStore(context))
        }
    }
}
