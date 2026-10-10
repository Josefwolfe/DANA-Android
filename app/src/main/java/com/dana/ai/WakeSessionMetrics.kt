package com.dana.ai

import java.util.concurrent.atomic.AtomicInteger

/** Volatile, process-only counts. No speech text or personal data is retained. */
object WakeSessionMetrics {
    private val wakeHits = AtomicInteger()
    private val windows = AtomicInteger()
    private val captured = AtomicInteger()
    private val recognized = AtomicInteger()
    private val unknown = AtomicInteger()
    private val timeouts = AtomicInteger()
    private val stale = AtomicInteger()

    fun wakeDetected() { wakeHits.incrementAndGet() }
    fun commandWindow() { windows.incrementAndGet() }
    fun commandCaptured() { captured.incrementAndGet() }
    fun knownCommand() { recognized.incrementAndGet() }
    fun unknownCommand() { unknown.incrementAndGet() }
    fun commandTimeout() { timeouts.incrementAndGet() }
    fun staleCallback() { stale.incrementAndGet() }

    fun report(): String = buildString {
        append("Wake detections: ").append(wakeHits.get()).append('\n')
        append("Command listening windows: ").append(windows.get()).append('\n')
        append("Command speech captured: ").append(captured.get()).append('\n')
        append("Commands parsed: ").append(recognized.get()).append('\n')
        append("Commands unknown: ").append(unknown.get()).append('\n')
        append("Command timeouts: ").append(timeouts.get()).append('\n')
        append("Stale callbacks discarded: ").append(stale.get()).append('\n')
    }
}
