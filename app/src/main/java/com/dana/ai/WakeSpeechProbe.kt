package com.dana.ai

/** Opt-in 10-minute in-memory diagnostic. Never saved to disk or shared automatically. */
object WakeSpeechProbe {
    private const val TEN_MINUTES = 10 * 60 * 1000L
    @Volatile private var expiresAt = 0L
    @Volatile private var lastWake = ""
    @Volatile private var lastCommand = ""
    @Volatile private var lastCommandEngine = "NONE"

    fun enabled(): Boolean {
        val active = expiresAt != 0L && System.currentTimeMillis() < expiresAt
        if (!active && expiresAt != 0L) disable()
        return active
    }
    fun toggle(): Boolean {
        if (enabled()) { disable(); return false }
        lastWake = ""; lastCommand = ""; lastCommandEngine = "NONE"
        expiresAt = System.currentTimeMillis() + TEN_MINUTES
        return true
    }
    fun disable() {
        expiresAt = 0L
        lastWake = ""; lastCommand = ""; lastCommandEngine = "NONE"
    }
    /** Wake-stage Vosk guesses are NOT evidence that a command was transcribed. */
    fun captureWake(text: String) {
        if (enabled()) lastWake = text.take(180)
        else if (expiresAt != 0L) disable()
    }
    /** Only complete command transcripts go here. */
    fun capture(text: String, engine: String = "VOSK_LOCAL") {
        if (enabled()) {
            lastCommand = text.take(180)
            lastCommandEngine = when (engine) {
                "ANDROID_ON_DEVICE", "WHISPER_LOCAL" -> engine
                else -> "VOSK_LOCAL"
            }
        } else if (expiresAt != 0L) disable()
    }
    fun report(): String = if (enabled()) {
        "Temporary transcript capture: YES (expires after 10 minutes)\n" +
            "Last wake-stage Vosk phrase (not a command): " +
            (lastWake.ifBlank { "(none yet)" }) + "\n" +
            "Last command phrase: " + (lastCommand.ifBlank { "(none yet)" }) + "\n" +
            "Command recognizer: $lastCommandEngine\n" +
            "WARNING: these lines may contain private speech; review before sharing.\n"
    } else "Temporary transcript capture: NO\n"
}
