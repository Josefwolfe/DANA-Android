package com.dana.ai

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.icu.util.ULocale
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/** Alarm dates use the device's local timezone. Unlike reminders, these are alarm-clock events. */
object DatedAlarmTime {
    fun nextDay(now: Long, offset: Int, hour: Int, minute: Int): Long? {
        require(offset in 0..2 && hour in 0..23 && minute in 0..59)
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            add(Calendar.DAY_OF_YEAR, offset)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis.takeIf { it > now }
    }

    fun nextWeekday(now: Long, weekday: Int, hour: Int, minute: Int): Long {
        require(weekday in Calendar.SUNDAY..Calendar.SATURDAY)
        require(hour in 0..23 && minute in 0..59)
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val offset = (weekday - cal.get(Calendar.DAY_OF_WEEK) + 7) % 7
        cal.add(Calendar.DAY_OF_YEAR, offset)
        cal.set(Calendar.HOUR_OF_DAY, hour)
        cal.set(Calendar.MINUTE, minute)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_YEAR, 7)
        return cal.timeInMillis
    }

    /** Persian calendar date, next occurrence. Android ICU is available since API 24. */
    fun nextPersianDate(now: Long, month: Int, day: Int, hour: Int, minute: Int): Long? {
        if (month !in 1..12 || day !in 1..31 || hour !in 0..23 || minute !in 0..59) return null
        val base = android.icu.util.Calendar.getInstance(ULocale("fa_IR@calendar=persian"))
        base.timeInMillis = now
        val currentYear = base.get(android.icu.util.Calendar.YEAR)
        for (year in currentYear..currentYear + 8) {
            val cal = android.icu.util.Calendar.getInstance(ULocale("fa_IR@calendar=persian"))
            cal.isLenient = false
            try {
                cal.clear()
                cal.set(android.icu.util.Calendar.YEAR, year)
                cal.set(android.icu.util.Calendar.MONTH, month - 1)
                cal.set(android.icu.util.Calendar.DAY_OF_MONTH, day)
                cal.set(android.icu.util.Calendar.HOUR_OF_DAY, hour)
                cal.set(android.icu.util.Calendar.MINUTE, minute)
                val millis = cal.timeInMillis
                if (millis > now) return millis
            } catch (_: IllegalArgumentException) { /* Invalid Esfand 30 in a non-leap year. */ }
        }
        return null
    }
}

class DatedAlarmScheduler(private val context: Context) {
    data class Alarm(val id: Int, val at: Long, val label: String, val exact: Boolean)
    private val prefs = context.getSharedPreferences("dana_dated_alarms", Context.MODE_PRIVATE)
    private val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun read(): MutableList<Alarm> = runCatching {
        val arr = JSONArray(prefs.getString("items", "[]"))
        MutableList(arr.length()) { index ->
            val obj = arr.getJSONObject(index)
            Alarm(obj.getInt("id"), obj.getLong("at"), obj.getString("label"),
                obj.optBoolean("exact", false))
        }
    }.getOrDefault(mutableListOf())

    private fun write(items: List<Alarm>) {
        val arr = JSONArray()
        items.forEach { item -> arr.put(JSONObject()
            .put("id", item.id).put("at", item.at).put("label", item.label)
            .put("exact", item.exact)) }
        prefs.edit().putString("items", arr.toString()).apply()
    }

    private fun trigger(id: Int): PendingIntent = PendingIntent.getBroadcast(context, id,
        Intent(context, DatedAlarmReceiver::class.java).setAction(DatedAlarmReceiver.ACTION_RING)
            .putExtra(DatedAlarmReceiver.EXTRA_ID, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun canScheduleExact(): Boolean = android.os.Build.VERSION.SDK_INT < 31 ||
        manager.canScheduleExactAlarms()

    private fun register(item: Alarm) {
        val open = PendingIntent.getActivity(context, item.id,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        if (canScheduleExact()) {
            manager.setAlarmClock(AlarmManager.AlarmClockInfo(item.at, open), trigger(item.id))
        } else {
            // No privileged access: the event still exists but Android may delay it.
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, item.at, trigger(item.id))
        }
    }

    fun schedule(at: Long, label: String?): Alarm? {
        if (at <= System.currentTimeMillis()) return null
        val id = prefs.getInt("next_id", 40000).coerceAtLeast(40000)
        val alarm = Alarm(id, at, label?.take(100)?.ifBlank { "آلارم دانا" } ?: "آلارم دانا",
            canScheduleExact())
        // Don't store an alarm unless Android accepted its scheduling.
        register(alarm)
        val items = read()
        items.add(alarm)
        write(items)
        prefs.edit().putInt("next_id", id + 1).apply()
        return alarm
    }

    fun remove(id: Int): Alarm? {
        val items = read()
        val item = items.firstOrNull { it.id == id } ?: return null
        items.removeAll { it.id == id }
        write(items)
        return item
    }

    fun restoreAfterBoot() {
        val now = System.currentTimeMillis()
        val pending = read().filter { it.at > now }
        write(pending)
        pending.forEach { runCatching { register(it) } }
    }
}
