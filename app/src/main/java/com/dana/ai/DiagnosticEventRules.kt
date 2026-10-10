package com.dana.ai

/** Pure, intentionally restrictive rules: never place spoken text, names or error messages in reports. */
object DiagnosticEventRules {
    private val token = Regex("[A-Z][A-Z0-9_]{0,47}")

    fun safeToken(value: String): String? = value.takeIf { token.matches(it) }

    /** Classify without copying any potentially identifying text from a native exception. */
    fun failureCategory(message: String?): String {
        if (message.isNullOrBlank()) return "NONE"
        val s = message.lowercase(java.util.Locale.ROOT)
        return when {
            "90" in s || "۹۰" in s || "timeout" in s || "طول کشید" in s || "زمان" in s -> "TIMEOUT"
            "permission" in s || "مجوز" in s || "اجازه" in s -> "PERMISSION"
            "battery" in s || "باتری" in s || "شارژ" in s -> "BATTERY"
            "zip" in s || "فایل" in s || "مدل" in s || "model" in s -> "MODEL"
            "microphone" in s || "میکروفون" in s || "audio" in s -> "MICROPHONE"
            "speech" in s || "گفتار" in s || "recognition" in s -> "RECOGNITION"
            else -> "OTHER"
        }
    }
}
