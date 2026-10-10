package com.dana.ai

import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/** In-memory, category-only diagnostic counters. Never stores spoken text or contact names. */
object WakeExecutionMetrics {
    private val received = AtomicInteger()
    private val uiRouted = AtomicInteger()
    private val requested = AtomicInteger()
    private val needsUser = AtomicInteger()
    private val unsupported = AtomicInteger()
    @Volatile private var lastType = "NONE"
    @Volatile private var lastOutcome = "NONE"

    private fun safe(value: String): String = value.uppercase(Locale.ROOT)
        .replace(Regex("[^A-Z0-9_]"), "_").take(40)

    fun received(type: String) {
        received.incrementAndGet()
        lastType = safe(type)
        lastOutcome = "RECEIVED"
    }

    fun outcome(value: String) {
        val status = safe(value)
        lastOutcome = status
        when (status) {
            "UI_ROUTED" -> uiRouted.incrementAndGet()
            "APP_LAUNCH_REQUESTED", "LOCAL_ACTION_REQUESTED", "RESPONSE_ONLY", "UI_ACTION_DISPATCHED" -> requested.incrementAndGet()
            "USER_TAP_REQUIRED" -> needsUser.incrementAndGet()
            "UNSUPPORTED" -> unsupported.incrementAndGet()
        }
    }

    fun report(): String = buildString {
        append("Wake commands passed to dispatcher: ").append(received.get()).append('\n')
        append("Commands routed to visible screen: ").append(uiRouted.get()).append('\n')
        append("Actions/responses requested (not verified completed): ").append(requested.get()).append('\n')
        append("Commands needing user tap: ").append(needsUser.get()).append('\n')
        append("Unsupported commands: ").append(unsupported.get()).append('\n')
        append("Last command category: ").append(lastType).append('\n')
        append("Last dispatch outcome: ").append(lastOutcome).append('\n')
    }
}
