package com.dana.ai

import org.junit.Assert.*
import org.junit.Test

class WhisperWatchdogPolicyTest {
    @Test fun boundedOnlyForLiveWhisperCommands() {
        assertEquals(25_000L, WhisperWatchdogPolicy.COMMAND_TIMEOUT_MS)
        assertTrue(WhisperWatchdogPolicy.shouldTimeout("command_whisper", false))
        assertFalse(WhisperWatchdogPolicy.shouldTimeout("command_whisper", true))
        assertFalse(WhisperWatchdogPolicy.shouldTimeout("wake", false))
        assertFalse(WhisperWatchdogPolicy.shouldTimeout("reply", false))
        assertFalse(WhisperWatchdogPolicy.shouldTimeout("fallback_pending", false))
    }
    @Test fun neverRetryWhisperAfterSessionFailure() {
        assertTrue(WhisperWatchdogPolicy.useWhisper(true, true, false))
        assertFalse(WhisperWatchdogPolicy.useWhisper(true, true, true))
        assertFalse(WhisperWatchdogPolicy.useWhisper(false, true, false))
        assertFalse(WhisperWatchdogPolicy.useWhisper(true, false, false))
    }
}
