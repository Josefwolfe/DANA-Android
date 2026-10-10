package com.dana.ai

/**
 * Vosk can emit several completed speech segments for a single spoken instruction.
 * Keep them until a short quiet period or the end of the listening window instead
 * of treating the first segment as the whole command. No audio or text is persisted.
 */
class WakeCommandCollector {
    private val segments = mutableListOf<String>()

    fun reset() = segments.clear()

    fun add(raw: String) {
        val text = CommandEngine.normalize(raw).take(240)
        if (text.isBlank() || text == "[unk]" || text == "unk") return
        val previous = segments.lastOrNull()
        when {
            previous == null -> segments.add(text)
            previous == text || previous.startsWith("$text ") -> Unit
            text.startsWith("$previous ") -> segments[segments.lastIndex] = text
            segments.size < 8 -> segments.add(text)
        }
    }

    fun hasSpeech(): Boolean = segments.isNotEmpty()

    fun phrase(): String {
        if (segments.isEmpty()) return ""
        val combined = segments.joinToString(" ").take(320)
        // A negative instruction must never fall back to an earlier positive fragment.
        if (SafetyPolicy.explicitlyNegated(combined)) return combined
        if (CommandEngine.parse(combined) !is DanaAction.Unknown) return combined
        // Some models emit a complete utterance as a later, separate hypothesis.
        return segments.asReversed().firstOrNull {
            CommandEngine.parse(it) !is DanaAction.Unknown
        } ?: combined
    }

    fun isKnown(): Boolean = phrase().let { it.isNotBlank() && CommandEngine.parse(it) !is DanaAction.Unknown }
    fun segmentCount(): Int = segments.size
}
