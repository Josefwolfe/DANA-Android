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
    fun transcribe(modelPath: String, samples: FloatArray): String {
        check(available) { "Whisper native library is not available" }
        require(samples.isNotEmpty() && samples.size <= 16000 * 15) { "Invalid recording length" }
        check(nativeInitialize(modelPath)) { "Whisper model could not be opened" }
        return nativeTranscribe(samples).trim()
    }
}
