package com.dana.ai

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.*
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

/**
 * EXPERIMENTAL opt-in offline foreground microphone service.
 * Must be started from a VISIBLE Activity. Not auto-started by BOOT_COMPLETED.
 * During a call, microphone use is suspended. Never launches an activity over the lock screen.
 */
class DanaWakeService : Service(), RecognitionListener {
    companion object {
        const val ACTION_START = "com.dana.ai.START_WAKE"
        const val ACTION_STOP = "com.dana.ai.STOP_WAKE"
        const val ACTION_OPEN_COMMAND = "com.dana.ai.OPEN_WAKE_COMMAND"
        const val EXTRA_COMMAND = "wake_command"
        const val CHANNEL = "dana_wake_experimental"
        const val NOTIFICATION_ID = 7120
        const val MODEL_DIR = "vosk_fa_small"
        @Volatile private var aliveInProcess = false
        fun running(context: Context) = aliveInProcess
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var model: Model? = null
    private var recognition: SpeechService? = null
    private var recognizer: Recognizer? = null
    private var voice: DanaVoice? = null
    private var stage = "init" // init, wake, reply, command, paused, closed
    private var errors = 0
    private var stopped = false
    private val audio by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }
    private val power by lazy { getSystemService(POWER_SERVICE) as PowerManager }
    private var powerReceiverRegistered = false
    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (stopped) return
            val reason = powerStopReason(intent)
            if (reason != null) {
                getSharedPreferences("wake_runtime", MODE_PRIVATE).edit()
                    .putString("last_stop_reason", reason).apply()
                updateNotification(reason)
                stopSelf()
            }
        }
    }

    private fun powerStopReason(batteryUpdate: Intent? = null): String? {
        val battery = if (batteryUpdate?.action == Intent.ACTION_BATTERY_CHANGED) batteryUpdate else
            registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val percent = if (scale > 0 && level >= 0) (level * 100 / scale) else -1
        return WakeEnergyPolicy.stopReason(percent, charging, power.isPowerSaveMode)
    }

    private val callCheck = object : Runnable {
        override fun run() {
            if (stopped) return
            if (audio.mode == AudioManager.MODE_IN_CALL || audio.mode == AudioManager.MODE_IN_COMMUNICATION) {
                if (stage == "wake" || stage == "command") {
                    closeRecognizer()
                    stage = "paused"
                    updateNotification("در تماس؛ میکروفون دانا آزاد شد")
                }
            } else if (stage == "paused") {
                errors = 0
                startWakeRecognition()
            }
            main.postDelayed(this, 6000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(CHANNEL, "بیدارباش آزمایشی دانا", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        // Called before expensive model initialization; Android requires prompt startForeground.
        val initial = notification("در حال آماده‌سازی بیدارباش آفلاین…")
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, initial,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(NOTIFICATION_ID, initial)
        val reason = powerStopReason()
        if (reason != null) {
            getSharedPreferences("wake_runtime", MODE_PRIVATE).edit()
                .putString("last_stop_reason", reason).apply()
            updateNotification(reason)
            stopSelf()
            return
        }
        val powerFilter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(powerReceiver, powerFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(powerReceiver, powerFilter)
        }
        powerReceiverRegistered = true
        getSharedPreferences("wake_runtime", MODE_PRIVATE).edit()
            .putBoolean("running", true).remove("last_stop_reason").apply()
        aliveInProcess = true
        voice = DanaVoice(this) { /* If TTS unavailable, status notification still works. */ }
        main.post(callCheck)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (stopped || !aliveInProcess) return START_NOT_STICKY
        if (intent?.action == ACTION_STOP) {
            stopSelf(); return START_NOT_STICKY
        }
        if (UserMemory(this).privateMode) {
            updateNotification("حالت خصوصی فعال است؛ شنود متوقف شد")
            stopSelf(); return START_NOT_STICKY
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            updateNotification("اجازه میکروفون داده نشده؛ بیدارباش متوقف شد")
            stopSelf(); return START_NOT_STICKY
        }
        if (stage == "init") {
            val directory = getDir(MODEL_DIR, MODE_PRIVATE)
            if (!WakeModelInstaller.installed(directory)) {
                updateNotification("ابتدا مدل کوچک فارسی را در تنظیمات دانا نصب کن")
                stopSelf(); return START_NOT_STICKY
            }
            stage = "loading"
            worker.execute {
                val loaded = runCatching { Model(directory.absolutePath) }
                main.post {
                    if (stopped) { loaded.getOrNull()?.close(); return@post }
                    if (loaded.isFailure) {
                        updateNotification("مدل صوتی بارگذاری نشد؛ آن را دوباره نصب کن")
                        stopSelf()
                    } else {
                        model = loaded.getOrNull()
                        startWakeRecognition()
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startWakeRecognition() {
        if (stopped || stage == "closed") return
        if (audio.mode == AudioManager.MODE_IN_CALL || audio.mode == AudioManager.MODE_IN_COMMUNICATION) {
            stage = "paused"; return
        }
        closeRecognizer()
        try {
            val recognizer = Recognizer(requireNotNull(model), 16000.0f,
                "[\"سلام دانا\", \"هی دانا\", \"[unk]\"]")
            recognition = SpeechService(recognizer, 16000.0f).also { it.startListening(this) }
            this.recognizer = recognizer
            stage = "wake"
            updateNotification("فعال: منتظر «سلام دانا» • لمس برای باز کردن")
        } catch (_: Exception) { recoverOrStop() }
    }

    private fun startCommandRecognition() {
        if (stopped) return
        closeRecognizer()
        try {
            val recognizer = Recognizer(requireNotNull(model), 16000.0f)
            recognition = SpeechService(recognizer, 16000.0f).also { it.startListening(this, 8500) }
            this.recognizer = recognizer
            stage = "command"
            updateNotification("دانا در حال دریافت دستور است…")
        } catch (_: Exception) { recoverOrStop() }
    }

    override fun onResult(hypothesis: String?) {
        // Vosk invokes listeners from its recognizer thread; all state and Android UI-affecting
        // calls are serialized on the main looper to avoid shutdown/join races.
        main.post { processHypothesis(hypothesis) }
    }

    private fun processHypothesis(hypothesis: String?) {
        if (stopped || UserMemory(this).privateMode) return
        val text = runCatching { JSONObject(hypothesis ?: "{}").optString("text").trim() }.getOrDefault("")
        if (text.isBlank()) return
        when (stage) {
            "wake" -> {
                val remaining = WakePhrase.extractAfterWake(text) ?: return
                closeRecognizer()
                stage = "reply"
                if (remaining.isNotBlank()) handleCommand(remaining)
                else {
                    voice?.speak("بله، گوش می‌دم.") { main.postDelayed({ startCommandRecognition() }, 250) }
                        ?: startCommandRecognition()
                }
            }
            "command" -> {
                closeRecognizer()
                stage = "reply"
                handleCommand(text)
            }
        }
    }
    override fun onPartialResult(hypothesis: String?) {} // Never act on incomplete phrases.
    override fun onFinalResult(hypothesis: String?) { onResult(hypothesis) }
    override fun onTimeout() { main.post { if (!stopped && stage == "command") startWakeRecognition() } }
    override fun onError(e: Exception?) { main.post { recoverOrStop() } }

    private fun handleCommand(text: String) {
        if (stopped) return
        // Lock-screen-safe actions only; anything revealing data or starting a call requires a tap/unlock.
        val action = CommandEngine.parse(text)
        if (!SafetyPolicy.permittedWithLockedScreen(action)) {
            notifyForUser(text, "برای ادامه دستور، قفل گوشی را باز کن و اعلان را لمس کن")
            return
        }
        when (action) {
            DanaAction.Time -> reply("الان ساعت ${SimpleDateFormat("HH:mm", Locale("fa", "IR")).format(Date())} است.")
            DanaAction.Battery -> {
                val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
                val percent = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                reply(if (percent in 0..100) "باتری $percent درصد است." else "درصد باتری در دسترس نیست.")
            }
            is DanaAction.Flashlight -> {
                if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    notifyForUser(text, "برای چراغ قوه ابتدا اجازه دوربین بده")
                } else {
                    val ok = runCatching {
                        val cameras = getSystemService(CAMERA_SERVICE) as CameraManager
                        val id = cameras.cameraIdList.firstOrNull {
                            cameras.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                        } ?: error("flash unavailable")
                        cameras.setTorchMode(id, action.enabled)
                    }.isSuccess
                    reply(if (ok) "چراغ قوه ${if (action.enabled) "روشن" else "خاموش"} شد." else
                        "کنترل چراغ ممکن نبود؛ گوشی را باز کن.")
                }
            }
            else -> notifyForUser(text, "برای ادامه دستور، قفل گوشی را باز کن و اعلان را لمس کن")
        }
    }

    private fun reply(message: String) {
        if (stopped) return
        stage = "reply"
        voice?.speak(message) { main.postDelayed({ startWakeRecognition() }, 300) }
            ?: main.postDelayed({ startWakeRecognition() }, 300)
    }

    private fun notifyForUser(text: String, message: String) {
        stage = "reply"
        val pending = PendingIntent.getActivity(this, 37,
            Intent(this, MainActivity::class.java).apply {
                action = ACTION_OPEN_COMMAND
                putExtra(EXTRA_COMMAND, text)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val note = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_dana).setContentTitle("دانا: فرمان دریافت شد")
            .setContentText(message).setContentIntent(pending).setAutoCancel(true).build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(7121, note)
        reply("دستورت رو شنیدم. برای ادامه اعلان دانا رو لمس کن.")
    }

    private fun notification(message: String): Notification {
        val open = PendingIntent.getActivity(this, 11, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 12, Intent(this, javaClass).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_dana)
            .setContentTitle("دانا • بیدارباش آزمایشی (میکروفون فعال)")
            .setContentText(message).setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "توقف شنود", stop).build()).build()
    }

    private fun updateNotification(message: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, notification(message))
    }
    private fun closeRecognizer() {
        recognition?.let { runCatching { it.cancel() }; runCatching { it.shutdown() } }
        recognition = null
        recognizer?.close(); recognizer = null
    }
    private fun recoverOrStop() {
        if (stopped) return
        closeRecognizer()
        errors++
        if (errors > 3) {
            updateNotification("شنود به دلیل خطا متوقف شد؛ برای بررسی دانا را باز کن")
            stopSelf()
        } else main.postDelayed({ startWakeRecognition() }, (1000L * errors))
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        stopped = true; stage = "closed"; aliveInProcess = false
        if (powerReceiverRegistered) {
            unregisterReceiver(powerReceiver)
            powerReceiverRegistered = false
        }
        main.removeCallbacksAndMessages(null)
        closeRecognizer(); model?.close(); model = null
        voice?.close(); voice = null
        worker.shutdownNow()
        getSharedPreferences("wake_runtime", MODE_PRIVATE).edit().putBoolean("running", false).apply()
        super.onDestroy()
    }
}
