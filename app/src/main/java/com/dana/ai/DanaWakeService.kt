package com.dana.ai

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.view.KeyEvent
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
    private var commandSpeech: SpeechManager? = null
    private var stage = "init" // init, wake, reply, command_online, command_offline, paused, closed
    private val onlineTimeout = Runnable {
        if (!stopped && stage == "command_online") startCommandRecognitionOffline()
    }
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
                if (stage == "wake" || stage == "command_offline" || stage == "command_online") {
                    commandSpeech?.cancel()
                    main.removeCallbacks(onlineTimeout)
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
        voice = DanaVoice(this) { /* If TTS unavailable, notification remains readable. */ }
        commandSpeech = SpeechManager(this,
            onResult = { phrase -> main.post {
                if (!stopped && stage == "command_online") {
                    main.removeCallbacks(onlineTimeout)
                    stage = "reply"
                    handleCommand(phrase)
                }
            } },
            onStatus = { message -> if (stage == "command_online") updateNotification(message) },
            onError = { _ -> main.post {
                if (!stopped && stage == "command_online") startCommandRecognitionOffline()
            } })
        main.post(callCheck)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (stopped || !aliveInProcess) return START_NOT_STICKY
        if (intent?.action == ACTION_STOP) {
            UserMemory(this).wakeAutoResume = false
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
                """["دانا", "سلام دانا", "هی دانا", "دانا چراغ قوه رو روشن کن", "دانا چراغ قوه رو خاموش کن", "دانا ساعت چنده", "دانا باتری چند درصده", "دانا تلگرام رو باز کن", "دانا واتساپ رو باز کن", "دانا اینستاگرام رو باز کن", "دانا یوتیوب رو باز کن", "[unk]"]""")
            recognition = SpeechService(recognizer, 16000.0f).also { it.startListening(this) }
            this.recognizer = recognizer
            stage = "wake"
            updateNotification("فعال: منتظر «دانا» • برای توقف، اعلان را باز کن")
        } catch (_: Exception) { recoverOrStop() }
    }

    /** Use the default Persian recognizer only after separate consent; otherwise Vosk. */
    private fun startCommandRecognition() {
        if (stopped) return
        if (!UserMemory(this).privateMode && UserMemory(this).microphoneDisclosureAccepted) {
            closeRecognizer()
            stage = "command_online"
            updateNotification("دانا در حال شنیدن فرمان فارسی است…")
            main.postDelayed(onlineTimeout, 12_000L)
            commandSpeech?.listen(allowOnline = true) ?: startCommandRecognitionOffline()
        } else startCommandRecognitionOffline()
    }

    private fun startCommandRecognitionOffline() {
        if (stopped) return
        main.removeCallbacks(onlineTimeout)
        stage = "command_offline" // Set before cancelling online recognition callbacks.
        commandSpeech?.cancel()
        closeRecognizer()
        try {
            val next = Recognizer(requireNotNull(model), 16000.0f)
            recognition = SpeechService(next, 16000.0f).also { it.startListening(this, 8500) }
            recognizer = next
            updateNotification("فرمان فارسی آفلاین را بگو…")
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
            "command_offline" -> {
                closeRecognizer()
                stage = "reply"
                handleCommand(text)
            }
        }
    }
    override fun onPartialResult(hypothesis: String?) {} // Never act on incomplete phrases.
    override fun onFinalResult(hypothesis: String?) { onResult(hypothesis) }
    override fun onTimeout() { main.post {
        if (!stopped && stage == "command_offline") startWakeRecognition()
    } }
    override fun onError(e: Exception?) { main.post {
        if (!stopped && (stage == "wake" || stage == "command_offline")) recoverOrStop()
    } }

    private fun handleCommand(text: String) {
        if (stopped) return
        val action = CommandEngine.parse(text)
        val keyguard = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        val locked = keyguard.isDeviceLocked || keyguard.isKeyguardLocked
        // Lock-screen actions that expose information or launch other apps need unlocking.
        if (locked && !SafetyPolicy.permittedWithLockedScreen(action)) {
            notifyForUser(text, "برای این فرمان ابتدا قفل گوشی را باز کن")
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
            is DanaAction.Volume -> {
                val ok = runCatching {
                    audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                        if (action.direction < 0) AudioManager.ADJUST_LOWER else AudioManager.ADJUST_RAISE, 0)
                }.isSuccess
                reply(if (ok) "صدای گوشی تنظیم شد." else "تغییر صدا ممکن نبود.")
            }
            is DanaAction.Music -> {
                if (!DanaMusicService.hasMusicPermission(this)) {
                    notifyForUser(text, "برای پخش موسیقی ابتدا دسترسی فایل‌های صوتی را در دانا فعال کن")
                } else {
                    val command = if (action.query.isNullOrBlank()) DanaMusicService.PLAY_RANDOM
                        else DanaMusicService.PLAY_NAMED
                    val accepted = DanaMusicService.command(this, command, action.query)
                    reply(if (accepted) "دارم موسیقی رو در حافظه جست‌وجو می‌کنم."
                        else "اجرای پخش موسیقی از پس‌زمینه ممکن نشد؛ دانا رو باز کن.")
                }
            }
            is DanaAction.Media -> {
                val local = when (action.command) {
                    "play", "resume" -> DanaMusicService.RESUME
                    "pause" -> DanaMusicService.PAUSE
                    "next" -> DanaMusicService.NEXT
                    "previous" -> DanaMusicService.PREVIOUS
                    "stop" -> DanaMusicService.STOP
                    else -> DanaMusicService.PAUSE
                }
                if (DanaMusicService.running && DanaMusicService.command(this, local)) {
                    reply("فرمان کنترل موسیقی ارسال شد.")
                } else {
                    val code = when (action.command) {
                        "play", "resume" -> KeyEvent.KEYCODE_MEDIA_PLAY
                        "pause", "stop" -> KeyEvent.KEYCODE_MEDIA_PAUSE
                        "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
                        "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                        else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                    }
                    val ok = runCatching {
                        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
                    }.isSuccess
                    reply(if (ok) "فرمان به برنامه موسیقی ارسال شد."
                        else "کنترل موسیقی ممکن نبود.")
                }
            }
            is DanaAction.Connectivity -> notifyForUser(text,
                "برای تغییر ${if (action.radio == "wifi") "وای‌فای" else "بلوتوث"} پنل تنظیمات رو باز و تغییر رو تأیید کن")
            is DanaAction.Alarm -> {
                val now = System.currentTimeMillis()
                val at = DatedAlarmTime.nextDay(now, 0, action.hour, action.minute)
                    ?: DatedAlarmTime.nextDay(now, 1, action.hour, action.minute)
                scheduleFromWake(at, action.label, text)
            }
            is DanaAction.DatedAlarm -> {
                val now = System.currentTimeMillis()
                val at = if (action.dayOffset != null)
                    DatedAlarmTime.nextDay(now, action.dayOffset, action.hour, action.minute)
                else action.weekday?.let { DatedAlarmTime.nextWeekday(now, it, action.hour, action.minute) }
                scheduleFromWake(at, action.label, text)
            }
            is DanaAction.PersianDateAlarm -> {
                val at = DatedAlarmTime.nextPersianDate(System.currentTimeMillis(),
                    action.month, action.day, action.hour, action.minute)
                scheduleFromWake(at, action.label, text)
            }

            is DanaAction.App -> {
                // Android restricts background activity launches. An optional, user-enabled
                // system-bound accessibility service can start the named app without reading
                // screen content. Otherwise ask the user to tap the notification.
                val launched = if (MainActivity.visibleNow) {
                    AppLauncher.resolve(this, action.name)?.let { app ->
                        runCatching { startActivity(app) }.isSuccess
                    } ?: false
                } else DanaHandsFreeService.launchNamedApp(action.name)
                if (launched) reply("${action.name} رو باز کردم.")
                else notifyForUser(text,
                    "برای باز کردن ${action.name} اعلان را لمس کن یا دسترسی اجرای صوتی را فعال کن")
            }
            is DanaAction.Unknown -> reply("این فرمان رو متوجه نشدم؛ دوباره بگو.")
            else -> notifyForUser(text, "برای ادامه فرمان، اعلان دانا را لمس کن")
        }
    }

    private fun scheduleFromWake(at: Long?, label: String?, original: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifyForUser(original, "برای آلارم تاریخ‌دار، اجازه اعلان‌های دانا را فعال کن")
            return
        }
        if (at == null) { reply("این زمان گذشته یا تاریخ معتبر نیست."); return }
        val saved = runCatching { DatedAlarmScheduler(this).schedule(at, label) }.getOrNull()
        if (saved == null) reply("ثبت آلارم ممکن نشد؛ برنامه را باز و تنظیمات آلارم را بررسی کن.")
        else {
            val whenText = android.icu.text.SimpleDateFormat("EEEE d MMMM ساعت HH:mm",
                android.icu.util.ULocale("fa_IR@calendar=persian")).format(Date(at))
            reply(if (saved.exact) "آلارم دقیق برای $whenText ثبت شد."
                else "آلارم برای $whenText ثبت شد، اما بدون مجوز آلارم دقیق ممکنه با تأخیر اجرا بشه.")
        }
    }

    private fun reply(message: String) {
        if (stopped) return
        stage = "reply"
        updateNotification(message)
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
        commandSpeech?.close(); commandSpeech = null
        closeRecognizer(); model?.close(); model = null
        voice?.close(); voice = null
        worker.shutdownNow()
        getSharedPreferences("wake_runtime", MODE_PRIVATE).edit().putBoolean("running", false).apply()
        super.onDestroy()
    }
}
