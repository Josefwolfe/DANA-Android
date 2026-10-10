package com.dana.ai

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale

/**
 * Persian TTS adapter. Vosk is speech-to-text, not a speech synthesizer.
 * We only mark speech as available when the installed Android TTS engine
 * explicitly accepts Persian or exposes a Persian voice. No English fallback.
 * Selecting an engine is an explicit local user action, not a silent install.
 */
class DanaVoice(context: Context, private val onAvailability: (Boolean) -> Unit) {
    data class VoiceChoice(val id: String, val description: String, val offline: Boolean)
    data class EngineChoice(val packageName: String, val label: String)

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val prefs = appContext.getSharedPreferences("dana_preferences", Context.MODE_PRIVATE)
    private val audio = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes).build()

    private var tts: TextToSpeech? = null
    private var generation = 0
    private var initialized = false
    private var ready = false
    private var hasFocus = false
    private var currentId: String? = null
    private var completed: (() -> Unit)? = null
    private var queued: Pair<String, (() -> Unit)?>? = null
    private var initializationStatus = "STARTING"
    private var languageStatus = "NOT_CHECKED"
    private var lastSpeakStatus = "NOT_TESTED"

    init { initialize() }

    fun isReady(): Boolean = initialized && ready

    fun choices(): List<VoiceChoice> = runCatching {
        tts?.voices.orEmpty().filter { it.locale.language.equals("fa", ignoreCase = true) }
            .sortedWith(compareBy<Voice> { it.isNetworkConnectionRequired }.thenBy { it.name })
            .map { VoiceChoice(it.name, it.name, !it.isNetworkConnectionRequired) }
    }.getOrDefault(emptyList())

    fun engines(): List<EngineChoice> = runCatching {
        tts?.engines.orEmpty().map { EngineChoice(it.name, it.label.toString()) }
    }.getOrDefault(emptyList())

    fun selectedEngine(): String = prefs.getString("tts_engine_package", null) ?: "SYSTEM_DEFAULT"

    fun chooseEngine(packageName: String): Boolean {
        if (engines().none { it.packageName == packageName }) return false
        prefs.edit().putString("tts_engine_package", packageName).remove("voice_name").apply()
        refresh()
        return true
    }

    fun useSystemEngine() {
        prefs.edit().remove("tts_engine_package").remove("voice_name").apply()
        refresh()
    }

    fun chooseVoice(id: String): Boolean {
        if (!isReady()) return false
        val candidate = runCatching {
            tts?.voices?.firstOrNull { it.name == id && it.locale.language == "fa" }
        }.getOrNull() ?: return false
        if (UserMemory(appContext).privateMode && candidate.isNetworkConnectionRequired) return false
        if (tts?.setVoice(candidate) != TextToSpeech.SUCCESS) return false
        prefs.edit().putString("voice_name", id).apply()
        return true
    }

    /** Refresh after returning from system TTS settings or installing language data. */
    fun refresh() {
        stop()
        tts?.shutdown()
        tts = null
        initialized = false
        ready = false
        initialize()
    }

    private fun initialize() {
        val myGeneration = ++generation
        initializationStatus = "INITIALIZING"
        languageStatus = "NOT_CHECKED"
        val selected = prefs.getString("tts_engine_package", null)
        try {
            val callback = TextToSpeech.OnInitListener { status ->
                main.post {
                    if (myGeneration == generation) configure(status)
                }
            }
            tts = if (selected.isNullOrBlank()) TextToSpeech(appContext, callback)
                else TextToSpeech(appContext, callback, selected)
        } catch (_: Exception) {
            initialized = true
            ready = false
            initializationStatus = "ENGINE_START_FAILED"
            onAvailability(false)
        }
    }

    private fun configure(status: Int) {
        val engine = tts
        initialized = true
        if (status != TextToSpeech.SUCCESS || engine == null) {
            ready = false
            initializationStatus = "ENGINE_INIT_FAILED"
            onAvailability(false)
            return
        }
        initializationStatus = "INITIALIZED"
        val primary = runCatching { engine.setLanguage(Locale.forLanguageTag("fa-IR")) }
            .getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
        val language = if (primary >= TextToSpeech.LANG_AVAILABLE) primary else
            runCatching { engine.setLanguage(Locale("fa")) }
                .getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
        languageStatus = when (language) {
            TextToSpeech.LANG_MISSING_DATA -> "PERSIAN_DATA_MISSING"
            TextToSpeech.LANG_NOT_SUPPORTED -> "PERSIAN_NOT_SUPPORTED"
            else -> "PERSIAN_ACCEPTED"
        }
        val persian = runCatching {
            engine.voices.orEmpty().filter { it.locale.language == "fa" }
        }.getOrDefault(emptyList())
        val saved = prefs.getString("voice_name", null)
        val privateMode = UserMemory(appContext).privateMode
        val preferred = persian.firstOrNull {
            it.name == saved && (!privateMode || !it.isNetworkConnectionRequired)
        } ?: persian.firstOrNull { !it.isNetworkConnectionRequired }
            ?: if (privateMode) null else persian.firstOrNull()
        val voiceSelected = preferred != null &&
            runCatching { engine.setVoice(preferred) == TextToSpeech.SUCCESS }.getOrDefault(false)
        // Private mode must never use a voice advertised as network-dependent.
        // If an engine does not disclose any voices, offline behavior cannot be verified.
        ready = if (privateMode) {
            voiceSelected && preferred != null && !preferred.isNetworkConnectionRequired
        } else language >= TextToSpeech.LANG_AVAILABLE || voiceSelected
        if (privateMode && !ready) languageStatus = "OFFLINE_PERSIAN_VOICE_REQUIRED"
        if (ready) {
            engine.setSpeechRate(0.94f)
            engine.setAudioAttributes(attributes)
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    if (utteranceId == currentId) lastSpeakStatus = "PLAYING"
                }
                override fun onDone(utteranceId: String?) { finish(utteranceId, "DONE") }
                @Deprecated("Android callback")
                override fun onError(utteranceId: String?) { finish(utteranceId, "SYNTHESIS_ERROR") }
                override fun onError(utteranceId: String?, errorCode: Int) {
                    finish(utteranceId, "SYNTHESIS_ERROR_$errorCode")
                }
            })
        }
        onAvailability(ready)
        queued?.let { (phrase, callback) -> queued = null; speak(phrase, callback) }
    }

    private fun finish(id: String?, status: String) {
        main.post {
            if (id == currentId) {
                lastSpeakStatus = status
                val next = completed
                completed = null
                currentId = null
                releaseFocus()
                next?.invoke()
            }
        }
    }

    private fun requestFocus() {
        hasFocus = audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }
    private fun releaseFocus() {
        if (hasFocus) audio.abandonAudioFocusRequest(focus)
        hasFocus = false
    }

    fun speak(text: String, onDone: (() -> Unit)? = null) {
        if (!initialized) { queued = text to onDone; return }
        if (!ready) {
            lastSpeakStatus = "PERSIAN_VOICE_UNAVAILABLE"
            main.post { onDone?.invoke() }
            return
        }
        val id = "dana_${System.nanoTime()}"
        currentId = id
        completed = onDone
        requestFocus()
        val result = runCatching {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        }.getOrNull()
        if (result != TextToSpeech.SUCCESS) finish(id, "SPEAK_REQUEST_FAILED")
        else {
            lastSpeakStatus = "QUEUED"
            // Some vendor engines never send onDone/onError; do not strand wake listening.
            val watchdogMs = (text.length * 180L).coerceIn(12_000L, 45_000L)
            main.postDelayed({ if (currentId == id) finish(id, "SPEECH_CALLBACK_TIMEOUT") }, watchdogMs)
        }
    }

    fun diagnostics(): String {
        val volume = runCatching { audio.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(-1)
        val maxVolume = runCatching { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(-1)
        return buildString {
            append("TTS selected engine: ").append(selectedEngine()).append('\n')
            append("TTS init status: ").append(initializationStatus).append('\n')
            append("TTS Persian language: ").append(languageStatus).append('\n')
            append("TTS last playback: ").append(lastSpeakStatus).append('\n')
            append("Media volume: ").append(volume).append('/').append(maxVolume).append('\n')
        }
    }

    fun stop() {
        completed = null
        currentId = null
        queued = null
        tts?.stop()
        releaseFocus()
    }
    fun close() {
        generation++
        stop()
        tts?.shutdown()
        tts = null
        initialized = false
        ready = false
    }
}
