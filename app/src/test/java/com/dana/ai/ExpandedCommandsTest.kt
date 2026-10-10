package com.dana.ai

import org.junit.Assert.*
import org.junit.Test

class ExpandedCommandsTest {
    @Test fun everydaySpeechIsRecognized() {
        assertEquals(DanaAction.App("تلگرام"), CommandEngine.parse("دانا میتونی تلگرام رو باز کنی"))
        assertEquals(DanaAction.App("تلگرام"), CommandEngine.parse("تلگرام رو اجرا کنم"))
        assertEquals(DanaAction.Camera, CommandEngine.parse("دوربین رو روشن کن"))
        assertEquals(DanaAction.Flashlight(true), CommandEngine.parse("چراغ قوه رو روشن کنم"))
        assertTrue(CommandEngine.parse("تلگرام رو اجرا نکنم") is DanaAction.Unknown)
        assertTrue(CommandEngine.parse("چراغ قوه رو روشن نکنم") is DanaAction.Unknown)
        assertTrue(CommandEngine.parse("به رضا زنگ بزنم") is DanaAction.Unknown)
        assertEquals(DanaAction.App("واتساپ"), CommandEngine.parse("دانا برو توی واتساپ"))
        assertEquals(DanaAction.Music(null), CommandEngine.parse("دانا یه آهنگ خوب بذار"))
        assertEquals(DanaAction.Media("next"), CommandEngine.parse("دانا بعدیشو بزن"))
        assertEquals(DanaAction.Volume(1), CommandEngine.parse("دانا صدا رو بیشتر کن"))
        assertEquals(DanaAction.Flashlight(false), CommandEngine.parse("دانا چراغ گوشی رو خاموش کن"))
    }

    @Test fun cloudActionsMapToSafeLocalActions() {
        assertEquals(DanaAction.Music(null), AiDecisionGate.accept("MUSIC_RANDOM"))
        assertEquals(DanaAction.Music("شادمهر"), AiDecisionGate.accept("MUSIC_SEARCH", "شادمهر"))
        assertEquals(DanaAction.Media("next"), AiDecisionGate.accept("MEDIA_NEXT"))
        assertEquals(DanaAction.Connectivity("wifi", true), AiDecisionGate.accept("WIFI_ON"))
        assertEquals(DanaAction.Timer(5, null), AiDecisionGate.accept("SET_TIMER", minutes = 5))
        assertEquals(DanaAction.DatedAlarm(1, null, 8, 30, null),
            AiDecisionGate.accept("SET_ALARM", hour = 8, minute = 30, dayOffset = 1))
        assertEquals(DanaAction.Answer("پاریس"), AiDecisionGate.accept("CHAT_ANSWER", answer = "پاریس"))
        assertNull(AiDecisionGate.accept("CHAT_ANSWER", answer = "x\ninvalid"))
        assertNull(AiDecisionGate.accept("DELETE_FILES"))
        assertNull(AiDecisionGate.accept("SET_ALARM", hour = 25, minute = 0))
        assertNull(AiDecisionGate.accept("MUSIC_SEARCH", "bad\nname"))
    }

    @Test fun negatedInstructionsAreNotExecuted() {
        assertTrue(CommandEngine.parse("دانا تلگرام رو باز نکن") is DanaAction.Unknown)
        assertTrue(CommandEngine.parse("دانا موزیک رو پخش نکن") is DanaAction.Unknown)
    }
}
