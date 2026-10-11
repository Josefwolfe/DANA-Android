package com.dana.ai

/** JNI wrapper around the pinned, official whisper.cpp source. No network calls. */
object WhisperNative {
    val available: Boolean = runCatching {
        System.loadLibrary("dana_whisper")
        true
    }.getOrDefault(false)

    private external fun nativeInitialize(modelPath: String): Boolean
    private external fun nativeTranscribe(samples: FloatArray): String
    private external fun nativeRelease()

    /** Release a previously loaded model when a new BIN is imported (background thread only). */
    fun release() { if (available) runCatching { nativeRelease() } }

    /** Call only from a background thread; loading and decoding are expensive. */
    fun transcribe(modelPath: String, samples: FloatArray,
                   shouldContinue: () -> Boolean = { true },
                   onDecodeStart: () -> Unit = {}): String {
        check(available) { "Whisper native library is not available" }
        require(samples.isNotEmpty() && samples.size <= 16000 * 15) { "Invalid recording length" }
        if (!shouldContinue()) return ""
        check(nativeInitialize(modelPath)) { "Whisper model could not be opened" }
        // Model loading can be slow. If the watchdog fired while loading,
        // avoid starting an expensive decode after the caller has fallen back.
        if (!shouldContinue()) return ""
        onDecodeStart()
        return nativeTranscribe(samples).trim()
    }
}
