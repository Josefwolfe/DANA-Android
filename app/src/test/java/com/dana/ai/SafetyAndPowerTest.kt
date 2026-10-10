package com.dana.ai

import org.junit.Assert.*
import org.junit.Test

class SafetyAndPowerTest {
    @Test fun negativeInstructionsAreNotDispatched() {
        assertTrue(CommandEngine.parse("دانا چراغ رو روشن نکن") is DanaAction.Unknown)
        assertTrue(CommandEngine.parse("واتساپ رو باز نکن") is DanaAction.Unknown)
        assertTrue(CommandEngine.parse("به رضا زنگ نزن") is DanaAction.Unknown)
        assertEquals(DanaAction.Sms("رضا", "فردا نمیام"),
            CommandEngine.parse("به رضا بگو فردا نمیام"))
    }

    @Test fun wakeNeverCallsOrSendsFromLockscreen() {
        assertTrue(SafetyPolicy.permittedWithLockedScreen(DanaAction.Time))
        assertFalse(SafetyPolicy.permittedWithLockedScreen(DanaAction.Call("رضا")))
        assertFalse(SafetyPolicy.permittedWithLockedScreen(DanaAction.Sms("رضا", "سلام")))
        assertTrue(SafetyPolicy.alwaysAskBeforeExecution(DanaAction.Call("رضا")))
    }

    @Test fun wakeYieldsToBatterySaver() {
        assertNotNull(WakeEnergyPolicy.stopReason(4, false, false))
        assertNotNull(WakeEnergyPolicy.stopReason(90, true, true))
        assertNull(WakeEnergyPolicy.stopReason(4, true, false))
        assertNull(WakeEnergyPolicy.stopReason(60, false, false))
    }

    @Test fun reminderDaypartMapsToCorrectClock() {
        assertEquals(DanaAction.ReminderAt(1, 18, 0, "ورزش کنم"),
            CommandEngine.parse("فردا عصر ساعت ۶ یادم بنداز ورزش کنم"))
    }
}
