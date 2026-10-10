package com.dana.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Only user-requested reminders are persisted. No audio or conversation transcript. */
class ReminderStore(context: Context) {
    private val prefs = context.getSharedPreferences("dana_reminders", Context.MODE_PRIVATE)

    data class Reminder(val id: Int, val text: String, val dueAt: Long)

    fun add(text: String, dueAt: Long): Reminder {
        val clean = text.trim().take(160)
        require(clean.isNotEmpty() && dueAt > System.currentTimeMillis())
        val next = prefs.getInt("next_id", 0) + 1
        val item = Reminder(next, clean, dueAt)
        val entries = all().filter { it.dueAt > System.currentTimeMillis() - 86_400_000L }
        save((entries + item).takeLast(100))
        prefs.edit().putInt("next_id", next).apply()
        return item
    }

    fun all(): List<Reminder> = try {
        val arr = JSONArray(prefs.getString("entries", "[]") ?: "[]")
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optInt("id", -1)
            val text = o.optString("text", "")
            val at = o.optLong("dueAt", 0)
            if (id > 0 && text.isNotBlank() && at > 0) Reminder(id, text, at) else null
        }
    } catch (_: Exception) { emptyList() }

    fun get(id: Int): Reminder? = all().firstOrNull { it.id == id }

    fun pending(): List<Reminder> = all().filter { it.dueAt > System.currentTimeMillis() }.sortedBy { it.dueAt }

    fun remove(id: Int) = save(all().filterNot { it.id == id })

    fun clear() = prefs.edit().clear().commit()

    private fun save(reminders: List<Reminder>) {
        val arr = JSONArray()
        reminders.forEach { r ->
            arr.put(JSONObject().put("id", r.id).put("text", r.text).put("dueAt", r.dueAt))
        }
        prefs.edit().putString("entries", arr.toString()).commit()
    }
}
