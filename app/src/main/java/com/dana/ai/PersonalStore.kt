package com.dana.ai

import android.content.Context
import org.json.JSONArray

/** ذخیره سبک یادداشت‌ها و کارهای شخصی روی خود گوشی. */
class PersonalStore(context: Context) {
    private val prefs = context.getSharedPreferences("dana_personal_store", Context.MODE_PRIVATE)

    data class Task(val text: String, val done: Boolean = false)

    fun addNote(text: String) {
        val items = loadStrings("notes").toMutableList()
        items.add(0, text.trim())
        saveStrings("notes", items.take(100))
    }

    fun notes(): List<String> = loadStrings("notes")

    fun clearNotes() = prefs.edit().remove("notes").apply()

    fun addTask(text: String) {
        val items = tasks().toMutableList()
        items.add(0, Task(text.trim(), false))
        saveTasks(items.take(100))
    }

    fun tasks(): List<Task> {
        val raw = prefs.getString("tasks", "[]") ?: "[]"
        val arr = JSONArray(raw)
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val text = o.optString("text").trim()
                if (text.isNotEmpty()) add(Task(text, o.optBoolean("done", false)))
            }
        }
    }

    fun markFirstMatchingDone(query: String): Boolean {
        val q = CommandEngine.normalize(query)
        val items = tasks().toMutableList()
        val index = items.indexOfFirst { CommandEngine.normalize(it.text).contains(q) }
        if (index < 0) return false
        items[index] = items[index].copy(done = true)
        saveTasks(items)
        return true
    }

    fun clearTasks() = prefs.edit().remove("tasks").apply()

    fun clearAll() = prefs.edit().clear().commit()

    private fun loadStrings(key: String): List<String> {
        val arr = JSONArray(prefs.getString(key, "[]") ?: "[]")
        return buildList {
            for (i in 0 until arr.length()) arr.optString(i).trim().takeIf { it.isNotEmpty() }?.let(::add)
        }
    }

    private fun saveStrings(key: String, values: List<String>) {
        prefs.edit().putString(key, JSONArray(values).toString()).apply()
    }

    private fun saveTasks(values: List<Task>) {
        val arr = JSONArray()
        values.forEach { task ->
            arr.put(org.json.JSONObject().put("text", task.text).put("done", task.done))
        }
        prefs.edit().putString("tasks", arr.toString()).apply()
    }
}
