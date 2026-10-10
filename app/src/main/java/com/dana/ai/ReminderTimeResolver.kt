package com.dana.ai

import java.util.Calendar
import java.util.TimeZone

/** Pure time calculations, independently testable outside Android. */
object ReminderTimeResolver {
    fun inMinutes(nowMillis: Long, minutes: Int): Long {
        require(minutes in 1..10080)
        return nowMillis + minutes * 60_000L
    }

    fun atLocal(nowMillis: Long, dayOffset: Int, hour: Int, minute: Int,
                timeZone: TimeZone = TimeZone.getDefault()): Long {
        require(dayOffset in 0..1)
        require(hour in 0..23 && minute in 0..59)
        return Calendar.getInstance(timeZone).apply {
            timeInMillis = nowMillis
            add(Calendar.DAY_OF_YEAR, dayOffset)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
