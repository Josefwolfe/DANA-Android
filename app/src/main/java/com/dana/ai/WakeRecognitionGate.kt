package com.dana.ai

/** Rejects callbacks emitted by a stopped recognizer or by a different listening phase. */
object WakeRecognitionGate {
    fun accepts(activeGeneration: Long, callbackGeneration: Long,
                activeStage: String, expectedStage: String): Boolean =
        activeGeneration == callbackGeneration && activeStage == expectedStage
}
