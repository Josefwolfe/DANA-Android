package com.dana.ai

import org.junit.Assert.*
import org.junit.Test

class OfflineUpgradeTest {
    @Test fun offlineMediaAndDisplayCommands() {
        assertEquals(DanaAction.MediaMute(true), CommandEngine.parse("دانا صدا رو بی صدا کن"))
        assertEquals(DanaAction.MediaMute(false), CommandEngine.parse("دانا صدا رو وصل کن"))
        assertEquals(DanaAction.Brightness(50), CommandEngine.parse("دانا نور صفحه رو ۵۰ درصد کن"))
        assertEquals(DanaAction.Brightness(null, 1), CommandEngine.parse("دانا روشنایی صفحه رو بیشتر کن"))
        assertEquals(DanaAction.Brightness(null, -1), CommandEngine.parse("دانا نور صفحه رو کمتر کن"))
        assertEquals(DanaAction.Music("شادمهر"), CommandEngine.parse("دانا یه آهنگ از شادمهر بذار"))
        assertEquals(DanaAction.Media("next"), CommandEngine.parse("دانا یه موزیک دیگه بذار"))
    }

    @Test fun offlineOrganizerAndSettingsCommands() {
        assertEquals(DanaAction.NoteAdd("خرید نان"),
            CommandEngine.parse("دانا این رو یادداشت کن خرید نان"))
        assertEquals(DanaAction.TaskAdd("قبض برق"),
            CommandEngine.parse("دانا به کارهام اضافه کن قبض برق"))
        assertEquals(DanaAction.ReminderIn(15, "برم بیرون"),
            CommandEngine.parse("دانا یادم بنداز ۱۵ دقیقه دیگه برم بیرون"))
        assertEquals(DanaAction.Settings("airplane"), CommandEngine.parse("حالت پرواز رو روشن کن"))
        assertEquals(DanaAction.Settings("hotspot"), CommandEngine.parse("هات اسپات رو روشن کن"))
        assertEquals(DanaAction.Settings("mobile_data"), CommandEngine.parse("دیتای موبایل رو روشن کن"))
        assertEquals(DanaAction.Settings("location"), CommandEngine.parse("موقعیت مکانی رو فعال کن"))
        assertEquals(DanaAction.Settings("nfc"), CommandEngine.parse("ان اف سی رو روشن کن"))
    }

    @Test fun safetyAndLockScreenStayConservative() {
        assertTrue(CommandEngine.parse("دانا نور صفحه رو کم نکن") is DanaAction.Unknown)
        assertTrue(CommandEngine.parse("دانا صدا رو قطع نکن") is DanaAction.Unknown)
        assertTrue(CommandEngine.parse("دانا تلگرام رو باز نکن") is DanaAction.Unknown)
        assertFalse(SafetyPolicy.permittedWithLockedScreen(DanaAction.NoteList))
        assertFalse(SafetyPolicy.permittedWithLockedScreen(DanaAction.TaskAdd("private")))
        assertFalse(SafetyPolicy.permittedWithLockedScreen(DanaAction.Brightness(30)))
        assertTrue(SafetyPolicy.permittedWithLockedScreen(DanaAction.TodayDate))
    }
}
