package com.dana.ai

import java.util.concurrent.atomic.AtomicInteger

/** In-memory counters only: never persist speech/audio or a model path. */
object WhisperMetrics {
    private val started = AtomicInteger()
    private val captured = AtomicInteger()
    private val transcribed = AtomicInteger()
    private val failed = AtomicInteger()
    fun start() { started.incrementAndGet() }
    fun capture() { captured.incrementAndGet() }
    fun success() { transcribed.incrementAndGet() }
    fun failure() { failed.incrementAndGet() }
    fun report(): String = buildString {
        append("Whisper sessions started: ${started.get()}\n")
        append("Audio windows captured: ${captured.get()}\n")
        append("Transcriptions returned: ${transcribed.get()}\n")
        append("Whisper failures: ${failed.get()}\n")
    }
}
