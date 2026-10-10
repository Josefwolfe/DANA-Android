package com.dana.ai

/** Pure decision rules; never treat an explicitly negated instruction as permission to act. */
object SafetyPolicy {
    private val prohibitions = Regex(
        "(?:^|\\s)(?:نکن|نکنید|نزن|نزنید|نگیر|نگیرید|نفرست|نفرستید|نذار|نگذار|نگذارید|نبند|اجرا نکن|باز نکن|انجام نده|سرچ نکن|جستجو نکن|پیدا نکن|نرو)(?:$|\\s)"
    )
    private val optOut = Regex("(?:^|\\s)(?:نمی\\s*خوام|نمی\\s*خواهم|منصرف شدم)(?:$|\\s)")

    fun explicitlyNegated(text: String): Boolean {
        val s = CommandEngine.normalize(text)
        // In «به رضا بگو چراغ رو روشن نکن», the negation belongs to the MESSAGE,
        // not to the user's request to open an SMS draft. Preserve quoted message content.
        val positiveSms = Regex(
            "^(?:(?:سلام دانا|هی دانا|دانا) )?(?:به|برای) .{1,80}? " +
                "(?:بگو|بنویس|خبر بده|پیام بده|پیام بفرست|اس ام اس بفرست)(?: که)? (.+)$"
        ).find(s)
        if (positiveSms != null && !optOut.containsMatchIn(s.substringBefore(positiveSms.groupValues[1]))) {
            return false
        }
        return prohibitions.containsMatchIn(s) || optOut.containsMatchIn(s)
    }

    /** Wake service never performs operations that reveal private data or contact another person. */
    fun permittedWithLockedScreen(action: DanaAction): Boolean =
        action is DanaAction.Time || action is DanaAction.Battery ||
            action is DanaAction.TodayDate || action is DanaAction.Help ||
            action is DanaAction.Greeting || action is DanaAction.Flashlight ||
            action is DanaAction.Volume || action is DanaAction.MediaMute ||
            action is DanaAction.Media || action is DanaAction.Music ||
            action is DanaAction.Alarm || action is DanaAction.DatedAlarm ||
            action is DanaAction.PersianDateAlarm

    fun alwaysAskBeforeExecution(action: DanaAction): Boolean =
        action is DanaAction.Call || action is DanaAction.Sms
}
