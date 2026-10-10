package com.dana.ai

/**
 * Explicit, temporary user-controlled diagnostic. Never stored in preferences,
 * files, crash logs or event history. The user decides whether to share a report.
 */
object WakeSpeechProbe {
    private const val TEN_MINUTES = 10 * 60 * 1000L
    @Volatile private var expiresAt = 0L
    @Volatile private var last = ""
    @Volatile private var lastEngine = "NONE"

    fun enabled(): Boolean {
        val active = expiresAt != 0L && System.currentTimeMillis() < expiresAt
        if (!active && expiresAt != 0L) disable()
        return active
    }
    fun toggle(): Boolean {
        if (enabled()) { disable(); return false }
        last = ""
        lastEngine = "NONE"
        expiresAt = System.currentTimeMillis() + TEN_MINUTES
        return true
    }
    fun disable() { expiresAt = 0L; last = ""; lastEngine = "NONE" }
    fun capture(text: String, engine: String = "VOSK_LOCAL") {
        if (enabled()) {
            last = text.take(180)
            lastEngine = if (engine == "ANDROID_ON_DEVICE") engine else "VOSK_LOCAL"
        } else if (expiresAt != 0L) disable()
    }
    fun report(): String = if (enabled()) {
        "Temporary transcript capture: YES (expires after 10 minutes)\n" +
            "Last recognized phrase: " + (last.ifBlank { "(none yet)" }) + "\n" +
            "Recognizer used for phrase: " + lastEngine + "\n" +
            "WARNING: this line can contain private speech; review before sharing.\n"
    } else "Temporary transcript capture: NO\n"
}
