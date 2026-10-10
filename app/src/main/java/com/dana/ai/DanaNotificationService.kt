package com.dana.ai

import android.service.notification.NotificationListenerService

/** فقط پس از فعال‌سازی صریح در تنظیمات اندروید قابل استفاده است. */
class DanaNotificationService : NotificationListenerService() {
    companion object {
        @Volatile var active: DanaNotificationService? = null
            private set
    }
    override fun onListenerConnected() { super.onListenerConnected(); active = this }
    override fun onListenerDisconnected() { if (active === this) active = null; super.onListenerDisconnected() }
    fun safeSummary(): String {
        val items = activeNotifications ?: return "اعلان فعالی پیدا نشد."
        val perApp = items.groupingBy { it.packageName.substringAfterLast('.') }.eachCount()
        return "${items.size} اعلان فعال:\n" + perApp.entries.joinToString("\n") { "${it.key}: ${it.value}" }
    }
}
