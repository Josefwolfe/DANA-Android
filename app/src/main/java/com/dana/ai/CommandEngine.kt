package com.dana.ai

import java.util.Locale

/** موتور آفلاین اولیه؛ فرمان‌های پرتکرار بدون اینترنت پردازش می‌شوند. */
sealed class DanaAction {
    object Greeting : DanaAction()
    object Time : DanaAction()
    object Battery : DanaAction()
    object TodayDate : DanaAction()
    object Help : DanaAction()
    /** Spoken answer from a separately consented remote AI service, never a phone action. */
    data class Answer(val text: String) : DanaAction()
    data class WebSearch(val query: String) : DanaAction()
    data class YouTubeSearch(val query: String) : DanaAction()
    data class Navigate(val destination: String) : DanaAction()
    object Camera : DanaAction()
    object Contacts : DanaAction()
    object Calendar : DanaAction()
    data class App(val name: String) : DanaAction()
    data class Flashlight(val enabled: Boolean) : DanaAction()
    data class Volume(val direction: Int) : DanaAction()
    /** Changes media stream mute state; never affects microphone capture. */
    data class MediaMute(val enabled: Boolean) : DanaAction()
    /** Requires Android's separate Modify System Settings consent. */
    data class Brightness(val percent: Int?, val direction: Int = 0) : DanaAction()
    data class Call(val contact: String) : DanaAction()
    data class Sms(val contact: String, val message: String?) : DanaAction()
    data class Settings(val section: String) : DanaAction()
    data class Media(val command: String) : DanaAction()
    /** query == null: choose a random locally indexed music track. */
    data class Music(val query: String?) : DanaAction()
    /** Android 10+ generally requires user interaction to change radio state. */
    data class Connectivity(val radio: String, val enabled: Boolean) : DanaAction()
    data class DatedAlarm(val dayOffset: Int?, val weekday: Int?, val hour: Int,
                          val minute: Int, val label: String?) : DanaAction()
    data class PersianDateAlarm(val day: Int, val month: Int, val hour: Int,
                                val minute: Int, val label: String?) : DanaAction()
    data class Alarm(val hour: Int, val minute: Int, val label: String?) : DanaAction()
    data class Timer(val minutes: Int, val label: String?) : DanaAction()
    data class NoteAdd(val text: String) : DanaAction()
    object NoteList : DanaAction()
    data class TaskAdd(val text: String) : DanaAction()
    object TaskList : DanaAction()
    data class TaskDone(val text: String) : DanaAction()
    data class ReminderIn(val minutes: Int, val text: String) : DanaAction()
    data class ReminderAt(val dayOffset: Int, val hour: Int, val minute: Int, val text: String) : DanaAction()
    object ReminderList : DanaAction()
    data class PrivateMode(val enabled: Boolean) : DanaAction()
    data class LearningMode(val enabled: Boolean) : DanaAction()
    data class ResponseStyle(val style: String) : DanaAction()
    data class Unknown(val original: String) : DanaAction()
}

object CommandEngine {
    fun normalize(input: String): String = input.lowercase(Locale.ROOT)
        .replace('ي', 'ی').replace('ك', 'ک')
        .replace('‌', ' ').replace(Regex("[\\u064B-\\u065F]"), "")
        .replace(Regex("[،؛؟!?.:]+"), " ")
        .replace(Regex("\\s+"), " ").trim()

    private fun stripDana(input: String): String {
        var s = normalize(input)
        for (prefix in listOf("سلام دانا ", "دانا ", "هی دانا ")) {
            if (s.startsWith(prefix)) { s = s.removePrefix(prefix).trim(); break }
        }
        return s.replace(Regex("^(?:لطفا|میشه|می شه|ممکنه|خواهشا)\\s+"), "")
            .replace(Regex("\\s+لطفا$"), "").trim()
    }

