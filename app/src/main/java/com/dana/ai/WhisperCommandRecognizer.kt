package com.dana.ai

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt

/** One bounded, on-device microphone window after the wake beep; no audio saved to disk. */
class WhisperCommandRecognizer(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val generation = AtomicInteger(0)
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var closed = false

    fun start(model: File, onStatus: (String) -> Unit, onResult: (String) -> Unit,
              onError: (String) -> Unit) {
        if (closed) { onError("Whisper recognizer is closed"); return }
        val token = generation.incrementAndGet()
        executor.execute {
            fun valid() = !closed && generation.get() == token
            fun status(s: String) { main.post { if (valid()) onStatus(s) } }
            fun error(s: String) { main.post { if (valid()) onError(s) } }
            try {
                if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                    error("مجوز میکروفون داده نشده")
                else {
                    status("در حال ضبط فرمان فارسی با Whisper (آفلاین)…")
                    val samples = recordUntilSilence(token)
                    if (valid()) {
                        if (samples == null) error("صدای واضحی دریافت نشد؛ دوباره فرمان را بگو")
                        else {
                            WhisperMetrics.capture()
                            status("در حال تبدیل گفتار به متن، کاملاً روی گوشی…")
                            val phrase = WhisperNative.transcribe(model.absolutePath, samples)
                            if (valid()) {
                                if (phrase.isBlank()) error("Whisper جمله‌ای تشخیص نداد")
                                else main.post { if (valid()) onResult(phrase) }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (valid()) error("خطای Whisper: ${e.javaClass.simpleName}")
            }
        }
    }

    /** 16 kHz mono PCM. End after ~0.9 s of silence, cap at 9 s. */
    private fun recordUntilSilence(token: Int): FloatArray? {
        // A permission check in start() does not protect this call if the user
        // revokes RECORD_AUDIO between scheduling and opening the microphone.
        // Check at the actual AudioRecord call site, too (required by Android Lint).
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("RECORD_AUDIO permission is missing or revoked")
        }
        val rate = 16_000
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT)
        check(min > 0) { "Unsupported audio format" }
        val audio = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(min, 4096))
        } catch (e: SecurityException) {
            // Android may revoke permission after the check; report a safe
            // failure to the caller instead of crashing the foreground service.
            throw SecurityException("RECORD_AUDIO permission was revoked", e)
        }
        if (audio.state != AudioRecord.STATE_INITIALIZED) {
            audio.release()
            error("Microphone not initialized")
        }
        recorder = audio
        val maxSamples = rate * 9
        val pcm = FloatArray(maxSamples)
        val block = ShortArray(1600)
        var filled = 0
        var speech = false
        var silentBlocks = 0
        var voicedBlocks = 0
        try {
            audio.startRecording()
            while (generation.get() == token && !closed && filled < maxSamples) {
                val count = audio.read(block, 0, minOf(block.size, maxSamples - filled),
                    AudioRecord.READ_BLOCKING)
                if (count <= 0) break
                var power = 0.0
                for (i in 0 until count) {
                    val v = block[i].toFloat()
                    pcm[filled + i] = v / 32768.0f
                    power += v * v
                }
                filled += count
                val rms = sqrt(power / count)
                if (rms >= 300.0) {
                    speech = true
                    voicedBlocks++
                    silentBlocks = 0
                } else if (speech) silentBlocks++
                if (speech && silentBlocks >= 9 && filled >= rate) break
            }
        } finally {
            recorder = null
            runCatching { audio.stop() }
            audio.release()
        }
        return if (generation.get() != token || !speech || voicedBlocks < 2 || filled < rate / 2)
            null else pcm.copyOf(filled)
    }

    fun cancel() {
        generation.incrementAndGet()
        recorder?.let { runCatching { it.stop() } }
    }

    fun close() { closed = true; cancel(); executor.shutdownNow() }
}
