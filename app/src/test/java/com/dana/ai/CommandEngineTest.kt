package com.dana.ai

import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class CommandEngineTest {
    @Test fun persianGreetingIsAnInteractivePrompt() {
        assertEquals(DanaAction.Greeting, CommandEngine.parse("سلام دانا"))
        assertEquals(DanaAction.Greeting, CommandEngine.parse("صبح بخیر دانا"))
    }
    @Test fun fourPersianCallVariants() {
        for (phrase in listOf("یه تماس با رضا بگیر", "رضا رو بگیر", "زنگ بزن به رضا", "با رضا تماس برقرار کن")) {
            // «زنگ بزن به رضا» نیز باید تشخیص داده شود.
            val result = CommandEngine.parse(phrase)
            assertTrue("$phrase: $result", result is DanaAction.Call)
            assertEquals("رضا", (result as DanaAction.Call).contact)
        }
    }
    @Test fun smsBodyIsNotMisclassifiedAsCall() {
        val result = CommandEngine.parse("به رضا بگو وقتی رسیدم تماس می گیرم")
        assertEquals("رضا", (result as DanaAction.Sms).contact)
        assertEquals("وقتی رسیدم تماس می گیرم", result.message)
    }
    @Test fun torchAndApps() {
        assertEquals(DanaAction.Flashlight(true), CommandEngine.parse("سلام دانا چراغ قوه رو روشن کن"))
        assertEquals(DanaAction.App("واتساپ"), CommandEngine.parse("واتساپ رو باز کن"))
    }
    @Test fun arabicCharacterVariants() {
        assertEquals("علی", CommandEngine.normalize("علي"))
    }
    @Test fun politeAndLongerCallVariants() {
        for (phrase in listOf("لطفاً به رضا زنگ بزن", "میشه به رضا زنگ بزن",
            "یک تماس با رضا بگیر", "می‌خواهم با رضا صحبت کنم")) {
            assertEquals(phrase, DanaAction.Call("رضا"), CommandEngine.parse(phrase))
        }
    }
    @Test fun noAccidentalCallWhenMessageMentionsCalling() {
        val message = CommandEngine.parse("به رضا بنویس فردا تماس بگیر")
        assertEquals(DanaAction.Sms("رضا", "فردا تماس بگیر"), message)
    }
    @Test fun explicitNegativeRequestMustNotCall() {
        assertTrue(CommandEngine.parse("به رضا زنگ نزن") is DanaAction.Unknown)
    }
    @Test fun negatedInstructionsAreNeverExecuted() {
        for (phrase in listOf("چراغ قوه رو روشن نکن", "چراغ قوه رو خاموش نکن",
            "صدای گوشی رو کم نکن", "به محمد پیام نفرست", "واتساپ رو باز نکن")) {
            assertTrue(phrase, CommandEngine.parse(phrase) is DanaAction.Unknown)
        }
    }
    @Test fun contactBookCommands() {
        assertEquals(DanaAction.Contacts, CommandEngine.parse("دفترچه تلفن رو باز کن"))
        assertEquals(DanaAction.Contacts, CommandEngine.parse("مخاطبین رو نشون بده"))
    }
    @Test fun remindersPrivacyAndLearning() {
        assertEquals(DanaAction.ReminderIn(15, "برم بیرون"),
            CommandEngine.parse("۱۵ دقیقه دیگه یادم بنداز برم بیرون"))
        assertEquals(DanaAction.ReminderAt(1, 8, 0, "قبض رو پرداخت کنم"),
            CommandEngine.parse("فردا ساعت ۸ یادم بنداز قبض رو پرداخت کنم"))
        assertEquals(DanaAction.ReminderList, CommandEngine.parse("یادآوری هامو بگو"))
        assertEquals(DanaAction.PrivateMode(true), CommandEngine.parse("حالت خصوصی رو فعال کن"))
        assertEquals(DanaAction.LearningMode(false), CommandEngine.parse("یادگیری رو خاموش کن"))
        assertEquals(DanaAction.ResponseStyle("short"), CommandEngine.parse("کوتاه جواب بده"))
    }
    @Test fun reminderTimeMath() {
        val utc = TimeZone.getTimeZone("UTC")
        val base = Calendar.getInstance(utc).apply {
            set(2026, Calendar.SEPTEMBER, 26, 15, 45, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        assertEquals(900_000L, ReminderTimeResolver.inMinutes(base, 15) - base)
        val today = ReminderTimeResolver.atLocal(base, 0, 18, 20, utc)
        val tomorrow = ReminderTimeResolver.atLocal(base, 1, 18, 20, utc)
        assertEquals(86_400_000L, tomorrow - today)
    }

    @Test fun organizerAndMediaCommands() {
        assertEquals(DanaAction.Battery, CommandEngine.parse("باتری چند درصده"))
        assertEquals(DanaAction.Media("pause"), CommandEngine.parse("موزیک رو متوقف کن"))
        assertEquals(DanaAction.Media("next"), CommandEngine.parse("آهنگ بعدی"))
        assertEquals(DanaAction.NoteAdd("خرید نان"), CommandEngine.parse("یادداشت کن خرید نان"))
        assertEquals(DanaAction.NoteList, CommandEngine.parse("یادداشت هامو بگو"))
        assertEquals(DanaAction.TaskAdd("تماس با رضا"), CommandEngine.parse("کار اضافه کن تماس با رضا"))
        assertEquals(DanaAction.TaskList, CommandEngine.parse("کارهای من"))
        assertEquals(DanaAction.Alarm(7, 30, null), CommandEngine.parse("ساعت 7 30 بیدارم کن"))
        assertEquals(DanaAction.Timer(10, null), CommandEngine.parse("تایمر 10 دقیقه"))
    }

}
