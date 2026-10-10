package com.dana.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small, bounded, opt-in-by-use local diagnostics. No raw commands, transcripts or exception messages. */
object DiagnosticEvents {
    private const val PREFS = "dana_diagnostic_events"
    private const val KEY = "events"
    private const val LIMIT = 80
    private val lock = Any()

    fun record(context: Context, event: String, detail: String = "NONE") {
        if (UserMemory(context).privateMode) return
        val safeEvent = DiagnosticEventRules.safeToken(event) ?: return
        val safeDetail = DiagnosticEventRules.safeToken(detail) ?: return
        synchronized(lock) {
            runCatching {
                val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val old = JSONArray(prefs.getString(KEY, "[]") ?: "[]")
                val next = JSONArray()
                for (i in (old.length() - LIMIT + 1).coerceAtLeast(0) until old.length()) {
                    next.put(old.getJSONObject(i))
                }
                next.put(JSONObject().put("at", System.currentTimeMillis())
                    .put("event", safeEvent).put("detail", safeDetail))
                prefs.edit().putString(KEY, next.toString()).apply()
            }
        }
    }

    fun recent(context: Context): String = synchronized(lock) {
        runCatching {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val entries = JSONArray(prefs.getString(KEY, "[]") ?: "[]")
            val formatter = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
            if (entries.length() == 0) "(رویدادی ثبت نشده)" else buildString {
                for (i in 0 until entries.length()) {
                    val e = entries.getJSONObject(i)
                    val event = e.optString("event")
                    val detail = e.optString("detail")
                    if (DiagnosticEventRules.safeToken(event) == null ||
                        DiagnosticEventRules.safeToken(detail) == null) continue
                    append(formatter.format(Date(e.optLong("at"))))
                    append(" | ").append(event).append(" | ").append(detail).append('\n')
                }
            }.trimEnd()
        }.getOrDefault("(گزارش رویدادها در دسترس نیست)")
    }

    fun clear(context: Context) = synchronized(lock) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY).apply()
    }
}
