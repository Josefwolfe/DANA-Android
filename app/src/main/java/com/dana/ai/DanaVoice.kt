package com.dana.ai

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.Voice
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** جنسیت صدا قابل تضمین نیست؛ فقط صدای فارسی نصب‌شده را ترجیح می‌دهد. */
class DanaVoice(context: Context, private val onAvailability: (Boolean) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("dana_preferences", Context.MODE_PRIVATE)
    data class VoiceChoice(val id: String, val description: String, val offline: Boolean)
    fun choices(): List<VoiceChoice> = try {
        tts?.voices.orEmpty().filter { it.locale.language == "fa" }
            .sortedWith(compareBy<Voice> { it.isNetworkConnectionRequired }.thenBy { it.name })
            .map { VoiceChoice(it.name, it.name, !it.isNetworkConnectionRequired) }
    } catch (_: Exception) { emptyList() }
    fun chooseVoice(id: String): Boolean {
        val candidate = try { tts?.voices?.firstOrNull { it.name == id && it.locale.language == "fa" } }
            catch (_: Exception) { null }
        if (candidate == null || tts?.setVoice(candidate) != TextToSpeech.SUCCESS) return false
        prefs.edit().putString("voice_name", id).apply()
        return true
    }

    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .build()
    private var hasFocus = false

    private fun requestFocus() {
        hasFocus = audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }
    private fun releaseFocus() {
        if (hasFocus) audio.abandonAudioFocusRequest(focus)
        hasFocus = false
    }
    private var tts: TextToSpeech? = null
    private var ready = false
    private var initialized = false
    private var currentId: String? = null
    private var queued: Pair<String, (() -> Unit)?>? = null
    private var completed: (() -> Unit)? = null

    init {
        tts = TextToSpeech(context) { status ->
            main.post {
                val engine = tts
                val result = if (status == TextToSpeech.SUCCESS && engine != null)
                    engine.setLanguage(Locale("fa", "IR")) else TextToSpeech.LANG_NOT_SUPPORTED
                ready = result != TextToSpeech.LANG_NOT_SUPPORTED && result != TextToSpeech.LANG_MISSING_DATA
                initialized = true
                if (ready) {
                    val localVoices = try {
                        engine?.voices?.filter { it.locale.language == "fa" && !it.isNetworkConnectionRequired }
                    } catch (ignored: Exception) { null }
                    val saved = prefs.getString("voice_name", null)
                    val preferred = try { engine?.voices?.firstOrNull { it.name == saved && it.locale.language == "fa" } }
                        catch (_: Exception) { null }
                        ?: localVoices?.firstOrNull {
                            it.name.contains("female", true) || it.name.contains("woman", true)
                        } ?: localVoices?.firstOrNull()
                    if (preferred != null) engine?.voice = preferred
                    engine?.setSpeechRate(0.94f)
                    engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}
                        override fun onDone(utteranceId: String?) { main.post {
                            if (utteranceId == currentId) {
                                val next = completed; completed = null; currentId = null
                                releaseFocus()
                                next?.invoke()
                            }
                        } }
                        @Deprecated("Android callback")
                        override fun onError(utteranceId: String?) { onDone(utteranceId) }
                    })
                }
                onAvailability(ready)
                queued?.let { (phrase, callback) -> queued = null; speak(phrase, callback) }
            }
        }
    }

    fun speak(text: String, onDone: (() -> Unit)? = null) {
        if (!initialized) { queued = text to onDone; return }
        if (!ready) { main.post { onDone?.invoke() }; return }
        val id = "dana_${System.nanoTime()}"
        currentId = id
        completed = onDone
        requestFocus()
        val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        if (result == TextToSpeech.ERROR) {
            val next = completed; completed = null; currentId = null
            releaseFocus()
            main.post { next?.invoke() }
        }
    }

    fun stop() { completed = null; currentId = null; tts?.stop(); releaseFocus() }
    fun close() { stop(); tts?.shutdown(); tts = null }
}
