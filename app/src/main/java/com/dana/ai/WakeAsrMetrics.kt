package com.dana.ai

import java.util.concurrent.atomic.AtomicInteger

/** Process-only diagnostic counters. Never stores audio or spoken text. */
object WakeAsrMetrics {
    private val androidAttempts = AtomicInteger()
    private val androidResults = AtomicInteger()
    private val androidErrors = AtomicInteger()
    private val voskFallbacks = AtomicInteger()
    private val voskCommands = AtomicInteger()
    @Volatile private var lastEngine = "NONE"
    @Volatile private var lastFailure = "NONE"

    fun androidStart() { androidAttempts.incrementAndGet(); lastEngine = "ANDROID_ON_DEVICE" }
    fun androidResult() { androidResults.incrementAndGet(); lastEngine = "ANDROID_ON_DEVICE" }
    fun androidError(message: String) {
        androidErrors.incrementAndGet()
        lastFailure = if (message.contains("فارسی") || message.contains("زبان"))
            "PERSIAN_LANGUAGE_UNAVAILABLE" else "RECOGNIZER_ERROR"
    }
    fun voskFallback() { voskFallbacks.incrementAndGet(); lastEngine = "VOSK_FALLBACK" }
    fun voskCommand() { voskCommands.incrementAndGet(); lastEngine = "VOSK_LOCAL" }

    fun report(): String = buildString {
        append("Android on-device attempts: ").append(androidAttempts.get()).append('\n')
        append("Android on-device results: ").append(androidResults.get()).append('\n')
        append("Android on-device failures: ").append(androidErrors.get()).append('\n')
        append("Fallbacks to Vosk: ").append(voskFallbacks.get()).append('\n')
        append("Vosk command results: ").append(voskCommands.get()).append('\n')
        append("Last command ASR engine: ").append(lastEngine).append('\n')
        append("Last on-device failure: ").append(lastFailure).append('\n')
        append("Android on-device availability does not guarantee a Persian language model.\n")
    }
}
