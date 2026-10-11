package com.dana.ai

import java.util.concurrent.atomic.AtomicInteger

/** Process-only, privacy-safe metrics. Never stores spoken text or raw audio. */
object WhisperMetrics {
    private val started = AtomicInteger()
    private val captured = AtomicInteger()
    private val transcribed = AtomicInteger()
    private val failed = AtomicInteger()
    private val timedOut = AtomicInteger()
    private val fallbacks = AtomicInteger()
    @Volatile private var lastPhase = "NONE"
    @Volatile private var phaseAtTimeout = "NONE"
    @Volatile private var startedAtNanos = 0L
    @Volatile private var lastDurationMs = 0L

    fun start() {
        started.incrementAndGet()
        startedAtNanos = System.nanoTime()
        lastPhase = "RECORDING"
    }
    fun capture() { captured.incrementAndGet(); lastPhase = "LOADING_MODEL" }
    fun decoding() { if (lastPhase == "LOADING_MODEL") lastPhase = "DECODING" }
    fun success() {
        transcribed.incrementAndGet()
        lastDurationMs = durationMs()
        lastPhase = "SUCCEEDED"
    }
    fun failure() {
        failed.incrementAndGet()
        lastDurationMs = durationMs()
        lastPhase = "FAILED"
    }
    fun timeout() {
        timedOut.incrementAndGet()
        phaseAtTimeout = lastPhase
        lastDurationMs = durationMs()
        lastPhase = "TIMED_OUT"
    }
    fun fallback() { fallbacks.incrementAndGet() }
    private fun durationMs(): Long =
        if (startedAtNanos == 0L) 0L else
            (System.nanoTime() - startedAtNanos).coerceAtLeast(0L) / 1_000_000L

    fun report(): String = buildString {
        append("Whisper sessions started: ${started.get()}\n")
        append("Audio windows captured: ${captured.get()}\n")
        append("Transcriptions returned: ${transcribed.get()}\n")
        append("Whisper failures: ${failed.get()}\n")
        append("Whisper timeouts: ${timedOut.get()}\n")
        append("Whisper fallbacks attempted: ${fallbacks.get()}\n")
        append("Whisper last phase: $lastPhase\n")
        append("Whisper phase at last timeout: $phaseAtTimeout\n")
        append("Whisper last attempt duration ms: $lastDurationMs\n")
        if (lastPhase == "RECORDING" || lastPhase == "LOADING_MODEL" || lastPhase == "DECODING")
            append("Whisper current attempt elapsed ms: ${durationMs()}\n")
        append("A timeout cannot forcibly interrupt an already-running native decode.\n")
    }
}
