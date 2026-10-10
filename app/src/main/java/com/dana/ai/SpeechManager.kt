package com.dana.ai

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/** صرفاً هنگام درخواست کاربر گوش می‌دهد؛ حلقه شنود دائمی ندارد. */
class SpeechManager(
    private val context: Context,
    private val onResult: (String) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onError: (String) -> Unit
) {
    private var recognizer: SpeechRecognizer? = null
    private var recognizerAllowsOnline: Boolean? = null
    private var busy = false

    /** Android's on-device service can still lack a downloaded Persian language pack. */
    fun hasOnDeviceRecognizer(): Boolean = android.os.Build.VERSION.SDK_INT >= 31 &&
        SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    /** Online-capable recognition is allowed only after explicit user consent in MainActivity. */
    fun listen(allowOnline: Boolean = false) {
        if (busy) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onError("موتور تشخیص گفتار روی این گوشی در دسترس نیست.")
            return
        }
        try {
            // Switching privacy modes must recreate the recognizer: the Android on-device
            // recognizer may lack Persian even when isOnDeviceRecognitionAvailable() is true.
            if (recognizer != null && recognizerAllowsOnline != allowOnline) close()
            if (recognizer == null) {
                recognizer = if (!allowOnline && android.os.Build.VERSION.SDK_INT >= 31 &&
                    SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                } else SpeechRecognizer.createSpeechRecognizer(context)
                recognizerAllowsOnline = allowOnline
                recognizer!!.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) = onStatus("در حال گوش دادن…")
                    override fun onBeginningOfSpeech() = onStatus("صدایت را می‌شنوم…")
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() = onStatus("در حال تشخیص گفتار…")
                    override fun onError(error: Int) {
                        busy = false
                        onError(when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                                "صدای واضحی دریافت نشد؛ دوباره تلاش کن."
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "مجوز میکروفون لازم است."
                            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                                if (allowOnline) "سرویس تشخیص گفتار گوشی زبان فارسی را پشتیبانی نمی‌کند؛ سرویس Google Speech را بررسی کن."
                                else "بسته تشخیص فارسی آفلاین روی گوشی در دسترس نیست؛ حالت خصوصی را بررسی کن."
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "میکروفون مشغول است؛ دوباره تلاش کن."
                            else -> "مشکل در تشخیص گفتار (کد $error)."
                        })
                    }
                    override fun onResults(results: Bundle?) {
                        busy = false
                        val phrase = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull { it.isNotBlank() }
                        if (phrase == null) onError("متوجه نشدم؛ دوباره بگو.") else onResult(phrase)
                    }
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "fa-IR")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                if (android.os.Build.VERSION.SDK_INT >= 23)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, !allowOnline)
            }
            busy = true
            recognizer!!.startListening(i)
        } catch (e: Exception) { busy = false; onError("تشخیص صوت شروع نشد: ${e.message ?: "خطا"}") }
    }

    fun cancel() { if (busy) recognizer?.cancel(); busy = false }
    fun close() { cancel(); recognizer?.destroy(); recognizer = null; recognizerAllowsOnline = null }
}
