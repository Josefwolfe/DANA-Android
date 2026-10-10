package com.dana.ai

import org.junit.Assert.*
import org.junit.Test

class WakeRecognitionGateTest {
    @Test fun rejectsOldWakeCallbackAfterStartingCommandRecognizer() {
        assertFalse(WakeRecognitionGate.accepts(7L, 5L, "command_offline", "wake"))
        assertFalse(WakeRecognitionGate.accepts(7L, 7L, "command_offline", "wake"))
        assertTrue(WakeRecognitionGate.accepts(7L, 7L, "command_offline", "command_offline"))
    }

    @Test fun rejectsFinalResultAfterRecognizerShutdown() {
        assertFalse(WakeRecognitionGate.accepts(8L, 7L, "wake", "command_offline"))
    }
}
