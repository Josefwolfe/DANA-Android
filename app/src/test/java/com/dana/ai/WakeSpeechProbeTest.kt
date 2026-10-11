package com.dana.ai

import org.junit.Assert.*
import org.junit.Test

class WakeSpeechProbeTest {
    @Test fun wakeHypothesisCannotPretendToBeACommand() {
        WakeSpeechProbe.disable()
        assertTrue(WakeSpeechProbe.toggle())
        WakeSpeechProbe.captureWake("دارم")
        assertTrue(WakeSpeechProbe.report().contains("Last command phrase: (none yet)"))
        WakeSpeechProbe.capture("ساعت چنده", "WHISPER_LOCAL")
        WakeSpeechProbe.captureWake("دانا")
        val report = WakeSpeechProbe.report()
        assertTrue(report.contains("Last wake-stage Vosk phrase (not a command): دانا"))
        assertTrue(report.contains("Last command phrase: ساعت چنده"))
        assertTrue(report.contains("Command recognizer: WHISPER_LOCAL"))
        WakeSpeechProbe.disable()
        assertFalse(WakeSpeechProbe.report().contains("ساعت چنده"))
    }
}