    /** Common polite/question forms of Persian imperatives, without altering SMS content. */
    private fun everydayPhrase(input: String): String {
        var s = input
        s = s.replace(Regex("^(?:می ?تونی|می ?توانی|میشه|می ?شه|امکانش هست|ممکنه|لطفا|خواهشا)\\s+"), "")
        s = s.replace(Regex("^(?:برام|واسم|برای من)\\s+"), "")
        s = s.replace(Regex("\\s+(?:رو|را)\\s+(باز|اجرا|فعال)\\s+کنی$"), " رو $1 کن")
        // Vosk can mistake «کن» for «کنم/کنی/کنه». Recover ONLY a small,
        // explicitly named set of harmless device/app commands. Never apply this
        // correction to calls, messages, alarms, settings or arbitrary speech.
        s = s.replace(Regex("""^((?:برنامه )?(?:تلگرام|واتساپ|واتس اپ|اینستاگرام|کروم|یوتیوب|گوگل مپ)) (?:رو|را) (باز|اجرا) (?:کنم|کنی|کنه)$"""), "$1 رو $2 کن")
        s = s.replace(Regex("""^((?:چراغ قوه|چراغ گوشی|چراغ|فلش گوشی|فلاش گوشی|فلش|فلاش)) (?:رو|را) (روشن|خاموش) (?:کنم|کنی|کنه)$"""), "$1 رو $2 کن")
        s = s.replace(Regex("""^(دوربین) (?:رو|را) (روشن|باز|اجرا) (?:کنم|کنی|کنه)$"""), "$1 رو $2 کن")
        return s
    }

    private fun faDigitsToEn(s: String): String {
        val fa = "۰۱۲۳۴۵۶۷۸۹"; val ar = "٠١٢٣٤٥٦٧٨٩"
        return buildString {
            s.forEach { ch ->
                val i = fa.indexOf(ch); val j = ar.indexOf(ch)
                append(when { i >= 0 -> ('0'.code + i).toChar(); j >= 0 -> ('0'.code + j).toChar(); else -> ch })
            }
        }
    }

    /** Common spoken Persian clock numbers; avoid changing «سه شنبه» into «3 شنبه». */
    private fun spokenClockDigits(text: String): String {
        val numbers = linkedMapOf("بیست و چهار" to "24", "بیست و سه" to "23",
            "بیست و دو" to "22", "بیست و یک" to "21", "بیست" to "20",
            "نوزده" to "19", "هجده" to "18", "هفده" to "17", "شانزده" to "16",
            "پانزده" to "15", "چهارده" to "14", "سیزده" to "13", "دوازده" to "12",
            "یازده" to "11", "ده" to "10", "نه" to "9", "هشت" to "8",
            "هفت" to "7", "شش" to "6", "پنج" to "5", "چهار" to "4",
            "سه" to "3", "دو" to "2", "یک" to "1", "صفر" to "0")
        var result = text
        for ((word, number) in numbers) {
            val pattern = Regex("(?<!\\S)" + word + "(?!\\S|\\s+شنبه)")
            result = result.replace(pattern, number)
        }
        return result.replace(Regex("(\\d{1,2}) و نیم"), "$1 30")
            .replace(Regex("(\\d{1,2}) و ربع"), "$1 15")
            .replace(Regex("(\\d{1,2}) و (\\d{1,2})"), "$1 $2")
    }

    private fun resolveHour(raw: Int?, daypart: String): Int? {
        if (raw == null || raw !in 0..23) return null
        return when {
            daypart in setOf("عصر", "شب") && raw in 1..11 -> raw + 12
            daypart in setOf("صبح", "شب") && raw == 12 -> 0
            else -> raw
        }
    }

    private fun originalMessageBody(input: String): String? {
        val raw = input.trim().replace(Regex("^(?:سلام\\s+دانا|دانا)\\s*[,،]?\\s*"), "")
        val tokens = listOf(" پیام بفرست", " پیام بده", " اس ام اس بفرست", " بنویس", " خبر بده", " بگو", " بفرست")
        for (token in tokens) {
            val index = raw.indexOf(token)
            if (index >= 0) return raw.substring(index + token.length).trim().removePrefix("که ").trim().ifEmpty { null }
        }
        return null
    }

