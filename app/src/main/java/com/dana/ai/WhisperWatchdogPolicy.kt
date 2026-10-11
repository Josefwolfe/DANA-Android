package com.dana.ai

/** Pure policy for bounding a local Whisper attempt without changing privacy guarantees. */
object WhisperWatchdogPolicy {
    // Includes microphone capture, model loading and native decoding. On slow phones
    // Whisper may still be running natively after this timeout; never launch it again
    // during the same service lifetime.
    const val COMMAND_TIMEOUT_MS = 25_000L

    fun useWhisper(modelInstalled: Boolean, nativeAvailable: Boolean,
                   disabledForSession: Boolean): Boolean =
        modelInstalled && nativeAvailable && !disabledForSession

    fun shouldTimeout(stage: String, stopped: Boolean): Boolean =
        !stopped && stage == "command_whisper"
}
