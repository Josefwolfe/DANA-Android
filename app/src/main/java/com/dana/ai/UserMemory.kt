package com.dana.ai

import android.content.Context
import org.json.JSONObject

class UserMemory(context: Context) {
    private val prefs = context.getSharedPreferences("dana_preferences", Context.MODE_PRIVATE)
    var name: String?
        get() = prefs.getString("user_name", null)?.takeIf { it.isNotBlank() }
        set(value) { prefs.edit().putString("user_name", value?.trim()).apply() }

    var drivingMode: Boolean
        get() = prefs.getBoolean("driving_mode", false)
        set(value) { prefs.edit().putBoolean("driving_mode", value).apply() }

    var restMode: Boolean
        get() = prefs.getBoolean("rest_mode", false)
        set(value) { prefs.edit().putBoolean("rest_mode", value).apply() }

    var privateMode: Boolean
        get() = prefs.getBoolean("private_mode", false)
        set(value) { prefs.edit().putBoolean("private_mode", value).apply() }

    var learningEnabled: Boolean
        get() = prefs.getBoolean("learning_enabled", true)
        set(value) { prefs.edit().putBoolean("learning_enabled", value).apply() }

    var microphoneDisclosureAccepted: Boolean
        get() = prefs.getBoolean("mic_network_disclosure_accepted", false)
        set(value) { prefs.edit().putBoolean("mic_network_disclosure_accepted", value).apply() }

    var quickAccessEnabled: Boolean
        get() = prefs.getBoolean("quick_access_enabled", false)
        set(value) { prefs.edit().putBoolean("quick_access_enabled", value).apply() }

    /** User's explicit consent to resume the wake service when DANA is opened visibly. */
    var wakeAutoResume: Boolean
        get() = prefs.getBoolean("wake_auto_resume", false)
        set(value) { prefs.edit().putBoolean("wake_auto_resume", value).apply() }

    /** Opt-in, foreground-only turn-taking after each spoken answer. */
    var conversationMode: Boolean
        get() = prefs.getBoolean("conversation_mode", false)
        set(value) { prefs.edit().putBoolean("conversation_mode", value).apply() }

    var responseStyle: String
        get() = prefs.getString("response_style", "friendly") ?: "friendly"
        set(value) { prefs.edit().putString("response_style", if (value == "short") "short" else "friendly").apply() }

    var voiceName: String?
        get() = prefs.getString("voice_name", null)
        set(value) { prefs.edit().putString("voice_name", value).apply() }

    /** Advanced inference is opt-in. The endpoint is a server-side proxy, never an embedded LLM key. */
    var cloudAiEnabled: Boolean
        get() = prefs.getBoolean("cloud_ai_enabled", false)
        set(value) { prefs.edit().putBoolean("cloud_ai_enabled", value).apply() }

    var cloudEndpoint: String
        get() = prefs.getString("cloud_endpoint", "") ?: ""
        set(value) { prefs.edit().putString("cloud_endpoint", value.trim()).apply() }

    fun clearAll() { prefs.edit().clear().commit() }

    fun aliasCount(): Int = try {
        JSONObject(prefs.getString("aliases", "{}") ?: "{}").length()
    } catch (_: Exception) { 0 }

    fun saveAlias(alias: String, contactId: Long, displayName: String) {
        if (privateMode || !learningEnabled) return
        val all = JSONObject(prefs.getString("aliases", "{}") ?: "{}")
        all.put(CommandEngine.normalize(alias), JSONObject().put("id", contactId).put("name", displayName))
        prefs.edit().putString("aliases", all.toString()).apply()
    }

    fun resolveAlias(alias: String): Long? {
        val all = JSONObject(prefs.getString("aliases", "{}") ?: "{}")
        return all.optJSONObject(CommandEngine.normalize(alias))?.optLong("id")?.takeIf { it > 0L }
    }
}