    fun parse(text: String): DanaAction {
        val s = everydayPhrase(stripDana(text))
        // Negative imperatives override every matching positive command.
        if (SafetyPolicy.explicitlyNegated(s)) return DanaAction.Unknown(text)
        if (s.isBlank()) return DanaAction.Unknown(text)
        if (s in setOf("سلام دانا", "سلام", "دانا", "صبح بخیر دانا", "سلام خوبی دانا"))
            return DanaAction.Greeting

        // تنظیمات حفظ حریم خصوصی و حافظه؛ مستقل از شبکه.
        if (s in setOf("حالت خصوصی روشن", "حالت خصوصی رو روشن کن", "حالت خصوصی روشن کن", "حالت خصوصی رو فعال کن")) return DanaAction.PrivateMode(true)
        if (s in setOf("حالت خصوصی خاموش", "حالت خصوصی رو خاموش کن", "حالت خصوصی خاموش کن", "حالت خصوصی رو غیرفعال کن")) return DanaAction.PrivateMode(false)
        if (s in setOf("یادگیری رو خاموش کن", "حافظه یادگیری رو خاموش کن")) return DanaAction.LearningMode(false)
        if (s in setOf("یادگیری رو روشن کن", "حافظه یادگیری رو روشن کن")) return DanaAction.LearningMode(true)
        if (s in setOf("کوتاه جواب بده", "مختصر جواب بده", "خلاصه جواب بده")) return DanaAction.ResponseStyle("short")
        if (s in setOf("دوستانه جواب بده", "صمیمی جواب بده")) return DanaAction.ResponseStyle("friendly")

        // Voice-first everyday requests, handled locally without an AI subscription.
        if (s in setOf("امروز چندمه", "امروز چندم است", "تاریخ امروز", "تاریخ چنده", "امروز تاریخ چندمه", "امروز چه روزیه",
                "امروز چه روزی است", "امروز چند شنبه است", "امروز چندشنبه است"))
            return DanaAction.TodayDate
        if (s in setOf("راهنما", "کمک", "چه کارهایی می تونی بکنی", "چه کارهایی میتونی بکنی",
                "چه فرمان هایی بلدی", "چه فرمان هایی رو بلدی", "چه کارهایی بلدی"))
            return DanaAction.Help
        Regex("^(?:توی|در) یوتیوب (.+?) (?:رو|را) (?:پیدا کن|جستجو کن|سرچ کن)$")
            .find(s)?.let { return DanaAction.YouTubeSearch(it.groupValues[1].trim()) }
        Regex("^(?:ویدیو|فیلم) (.+?) (?:رو|را) (?:در|توی) یوتیوب (?:پیدا کن|جستجو کن)$")
            .find(s)?.let { return DanaAction.YouTubeSearch(it.groupValues[1].trim()) }
        Regex("^(?:مسیریابی|مسیر یابی) (?:به|تا|برای) (.+)$")
            .find(s)?.let { return DanaAction.Navigate(it.groupValues[1].trim()) }
        Regex("^مسیر (?:به|تا) (.+?) (?:رو|را) (?:نشون بده|نشان بده|باز کن)$")
            .find(s)?.let { return DanaAction.Navigate(it.groupValues[1].trim()) }
        Regex("^منو ببر (?:به|تا) (.+)$")
            .find(s)?.let { return DanaAction.Navigate(it.groupValues[1].trim()) }
        Regex("^(?:جستجو کن|جست و جو کن|سرچ کن|در اینترنت جستجو کن) (.+)$")
            .find(s)?.let { return DanaAction.WebSearch(it.groupValues[1].trim()) }
        Regex("^(?:توی|در) گوگل (.+?) (?:رو|را) (?:سرچ کن|جستجو کن)$")
            .find(s)?.let { return DanaAction.WebSearch(it.groupValues[1].trim()) }

        // Date-specific alarms: weekdays, tomorrow and Persian calendar dates.
        // The Android Clock ACTION_SET_ALARM does not support an arbitrary date;
        // these are handled by DANA's alarm-clock scheduler instead.
        val alarmSource = spokenClockDigits(faDigitsToEn(s))
        val weekdays = mapOf("شنبه" to java.util.Calendar.SATURDAY,
            "یکشنبه" to java.util.Calendar.SUNDAY, "یک شنبه" to java.util.Calendar.SUNDAY,
            "دوشنبه" to java.util.Calendar.MONDAY, "دو شنبه" to java.util.Calendar.MONDAY,
            "سه شنبه" to java.util.Calendar.TUESDAY, "سهشنبه" to java.util.Calendar.TUESDAY,
            "چهارشنبه" to java.util.Calendar.WEDNESDAY, "چهار شنبه" to java.util.Calendar.WEDNESDAY,
            "پنجشنبه" to java.util.Calendar.THURSDAY, "پنج شنبه" to java.util.Calendar.THURSDAY,
            "جمعه" to java.util.Calendar.FRIDAY)
        val persianMonths = listOf("فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور",
            "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند")
        val dateWord = "(امروز|فردا|پس فردا|پسفردا|شنبه|یکشنبه|یک شنبه|دوشنبه|دو شنبه|سه شنبه|سهشنبه|چهارشنبه|چهار شنبه|پنجشنبه|پنج شنبه|جمعه)"
        val datePattern = Regex("^(?:برای )?" + dateWord + " (?:آلارم |زنگ هشدار )?(?:رو )?(?:برای )?(?:ساعت )?(\\d{1,2})(?:[: ](\\d{1,2}))? (?:(صبح|ظهر|عصر|شب) )?(?:آلارم |زنگ هشدار )?(?:رو )?(?:بیدارم کن|بذار|بزار|بگذار|تنظیم کن|فعال کن)(?: (.*))?$")
        val dateMatch = datePattern.find(alarmSource)
        if (dateMatch != null) {
            val dayName = dateMatch.groupValues[1]
            val h = resolveHour(dateMatch.groupValues[2].toIntOrNull(), dateMatch.groupValues[4])
            val minute = dateMatch.groupValues[3].toIntOrNull() ?: 0
            if (h != null && minute in 0..59) {
                val offset = when (dayName) { "امروز" -> 0; "فردا" -> 1;
                    "پس فردا", "پسفردا" -> 2; else -> null }
                return DanaAction.DatedAlarm(offset, weekdays[dayName], h, minute,
                    dateMatch.groupValues[5].trim().ifEmpty { null })
            }
        }
        val persianDatePattern = Regex("^(?:برای )?(\\d{1,2}) (" + persianMonths.joinToString("|") +
            ") (?:آلارم |زنگ هشدار )?(?:رو )?(?:برای )?(?:ساعت )?(\\d{1,2})(?:[: ](\\d{1,2}))? (?:(صبح|ظهر|عصر|شب) )?(?:آلارم |زنگ هشدار )?(?:رو )?(?:بیدارم کن|بذار|بزار|بگذار|تنظیم کن|فعال کن)(?: (.*))?$")
        persianDatePattern.find(alarmSource)?.let { m ->
            val day = m.groupValues[1].toIntOrNull()
            val month = persianMonths.indexOf(m.groupValues[2]) + 1
            val hour = resolveHour(m.groupValues[3].toIntOrNull(), m.groupValues[5])
            val minute = m.groupValues[4].toIntOrNull() ?: 0
            if (day != null && day in 1..31 && hour != null && minute in 0..59) {
                return DanaAction.PersianDateAlarm(day, month, hour, minute,
                    m.groupValues[6].trim().ifEmpty { null })
            }
        }

        // یادآوری داخل خود برنامه؛ برخلاف تایمر خارجی پس از بسته شدن برنامه هم اعلان دارد.
        if (s in setOf("یادآوری هام", "یادآوری ها", "یادآوری هامو بگو", "یادآوری های من", "یادآوری ها رو بگو")) return DanaAction.ReminderList
        val reminderSource = faDigitsToEn(s)
        Regex("^(?:تا )?(\\d{1,4}) دقیقه (?:دیگه|دیگر|بعد) (?:یادم بنداز|یادآوری کن)(?: که)? (.+)$")
            .find(reminderSource)?.let { m ->
                val n = m.groupValues[1].toIntOrNull()
                if (n != null && n in 1..10080) return DanaAction.ReminderIn(n, m.groupValues[2].trim())
            }
        Regex("^(\\d{1,3}) ساعت (?:دیگه|دیگر|بعد) (?:یادم بنداز|یادآوری کن)(?: که)? (.+)$")
            .find(reminderSource)?.let { m ->
                val n = m.groupValues[1].toIntOrNull()
                if (n != null && n in 1..168) return DanaAction.ReminderIn(n * 60, m.groupValues[2].trim())
            }
        Regex("^نیم ساعت (?:دیگه|دیگر|بعد) (?:یادم بنداز|یادآوری کن)(?: که)? (.+)$")
            .find(reminderSource)?.let { return DanaAction.ReminderIn(30, it.groupValues[1].trim()) }
        // Respect colloquial dayparts: «فردا عصر ساعت ۶» means 18:00, not 06:00.
        Regex("^(فردا|امروز) (?:(صبح|عصر|شب) )?(?:ساعت )?(\\d{1,2})(?:[: ](\\d{1,2}))? (?:(صبح|عصر|شب) )?(?:یادم بنداز|یادآوری کن)(?: که)? (.+)$")
            .find(reminderSource)?.let { m ->
                val rawHour = m.groupValues[3].toIntOrNull()
                val min = m.groupValues[4].toIntOrNull() ?: 0
                val daypart = m.groupValues[2].ifBlank { m.groupValues[5] }
                val reminderText = m.groupValues[6].trim()
                val h = if (rawHour != null && rawHour in 0..23) when {
                    daypart in setOf("عصر", "شب") && rawHour in 1..11 -> rawHour + 12
                    daypart == "شب" && rawHour == 12 -> 0
                    daypart == "صبح" && rawHour == 12 -> 0
                    else -> rawHour
                } else null
                if (h != null && min in 0..59 && reminderText.isNotEmpty()) {
                    return DanaAction.ReminderAt(if (m.groupValues[1] == "فردا") 1 else 0, h, min, reminderText)
                }
            }

        // یادداشت و کارها زودتر از الگوی عمومی «باز کن» بررسی می‌شوند.
        Regex("^(?:یادداشت کن|یادداشت بنویس|نوت کن) (.+)$").find(s)?.let { return DanaAction.NoteAdd(it.groupValues[1].trim()) }
        if (s in setOf("یادداشت هام", "یادداشت ها", "یادداشت هامو بگو", "یادداشت ها رو بگو", "یادداشت ها را بگو")) return DanaAction.NoteList
        Regex("^(?:کار اضافه کن|وظیفه اضافه کن|تسک اضافه کن) (.+)$").find(s)?.let { return DanaAction.TaskAdd(it.groupValues[1].trim()) }
        Regex("^(.+?) (?:رو|را) انجام شده بزن$").find(s)?.let { return DanaAction.TaskDone(it.groupValues[1].trim()) }
        if (s in setOf("کار هام", "کارها", "کارهای من", "کارهای امروز", "تسک هام", "وظایف من")) return DanaAction.TaskList

        // More natural offline reminders, without a server or generative model.
        Regex("^(?:یادم بنداز|یادآوری کن) (?:تا|که)? ?(\\d{1,4}) دقیقه (?:دیگه|دیگر|بعد) (.+)$")
            .find(faDigitsToEn(s))?.let { m ->
                val minutes = m.groupValues[1].toIntOrNull()
                if (minutes != null && minutes in 1..10080)
                    return DanaAction.ReminderIn(minutes, m.groupValues[2].trim())
            }

        // تایمر نسبی: «۳۰ دقیقه دیگه یادم بنداز ...» یا «تایمر 10 دقیقه»
        val timerSource = spokenClockDigits(faDigitsToEn(s))
        Regex("(?:تایمر|(?:(\\d+) دقیقه دیگه))(?: (\\d+) دقیقه)?(?:.*?(?:برای|که|یادم بنداز))?\\s*(.*)")
            .find(timerSource)?.let { m ->
                val minutes = (m.groupValues[1].ifBlank { m.groupValues[2] }).toIntOrNull()
                if (minutes != null && minutes in 1..1440) return DanaAction.Timer(minutes, m.groupValues[3].trim().ifEmpty { null })
            }
        Regex("^تایمر (\\d+) دقیقه(?: برای (.*))?$").find(timerSource)?.let { m ->
            return DanaAction.Timer(m.groupValues[1].toInt(), m.groupValues[2].trim().ifEmpty { null })
        }

        // آلارم ساعت مشخص: «ساعت 7:30 بیدارم کن»
        Regex("^(?:فردا )?(?:ساعت )?(\\d{1,2})(?:[ :](\\d{1,2}))? (?:صبح |شب )?(?:بیدارم کن|آلارم بذار|آلارم بگذار)(?: (.*))?$")
            .find(timerSource)?.let { m ->
                val h = m.groupValues[1].toIntOrNull(); val min = m.groupValues[2].toIntOrNull() ?: 0
                if (h != null && h in 0..23 && min in 0..59) return DanaAction.Alarm(h, min, m.groupValues[3].trim().ifEmpty { null })
            }

        Regex("^(?:این رو|این را|اینو) یادداشت کن(?: که)? (.+)$")
            .find(s)?.let { return DanaAction.NoteAdd(it.groupValues[1].trim()) }
        Regex("^(?:به لیست کارهام اضافه کن|به کارهام اضافه کن) (.+)$")
            .find(s)?.let { return DanaAction.TaskAdd(it.groupValues[1].trim()) }

        // Persian screen brightness and sound commands. No special permission is
        // silently granted: Android will require the user to approve brightness control.
        val brightness = faDigitsToEn(s)
        Regex("^(?:نور|روشنایی) (?:صفحه|نمایشگر|گوشی)(?: رو| را)? (\\d{1,3}) ?(?:درصد|٪) (?:کن|بذار|بزار|تنظیم کن)$")
            .find(brightness)?.let { m ->
                val percent = m.groupValues[1].toIntOrNull()
                if (percent != null && percent in 1..100) return DanaAction.Brightness(percent)
            }
        if (Regex("(?:نور|روشنایی) (?:صفحه|نمایشگر|گوشی)").containsMatchIn(s)) {
            if (listOf("بیشتر", "زیاد", "بالا", "روشن تر").any { it in s })
                return DanaAction.Brightness(null, 1)
            if (listOf("کمتر", "کم", "پایین", "تاریک تر").any { it in s })
                return DanaAction.Brightness(null, -1)
        }
        if (s in setOf("صدا رو بی صدا کن", "صدای گوشی رو قطع کن", "صدای موزیک رو قطع کن",
                "موزیک رو بی صدا کن", "صدا رو میوت کن", "صدا رو سایلنت کن"))
            return DanaAction.MediaMute(true)
        if (s in setOf("صدا رو از بی صدایی دربیار", "صدا رو وصل کن", "صدا رو آن میوت کن",
                "صدای موزیک رو وصل کن", "صدا رو دوباره فعال کن"))
            return DanaAction.MediaMute(false)

        // Settings that Android only allows the user to change in a system panel.
        // We never claim that DANA directly toggled these restricted radios.
        if (listOf("حالت پرواز", "مود پرواز", "ایرپلین مود").any { it in s })
            return DanaAction.Settings("airplane")
        if (listOf("نقطه اتصال", "هات اسپات", "هاتسپات", "اینترنت اشتراکی").any { it in s })
            return DanaAction.Settings("hotspot")
        if (listOf("داده همراه", "دیتای موبایل", "اینترنت سیم کارت", "دیتا گوشی").any { it in s })
            return DanaAction.Settings("mobile_data")
        if (("مکان یاب" in s || "موقعیت مکانی" in s || "جی پی اس" in s) &&
            listOf("روشن", "خاموش", "فعال", "غیرفعال", "تنظیمات", "باز").any { it in s })
            return DanaAction.Settings("location")
        if (("ان اف سی" in s || "nfc" in s) &&
            listOf("روشن", "خاموش", "فعال", "غیرفعال", "تنظیمات", "باز").any { it in s })
            return DanaAction.Settings("nfc")

        // SMS مقدم بر تماس است.
        val smsPatterns = listOf(
            Regex("^(?:به|برای) (.+?) (?:پیام بده|پیام بفرست|اس ام اس بفرست|بنویس|بگو|خبر بده)(?: که)?(?: (.*))?$"),
            Regex("^(?:پیام|اس ام اس) (?:به|برای) (.+?) بفرست(?: که)?(?: (.*))?$")
        )
        for (r in smsPatterns) {
            val m = r.find(s) ?: continue
            val contact = m.groupValues[1].trim()
            val body = originalMessageBody(text) ?: m.groupValues.getOrElse(2) { "" }.trim().ifEmpty { null }
            if (contact.isNotEmpty()) return DanaAction.Sms(contact, body)
        }

        if (("چراغ" in s || "فلاش" in s) && ("روشن" in s || "خاموش" in s)) return DanaAction.Flashlight("روشن" in s)
        if (("صدا" in s || "ولوم" in s) && listOf("کم", "زیاد", "بلند").any { it in s }) return DanaAction.Volume(if ("کم" in s) -1 else 1)
        if (("دوربین" in s || "عکاسی" in s) && listOf("باز", "اجرا", "بیار", "روشن").any { it in s }) return DanaAction.Camera
        if (s in setOf("مخاطبین", "مخاطب ها", "دفترچه تلفن") ||
            (("مخاطبین" in s || "دفترچه تلفن" in s || "مخاطب ها" in s) &&
                listOf("باز", "نشون", "نشان", "بیار").any { it in s })) return DanaAction.Contacts
        if ("ساعت چنده" in s || "ساعت چند" in s || "چه ساعتی" in s || "الان ساعت" in s) return DanaAction.Time
        if (("باتری" in s && ("چنده" in s || "درصد" in s || "وضعیت" in s)) ||
            s in setOf("شارژ چنده", "شارژ گوشی چنده", "چقدر شارژ دارم")) return DanaAction.Battery
        if ("تقویم" in s && listOf("باز", "اجرا", "بیار").any { it in s }) return DanaAction.Calendar
        // Vosk sometimes drops the final imperative word of short app commands.
        Regex("^(?:برنامه )?(تلگرام|واتساپ|واتس اپ|اینستاگرام|کروم|یوتیوب)(?: رو| را)? باز$")
            .find(s)?.let { return DanaAction.App(it.groupValues[1]) }

        // Music controls are checked before generic app launching. Context-free
        // «عوض کن / ادامه بده» refer to the active player in hands-free mode.
        val musicWord = "(?:موزیک|موسیقی|آهنگ|اهنگ|ترانه)"
        if (s in setOf("استپ کن", "مکث کن", "پاز کن", "توقف کن", "متوقف کن") ||
            (Regex(musicWord).containsMatchIn(s) &&
                listOf("استپ", "مکث", "پاز", "متوقف", "توقف").any { it in s }))
            return DanaAction.Media("pause")
        if (s in setOf("موزیک رو قطع کن", "موسیقی رو قطع کن", "پخش رو قطع کن",
                "پخش موسیقی رو قطع کن", "موزیک رو کامل قطع کن")) return DanaAction.Media("stop")
        if (s in setOf("عوض کن", "عوضش کن", "بعدی", "آهنگ بعدی", "اهنگ بعدی",
                "موزیک بعدی", "بعدی رو پخش کن") ||
            (Regex(musicWord).containsMatchIn(s) && listOf("بعدی", "عوض").any { it in s }))
            return DanaAction.Media("next")
        if (s in setOf("قبلی", "قبلی رو پخش کن", "آهنگ قبلی", "اهنگ قبلی", "موزیک قبلی"))
            return DanaAction.Media("previous")
        if (s in setOf("ادامه بده", "پلی کن", "پخش رو ادامه بده", "موزیک رو ادامه بده",
                "آهنگ رو ادامه بده", "موسیقی رو ادامه بده")) return DanaAction.Media("resume")
        Regex("^(?:موزیک|آهنگ|اهنگ) (?:به اسم|با نام) (.+?) (?:رو |را )?(?:پخش کن|بذار|بزار|پیدا کن)$")
            .find(s)?.let { return DanaAction.Music(it.groupValues[1].trim()) }
        Regex("^" + musicWord + " (.+?) (?:رو |را )?(?:پخش کن|بذار|بزار|پیدا کن|اجرا کن)$")
            .find(s)?.let { m ->
                val query = m.groupValues[1].trim()
                if (query !in setOf("رو", "را", "یه", "یک", "برام", "برای من", "رو برام"))
                    return DanaAction.Music(query)
            }
        if (s in setOf("یه موزیک دیگه بذار", "یه آهنگ دیگه بذار", "یه موزیک دیگه پخش کن",
                "یه آهنگ دیگه پخش کن", "آهنگ بعدی رو بزن", "این آهنگ رو عوض کن"))
            return DanaAction.Media("next")
        if (s in setOf("آهنگ رو دوباره پخش کن", "موزیک رو دوباره پخش کن", "دوباره پلی کن"))
            return DanaAction.Media("resume")
        if (s in setOf("موزیک پخش", "آهنگ پخش", "موزیک پخش کن", "یه موزیک پخش کن", "یک موزیک پخش کن",
                "یه آهنگ پخش کن", "یک آهنگ پخش کن", "آهنگ پخش کن", "آهنگ بذار",
                "موزیک بذار", "موزیک بزار", "یه موزیک بذار", "موسیقی پخش کن",
                "برنامه موزیک رو فعال کن", "موزیک رو فعال کن", "موزیک رو باز کن",
                "موزیک رو پخش کن", "آهنگ رو پخش کن", "پخش موسیقی"))
            return DanaAction.Music(null)
        Regex("^(?:یه |یک )?(?:آهنگ|اهنگ|موزیک) (?:از|از خواننده) (.+?) (?:رو |را )?(?:پخش کن|بذار|بزار|بزن)$")
            .find(s)?.let { return DanaAction.Music(it.groupValues[1].trim()) }
        Regex("^(?:آهنگ|اهنگ|موزیک) (.+?) (?:رو |را )?(?:پیدا کن و پخش کن|سرچ کن و پخش کن)$")
            .find(s)?.let { return DanaAction.Music(it.groupValues[1].trim()) }
        Regex("^(.+?) (?:آهنگ|اهنگ|موزیک) (?:رو |را )?(?:پخش کن|بذار|بزار)$")
            .find(s)?.let { return DanaAction.Music(it.groupValues[1].trim()) }
        if (Regex(musicWord).containsMatchIn(s) && "ادامه" in s)
            return DanaAction.Media("resume")
        if ("وای فای" in s || "وایفای" in s || "wifi" in s || "بلوتوث" in s) {
            val radio = if ("بلوتوث" in s) "bluetooth" else "wifi"
            if (listOf("روشن", "فعال", "وصل", "خاموش", "غیرفعال", "قطع").any { it in s }) {
                val enabled = listOf("روشن", "فعال", "وصل").any { it in s } &&
                    listOf("خاموش", "غیرفعال", "قطع").none { it in s }
                return DanaAction.Connectivity(radio, enabled)
            }
        }
        if ("تنظیمات" in s || ("وای فای" in s && "باز" in s) ||
            ("بلوتوث" in s && "باز" in s)) {
            return DanaAction.Settings(when { "وای فای" in s -> "wifi";
                "بلوتوث" in s -> "bluetooth"; else -> "general" })
        }

        val callPatterns = listOf(
            Regex("^(?:به|با) (.+?) (?:زنگ بزن|تماس بگیر|تماس برقرار کن|حرف بزن|صحبت کن)$"),
            Regex("^(?:(?:یه|یک) )?(?:زنگ|تماس) (?:به|با) (.+?) (?:بزن|بگیر|برقرار کن)$"),
            Regex("^زنگ بزن (?:به|با) (.+)$"),
            Regex("^(.+?) (?:رو|را) (?:بگیر|وصل کن)$"),
            Regex("^(?:می خوام|می خواهم|میخوام|میخواهم) با (.+?) (?:حرف بزنم|صحبت کنم)$")
        )
        for (r in callPatterns) {
            val c = r.find(s)?.groupValues?.get(1)?.trim()
            if (!c.isNullOrBlank()) return DanaAction.Call(c)
        }

        val appPatterns = listOf(
            Regex("^(.+?) (?:رو|را) (?:باز کن|اجرا کن|بیار بالا|بیار)$"),
            Regex("^(?:برنامه )?(.+?) (?:رو|را) (?:روشن کن|بالا بیار)$"),
            Regex("^(?:برو داخل|برنامه) (.+?)(?: رو باز کن)?$")
        )
        for (r in appPatterns) {
            val name = r.find(s)?.groupValues?.get(1)?.trim()
            if (!name.isNullOrBlank()) return DanaAction.App(name)
        }
        // Extra colloquial phrasing that otherwise falls through the strict offline grammar.
        // Do not treat an arbitrary question as an executable phone operation.
        val flexible = s.replace(Regex("\\s+"), " ").trim()
        if (flexible in setOf("موزیک بزن", "یه آهنگ بزار", "یه آهنگ بذار", "یه آهنگ برام بذار",
                "یه آهنگ خوب بذار", "یه موزیک خوب بذار", "یه آهنگ تصادفی بذار", "یه آهنگ بزن",
                "پخش کن یه آهنگ", "پخش کن یه موزیک", "یه موزیک از گوشیم پخش کن"))
            return DanaAction.Music(null)
        if (flexible in setOf("برو آهنگ بعدی", "بعدیش", "بعدیشو بزن", "بعدیش رو بزن", "ردش کن",
                "این آهنگ رو رد کن", "این موزیک رو رد کن")) return DanaAction.Media("next")
        if (flexible in setOf("موزیکو نگه دار", "آهنگو نگه دار", "آهنگ رو نگه دار", "پخش رو نگه دار"))
            return DanaAction.Media("pause")
        if (flexible in setOf("دوباره پخشش کن", "دوباره پلی کن", "از همون جا ادامه بده"))
            return DanaAction.Media("resume")
        if (flexible in setOf("صدا رو بیشتر کن", "صدای گوشی رو بیشتر کن", "ولوم رو ببر بالا"))
            return DanaAction.Volume(1)
        if (flexible in setOf("صدا رو کمتر کن", "صدای گوشی رو کمتر کن", "ولوم رو بیار پایین"))
            return DanaAction.Volume(-1)
        if (flexible in setOf("فلش گوشی رو روشن کن", "چراغ گوشی رو روشن کن"))
            return DanaAction.Flashlight(true)
        if (flexible in setOf("فلش گوشی رو خاموش کن", "چراغ گوشی رو خاموش کن"))
            return DanaAction.Flashlight(false)
        Regex("^(?:برو تو|برو توی|برو داخل) (.+)$").find(flexible)?.let {
            return DanaAction.App(it.groupValues[1].trim())
        }
        Regex("^(.+?)(?:و| رو| را) (?:وا کن|بازش کن)$").find(flexible)?.let {
            return DanaAction.App(it.groupValues[1].trim())
        }
        Regex("^(?:پخش کن|بذار|بزار) (?:آهنگ|موزیک) (.+)$").find(flexible)?.let {
            return DanaAction.Music(it.groupValues[1].trim())
        }
        return DanaAction.Unknown(text)
    }
}
