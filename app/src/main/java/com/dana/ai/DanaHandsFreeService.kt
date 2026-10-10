package com.dana.ai

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * Explicitly user-enabled, optional system-bound service.
 * It never inspects screen content, captures events, clicks, or performs gestures.
 * Its sole purpose is to open a user-named installed app while DANA is backgrounded.
 */
class DanaHandsFreeService : AccessibilityService() {
    companion object {
        @Volatile private var connected: DanaHandsFreeService? = null

        fun launchNamedApp(name: String): Boolean {
            val service = connected ?: return false
            val intent = AppLauncher.resolve(service, name) ?: return false
            return runCatching { service.startActivity(intent) }.isSuccess
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Deliberately do not read or store event content.
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (connected === this) connected = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (connected === this) connected = null
        super.onDestroy()
    }
}
