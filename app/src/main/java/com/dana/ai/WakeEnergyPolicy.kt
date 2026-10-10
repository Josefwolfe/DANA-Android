package com.dana.ai

/** Pure policy: opt-in experimental listening yields to the phone's battery-saving priorities. */
object WakeEnergyPolicy {
    const val MIN_BATTERY_PERCENT = 15

    /** null means the experimental session may run; non-null explains why it must pause. */
    fun stopReason(percent: Int, charging: Boolean, batterySaver: Boolean): String? = when {
        batterySaver -> "حالت ذخیره باتری گوشی فعال است؛ بیدارباش متوقف شد."
        percent in 0 until MIN_BATTERY_PERCENT && !charging ->
            "شارژ گوشی کمتر از $MIN_BATTERY_PERCENT درصد است؛ برای حفظ باتری بیدارباش متوقف شد."
        else -> null
    }
}
