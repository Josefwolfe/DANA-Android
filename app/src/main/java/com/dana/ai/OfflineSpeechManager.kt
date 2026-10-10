package com.dana.ai

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.util.concurrent.Executors

/**
 * One-shot, local Persian speech recognition for the visible Activity.
 * Unlike Android SpeechRecognizer, Vosk does not require a Google Persian language pack
 * or send recorded speech to a speech provider. The user must import a Vosk model first.
 */
class OfflineSpeechManager(
    private val onResult: (String) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onError: (String) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var model: Model? = null
    private var modelPath: String? = null
    private var recognizer: Recognizer? = null
    private var speechService: SpeechService? = null
    private var generation = 0
    private var active = false
    private var closed = false
    private val timeout = Runnable {
        if (active) finishError("بارگذاری یا تشخیص آفلاین طولانی شد. مدل را بررسی کن و دوباره تلاش کن.")
    }

    fun isActive() = active

    fun listen(root: File) {
        if (closed || active) return
        if (!WakeModelInstaller.installed(root)) {
            onError("مدل Vosk فارسی نصب نیست. از دکمه نصب مدل، فایل ZIP فارسی را انتخاب کن.")
            return
        }
        active = true
        val request = ++generation
        main.postDelayed(timeout, 90_000L)
        onStatus("در حال آماده‌سازی مدل فارسی آفلاین…")
        if (model != null && modelPath == root.absolutePath) {
            beginListening(request)
            return
        }
        // Model() performs expensive native disk I/O and MUST NOT run on the UI thread.
        worker.execute {
            val loaded = runCatching { Model(root.absolutePath) }
            main.post {
                if (closed || !active || request != generation) {
                    loaded.getOrNull()?.close()
                    return@post
                }
                if (loaded.isFailure) {
                    finishError("مدل فارسی Vosk باز نشد: ${loaded.exceptionOrNull()?.localizedMessage ?: "فایل مدل ناقص است"}")
                } else {
                    model?.close()
                    model = loaded.getOrNull()
                    modelPath = root.absolutePath
                    beginListening(request)
                }
            }
        }
    }

    private fun beginListening(request: Int) {
        if (closed || !active || request != generation) return
        try {
            onStatus("مدل آماده است؛ فرمان فارسی را بگو…")
            val recognizer = Recognizer(requireNotNull(model), 16000.0f)
            this.recognizer = recognizer
            speechService = SpeechService(recognizer, 16000.0f).also { service ->
                service.startListening(object : RecognitionListener {
                    override fun onPartialResult(hypothesis: String?) { /* never execute partial speech */ }
                    override fun onResult(hypothesis: String?) = receive(hypothesis)
                    override fun onFinalResult(hypothesis: String?) = receive(hypothesis)
                    override fun onTimeout() { main.post {
                        if (active && request == generation) finishError("فرمان واضحی دریافت نشد؛ دوباره تلاش کن.")
                    } }
                    override fun onError(error: Exception?) { main.post {
                        if (active && request == generation)
                            finishError("خطای میکروفون یا Vosk: ${error?.localizedMessage ?: "نامشخص"}")
                    } }
                }, 11_000)
            }
        } catch (e: Exception) {
            finishError("تشخیص آفلاین شروع نشد: ${e.localizedMessage ?: "خطا"}")
        }
    }

    private fun receive(hypothesis: String?) {
        val phrase = runCatching { JSONObject(hypothesis ?: "{}").optString("text").trim() }.getOrDefault("")
        if (phrase.isBlank()) return
        main.post {
            if (!active || closed) return@post
            cancel()
            onResult(phrase)
        }
    }

    private fun finishError(message: String) {
        cancel()
        onError(message)
    }

    private fun stopCapture() {
        val service = speechService
        speechService = null
        if (service != null) {
            runCatching { service.cancel() }
            runCatching { service.shutdown() }
        }
        recognizer?.let { runCatching { it.close() } }
        recognizer = null
    }

    fun cancel() {
        generation++
        active = false
        main.removeCallbacks(timeout)
        stopCapture()
    }

    /** Call before replacing the installed ZIP model to avoid using a stale native model. */
    fun releaseModel() {
        cancel()
        model?.let { runCatching { it.close() } }
        model = null
        modelPath = null
    }

    fun close() {
        closed = true
        releaseModel()
        worker.shutdown()
    }
}
