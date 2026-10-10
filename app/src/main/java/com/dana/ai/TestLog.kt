package com.dana.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** متن فرمان، شماره تلفن و محتوای پیام را ذخیره نمی‌کند. */
class TestLog(context: Context) {
    private val prefs = context.getSharedPreferences("dana_test_log", Context.MODE_PRIVATE)
    fun add(type: String, result: String, durationMs: Long) {
        val list = JSONArray(prefs.getString("records", "[]"))
        val next = JSONArray()
        val keepFrom = (list.length() - 199).coerceAtLeast(0)
        for (i in keepFrom until list.length()) next.put(list.getJSONObject(i))
        next.put(JSONObject().put("type", type).put("result", result)
            .put("duration_ms", durationMs).put("at", System.currentTimeMillis()))
        prefs.edit().putString("records", next.toString()).apply()
    }
    fun report(): String {
        val list = JSONArray(prefs.getString("records", "[]"))
        var ok = 0; var errors = 0; var totalTime = 0L
        val byType = linkedMapOf<String, Int>()
        for (i in 0 until list.length()) {
            val item = list.getJSONObject(i)
            if (item.getString("result") in setOf("recognized", "dispatched")) ok++ else errors++
            totalTime += item.getLong("duration_ms")
            val type = item.getString("type")
            byType[type] = (byType[type] ?: 0) + 1
        }
        return "تعداد فرمان‌ها: ${list.length()}\nفرمان‌های شناخته‌شده/تحویل‌شده: $ok\nخطا/نامشخص: $errors\n" +
            "میانگین پردازش: ${if (list.length() == 0) 0 else totalTime / list.length()} میلی‌ثانیه\n\n" +
            byType.entries.joinToString("\n") { "${it.key}: ${it.value}" } +
            "\n\nشناخته شدن فرمان به معنی انجام قطعی آن توسط گوشی یا برنامه دیگر نیست."
    }
    fun clear() { prefs.edit().remove("records").apply() }
}
