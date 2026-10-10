package com.dana.ai

import java.util.Locale

/** موتور آفلاین اولیه؛ فرمان‌های پرتکرار بدون اینترنت پردازش می‌شوند. */
sealed class DanaAction {
    object Greeting : DanaAction()
    object Time : DanaAction()
    object Battery : DanaAction()
    object Camera : DanaAction()
    object Contacts : DanaAction()
    object Calendar : DanaAction()
    data class App(val name: String) : DanaAction()
    data class Flashlight(val enabled: Boolean) : DanaAction()
    data class Volume(val direction: Int) : DanaAction()
    data class Call(val contact: String) : DanaAction()
    data class Sms(val contact: String, val message: String?) : DanaAction()
    data class Settings(val section: String) : DanaAction()
    data class Media(val command: String) : DanaAction()
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

    private fun faDigitsToEn(s: String): String {
        val fa = "۰۱۲۳۴۵۶۷۸۹"; val ar = "٠١٢٣٤٥٦٧٨٩"
        return buildString {
            s.forEach { ch ->
                val i = fa.indexOf(ch); val j = ar.indexOf(ch)
                append(when { i >= 0 -> ('0'.code + i).toChar(); j >= 0 -> ('0'.code + j).toChar(); else -> ch })
            }
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
        val s = stripDana(text)
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

        // تایمر نسبی: «۳۰ دقیقه دیگه یادم بنداز ...» یا «تایمر 10 دقیقه»
        val timerSource = faDigitsToEn(s)
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
        if (("دوربین" in s || "عکاسی" in s) && listOf("باز", "اجرا", "بیار").any { it in s }) return DanaAction.Camera
        if (s in setOf("مخاطبین", "مخاطب ها", "دفترچه تلفن") ||
            (("مخاطبین" in s || "دفترچه تلفن" in s || "مخاطب ها" in s) &&
                listOf("باز", "نشون", "نشان", "بیار").any { it in s })) return DanaAction.Contacts
        if ("ساعت چنده" in s || "ساعت چند" in s || "چه ساعتی" in s || "الان ساعت" in s) return DanaAction.Time
        if ("باتری" in s && ("چنده" in s || "درصد" in s || "وضعیت" in s)) return DanaAction.Battery
        if ("تقویم" in s && listOf("باز", "اجرا", "بیار").any { it in s }) return DanaAction.Calendar

        if (("موزیک" in s || "آهنگ" in s || "اهنگ" in s) && listOf("توقف", "مکث", "پاز").any { it in s }) return DanaAction.Media("pause")
        if (("موزیک" in s || "آهنگ" in s || "اهنگ" in s) && listOf("پخش", "ادامه").any { it in s }) return DanaAction.Media("play")
        if (("آهنگ بعدی" in s || "اهنگ بعدی" in s || "بعدی رو پخش" in s)) return DanaAction.Media("next")
        if (("آهنگ قبلی" in s || "اهنگ قبلی" in s || "قبلی رو پخش" in s)) return DanaAction.Media("previous")

        if ("تنظیمات" in s || (("وای فای" in s || "بلوتوث" in s) && listOf("روشن", "خاموش", "باز").any { it in s })) {
            return DanaAction.Settings(when { "وای فای" in s -> "wifi"; "بلوتوث" in s -> "bluetooth"; else -> "general" })
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
            Regex("^(?:برو داخل|برنامه) (.+?)(?: رو باز کن)?$")
        )
        for (r in appPatterns) {
            val name = r.find(s)?.groupValues?.get(1)?.trim()
            if (!name.isNullOrBlank()) return DanaAction.App(name)
        }
        return DanaAction.Unknown(text)
    }
}
