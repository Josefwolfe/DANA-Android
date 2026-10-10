package com.dana.ai

import java.util.Calendar
import java.util.TimeZone

/** آفلاین، بدون SDK و بدون نیاز به میکروفون: آزمون موتور فرمان‌های فارسی. */
fun main() {
    val cases = listOf(
        "سلام دانا" to DanaAction.Greeting,
        "صبح بخیر دانا" to DanaAction.Greeting,
        "یه تماس با رضا بگیر" to DanaAction.Call("رضا"),
        "یک تماس با رضا بگیر" to DanaAction.Call("رضا"),
        "رضا رو بگیر" to DanaAction.Call("رضا"),
        "زنگ بزن به رضا" to DanaAction.Call("رضا"),
        "با رضا تماس برقرار کن" to DanaAction.Call("رضا"),
        "سلام دانا به رضا زنگ بزن" to DanaAction.Call("رضا"),
        "دانا با رضا تماس بگیر" to DanaAction.Call("رضا"),
        "می‌خوام با رضا حرف بزنم" to DanaAction.Call("رضا"),
        "می‌خواهم با رضا صحبت کنم" to DanaAction.Call("رضا"),
        "یه زنگ به رضا بزن" to DanaAction.Call("رضا"),
        "با رضا صحبت کن" to DanaAction.Call("رضا"),
        "علي رو بگير" to DanaAction.Call("علی"),
        "لطفاً به رضا زنگ بزن" to DanaAction.Call("رضا"),
        "به رضا زنگ بزن لطفاً" to DanaAction.Call("رضا"),
        "میشه به رضا زنگ بزن" to DanaAction.Call("رضا"),
        "به رضا بگو وقتی رسیدم تماس می‌گیرم" to DanaAction.Sms("رضا", "وقتی رسیدم تماس می‌گیرم"),
        "برای علی پیام بفرست که فردا میام" to DanaAction.Sms("علی", "فردا میام"),
        "دانا برای مادر بنویس سلام" to DanaAction.Sms("مادر", "سلام"),
        "برای علی پیام بده" to DanaAction.Sms("علی", null),
        "پیام به رضا بفرست که دیر می‌رسم" to DanaAction.Sms("رضا", "دیر می‌رسم"),
        "به محمد خبر بده فردا نمیام" to DanaAction.Sms("محمد", "فردا نمیام"),
        "به رضا پیام بده که سلام" to DanaAction.Sms("رضا", "سلام"),
        "به رضا بنویس فردا تماس بگیر" to DanaAction.Sms("رضا", "فردا تماس بگیر"),
        "به رضا بگو چراغ رو روشن نکن" to DanaAction.Sms("رضا", "چراغ رو روشن نکن"),
        "واتساپ رو باز کن" to DanaAction.App("واتساپ"),
        "تلگرام رو اجرا کن" to DanaAction.App("تلگرام"),
        "برو داخل کروم" to DanaAction.App("کروم"),
        "اینستاگرام رو بیار بالا" to DanaAction.App("اینستاگرام"),
        "برنامه یوتیوب" to DanaAction.App("یوتیوب"),
        "سلام دانا چراغ قوه رو روشن کن" to DanaAction.Flashlight(true),
        "چراغ قوه رو خاموش کن" to DanaAction.Flashlight(false),
        "صدای گوشی رو کم کن" to DanaAction.Volume(-1),
        "ولوم رو زیاد کن" to DanaAction.Volume(1),
        "دوربین رو باز کن" to DanaAction.Camera,
        "تقویم رو باز کن" to DanaAction.Calendar,
        "تنظیمات وای فای رو باز کن" to DanaAction.Settings("wifi"),
        "بلوتوث رو روشن کن" to DanaAction.Connectivity("bluetooth", true),
        "ساعت چنده" to DanaAction.Time,
        "لطفا ساعت چنده؟" to DanaAction.Time,
        "باتری چند درصده" to DanaAction.Battery,
        "موزیک رو متوقف کن" to DanaAction.Media("pause"),
        "آهنگ بعدی" to DanaAction.Media("next"),
        "یادداشت کن خرید نان" to DanaAction.NoteAdd("خرید نان"),
        "یادداشت هامو بگو" to DanaAction.NoteList,
        "کار اضافه کن تماس با رضا" to DanaAction.TaskAdd("تماس با رضا"),
        "کارهای من" to DanaAction.TaskList,
        "تماس با رضا رو انجام شده بزن" to DanaAction.TaskDone("تماس با رضا"),
        "ساعت 7 30 بیدارم کن" to DanaAction.Alarm(7, 30, null),
        "تایمر 10 دقیقه" to DanaAction.Timer(10, null),
        "۱۵ دقیقه دیگه یادم بنداز برم بیرون" to DanaAction.ReminderIn(15, "برم بیرون"),
        "۳۰ دقیقه دیگه یادآوری کن آب بخورم" to DanaAction.ReminderIn(30, "آب بخورم"),
        "نیم ساعت بعد یادم بنداز تماس بگیرم" to DanaAction.ReminderIn(30, "تماس بگیرم"),
        "۲ ساعت دیگه یادم بنداز فایل رو بفرستم" to DanaAction.ReminderIn(120, "فایل رو بفرستم"),
        "فردا ساعت ۸ یادم بنداز قبض رو پرداخت کنم" to DanaAction.ReminderAt(1, 8, 0, "قبض رو پرداخت کنم"),
        "فردا ساعت ۷:۳۰ یادآوری کن جلسه دارم" to DanaAction.ReminderAt(1, 7, 30, "جلسه دارم"),
        "امروز ساعت ۲۱ یادم بنداز کتاب بخونم" to DanaAction.ReminderAt(0, 21, 0, "کتاب بخونم"),
        "فردا صبح ساعت ۹ یادم بنداز داروخانه برم" to DanaAction.ReminderAt(1, 9, 0, "داروخانه برم"),
        "فردا عصر ساعت ۶ یادم بنداز برم باشگاه" to DanaAction.ReminderAt(1, 18, 0, "برم باشگاه"),
        "امروز شب ساعت ۹ یادآوری کن لباس بشورم" to DanaAction.ReminderAt(0, 21, 0, "لباس بشورم"),
        "فردا ساعت ۷ شب یادم بنداز به بابا زنگ بزنم" to DanaAction.ReminderAt(1, 19, 0, "به بابا زنگ بزنم"),
        "فردا ساعت ۶:۴۵ عصر یادم بنداز خرید کنم" to DanaAction.ReminderAt(1, 18, 45, "خرید کنم"),
        "یادآوری هامو بگو" to DanaAction.ReminderList,
        "یادآوری های من" to DanaAction.ReminderList,
        "حالت خصوصی روشن" to DanaAction.PrivateMode(true),
        "حالت خصوصی رو فعال کن" to DanaAction.PrivateMode(true),
        "حالت خصوصی رو خاموش کن" to DanaAction.PrivateMode(false),
        "یادگیری رو خاموش کن" to DanaAction.LearningMode(false),
        "یادگیری رو روشن کن" to DanaAction.LearningMode(true),
        "مختصر جواب بده" to DanaAction.ResponseStyle("short"),
        "دوستانه جواب بده" to DanaAction.ResponseStyle("friendly"),
        "دانا خلاصه جواب بده" to DanaAction.ResponseStyle("short"),
        "من فقط دارم سلام می‌کنم" to DanaAction.Unknown("من فقط دارم سلام می‌کنم"),
        "به رضا زنگ نزن" to DanaAction.Unknown("به رضا زنگ نزن"),
        "چراغ قوه رو روشن نکن" to DanaAction.Unknown("چراغ قوه رو روشن نکن"),
        "چراغ قوه رو خاموش نکن" to DanaAction.Unknown("چراغ قوه رو خاموش نکن"),
        "صدای گوشی رو کم نکن" to DanaAction.Unknown("صدای گوشی رو کم نکن"),
        "واتساپ رو باز نکن" to DanaAction.Unknown("واتساپ رو باز نکن"),
        "به محمد پیام نفرست" to DanaAction.Unknown("به محمد پیام نفرست"),
        "دوربین رو باز نکن" to DanaAction.Unknown("دوربین رو باز نکن"),
        "فردا ساعت هشت یادم ننداز" to DanaAction.Unknown("فردا ساعت هشت یادم ننداز"),
        "به رضا زنگ بزن نه نزن" to DanaAction.Unknown("به رضا زنگ بزن نه نزن"),
        "دفترچه تلفن رو باز کن" to DanaAction.Contacts,
        "مخاطبین رو نشون بده" to DanaAction.Contacts,
        "مخاطبین" to DanaAction.Contacts,
        "دفترچه تلفن" to DanaAction.Contacts
    )
    var failed = 0
    for ((phrase, expected) in cases) {
        val actual = CommandEngine.parse(phrase)
        if (actual != expected) {
            System.err.println("FAIL: $phrase | expected=$expected actual=$actual")
            failed++
        }
    }
    check(CommandEngine.normalize("عَلِي") == "علی") { "Normalization failed" }
    println("DANA Core: ${cases.size + 1 - failed}/${cases.size + 1} cases passed")
    check(failed == 0) { "$failed cases failed" }
    val utc = TimeZone.getTimeZone("UTC")
    val base = Calendar.getInstance(utc).apply {
        set(2026, Calendar.SEPTEMBER, 26, 15, 45, 34)
        set(Calendar.MILLISECOND, 500)
    }.timeInMillis
    var timeOk = 0
    fun checkTime(ok: Boolean) { check(ok) { "Reminder time test failed" }; timeOk++ }
    checkTime(ReminderTimeResolver.inMinutes(base, 15) - base == 900_000L)
    checkTime(ReminderTimeResolver.inMinutes(base, 60) - base == 3_600_000L)
    val today = ReminderTimeResolver.atLocal(base, 0, 18, 20, utc)
    val tomorrow = ReminderTimeResolver.atLocal(base, 1, 18, 20, utc)
    checkTime(today > base)
    checkTime(tomorrow - today == 86_400_000L)
    checkTime(Calendar.getInstance(utc).apply { timeInMillis = today }.get(Calendar.MINUTE) == 20)
    checkTime(runCatching { ReminderTimeResolver.atLocal(base, 1, 25, 0, utc) }.isFailure)
    println("DANA ReminderTimeResolver: $timeOk/$timeOk cases passed")
    FeatureRegression.run()
}
