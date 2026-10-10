package com.dana.ai

/** Pure Kotlin so speech-trigger decisions can be tested without an Android device. */
object WakePhrase {
    private val triggers = listOf("سلام دانا", "هی دانا")

    fun extractAfterWake(recognized: String): String? {
        val normalized = CommandEngine.normalize(recognized)
        for (trigger in triggers) {
            if (normalized == trigger) return ""
            if (normalized.startsWith("$trigger ")) return normalized.removePrefix("$trigger ").trim()
        }
        return null
    }

    fun isWakePhrase(recognized: String) = extractAfterWake(recognized) != null
}
