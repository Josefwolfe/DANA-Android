package com.dana.ai

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.ToneGenerator
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
class DanaWakeService : Service() {
    companion object {
        const val ACTION_START = "com.dana.ai.START_WAKE"
        const val ACTION_STOP = "com.dana.ai.STOP_WAKE"
        const val ACTION_PRIVACY_CHANGED = "com.dana.ai.WAKE_PRIVACY_CHANGED"
        const val ACTION_TTS_CHANGED = "com.dana.ai.WAKE_TTS_CHANGED"
        const val ACTION_UI_COMMAND_COMPLETE = "com.dana.ai.WAKE_UI_COMMAND_COMPLETE"
        const val ACTION_OPEN_COMMAND = "com.dana.ai.OPEN_WAKE_COMMAND"
        const val EXTRA_COMMAND = "wake_command"
        const val CHANNEL = "dana_wake_experimental"
        const val ACTION_CHANNEL = "dana_wake_action_results"
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
    // Every Vosk listener is tied to one generation. Shutdown can emit a late final result.
    private var recognitionGeneration = 0L
    private val commandCollector = WakeCommandCollector()
    private val finishCommandAfterSilence = Runnable {
        if (!stopped && stage == "command_offline") finishCollectedCommand()
    }
    private var voice: DanaVoice? = null
    private var commandSpeech: SpeechManager? = null
    private var stage = "init" // init, wake, reply, command_online, command_offline, paused, closed
    private val uiHandoffTimeout = Runnable {
        if (!stopped && stage == "ui_handoff") startWakeRecognition()
    }
    private val responseWatchdog = Runnable {
        if (!stopped && stage == "reply") startWakeRecognition()
    }
    private val onlineTimeout = Runnable {
        if (!stopped && stage == "command_online") startCommandRecognitionOffline()
    }
    private val modelLoadTimeout = Runnable {
        if (!stopped && stage == "loading") {
            failWake("بارگذاری مدل فارسی بیش از ۹۰ ثانیه طول کشید. فضای حافظه و سلامت ZIP را بررسی کن.")
        }
    }
    private fun failWake(reason: String) {
        DiagnosticEvents.record(this, "WAKE_SERVICE_FAILED", DiagnosticEventRules.failureCategory(reason))
        getSharedPreferences("wake_runtime", MODE_PRIVATE).edit()
            .putBoolean("ready", false)
            .putString("stage", "failed")
            .putString("last_stop_reason", reason).apply()
        updateNotification(reason)
        stopSelf()
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
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).apply {
            createNotificationChannel(channel)
            createNotificationChannel(NotificationChannel(ACTION_CHANNEL,
                "نتیجه فرمان‌های صوتی دانا", NotificationManager.IMPORTANCE_DEFAULT))
        }
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
            .putBoolean("running", true).putBoolean("ready", false)
            .putString("stage", "initializing")
            .remove("last_stop_reason").apply()
        aliveInProcess = true
        DiagnosticEvents.record(this, "WAKE_SERVICE_CREATED")
        voice = DanaVoice(this) { /* If TTS unavailable, notification remains readable. */ }
        commandSpeech = SpeechManager(this,
            onResult = { phrase -> main.post {
                if (!stopped && stage == "command_online") {
                    main.removeCallbacks(onlineTimeout)
                    WakeSessionMetrics.commandCaptured()
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
        if (intent?.action == ACTION_UI_COMMAND_COMPLETE) {
            if (stage == "ui_handoff") {
                main.removeCallbacks(uiHandoffTimeout)
                main.postDelayed({ if (!stopped && stage == "ui_handoff") startWakeRecognition() }, 450L)
            }
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_TTS_CHANGED) {
            voice?.close()
            voice = DanaVoice(this) { available ->
                DiagnosticEvents.record(this, "WAKE_TTS_RELOADED", if (available) "READY" else "UNAVAILABLE")
            }
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_PRIVACY_CHANGED) {
            voice?.refresh() // Enforce local-only TTS when private mode changes.
            // Stop any potentially online Android recognition immediately when private mode
            // becomes active; the local Vosk wake service itself remains enabled.
            if (stage == "command_online" || stage == "command_offline") {
                commandSpeech?.cancel()
                main.removeCallbacks(onlineTimeout)
                startWakeRecognition()
            }
            updateNotification(if (UserMemory(this).privateMode)
                "حالت خصوصی: بیدارباش و فرمان‌ها فقط با Vosk محلی" else
                "بیدارباش فعال؛ آماده دریافت «دانا»")
            return START_NOT_STICKY
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            failWake("اجازه میکروفون داده نشده؛ بیدارباش متوقف شد")
            return START_NOT_STICKY
        }
        if (stage == "init") {
            val directory = getDir(MODEL_DIR, MODE_PRIVATE)
            if (!WakeModelInstaller.installed(directory)) {
                failWake("مدل فارسی نصب نیست یا فایل‌های اصلی آن ناقص‌اند؛ مدل ZIP را دوباره نصب کن.")
                return START_NOT_STICKY
            }
            stage = "loading"
            DiagnosticEvents.record(this, "WAKE_MODEL_LOADING")
            getSharedPreferences("wake_runtime", MODE_PRIVATE).edit().putBoolean("ready", false).putString("stage", "loading").apply()
            updateNotification("در حال بازکردن مدل فارسی Vosk… ممکن است کمی زمان ببرد")
            main.postDelayed(modelLoadTimeout, 90_000L)
            worker.execute {
                val loaded = runCatching { Model(directory.absolutePath) }
                main.post {
                    if (stopped) { loaded.getOrNull()?.close(); return@post }
                    main.removeCallbacks(modelLoadTimeout)
                    if (loaded.isFailure) {
                        DiagnosticEvents.record(this, "WAKE_MODEL_ERROR",
                            loaded.exceptionOrNull()?.javaClass?.simpleName?.uppercase(Locale.ROOT) ?: "UNKNOWN")
                        val reason = loaded.exceptionOrNull()?.localizedMessage?.take(180)
                            ?: "ساختار فایل مدل ناقص است"
                        failWake("مدل فارسی Vosk باز نشد: $reason")
                    } else {
                        DiagnosticEvents.record(this, "WAKE_MODEL_LOADED")
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
        main.removeCallbacks(finishCommandAfterSilence)
        main.removeCallbacks(uiHandoffTimeout)
        main.removeCallbacks(responseWatchdog)
        commandCollector.reset()
        closeRecognizer()
        try {
            // Unrestricted offline recognition allows both "دانا" then a second turn,
            // AND a wake word plus an instruction spoken as one sentence.
            // Grammar-limited wake decoding previously discarded the command suffix.
            val recognizer = Recognizer(requireNotNull(model), 16000.0f)
            val token = ++recognitionGeneration
            this.recognizer = recognizer
            stage = "wake"
            recognition = SpeechService(recognizer, 16000.0f).also {
                it.startListening(listenerFor("wake", token))
            }
            DiagnosticEvents.record(this, "WAKE_LISTENING")
            getSharedPreferences("wake_runtime", MODE_PRIVATE).edit().putBoolean("ready", true).putString("stage", "wake").apply()
            updateNotification("فعال: منتظر «دانا» • برای توقف، اعلان را باز کن")
        } catch (e: Exception) { recoverOrStop("Vosk در بیدارباش خطا داد: ${e.localizedMessage ?: "خطا"}") }
    }

    /** Wake-triggered commands are always decoded locally, regardless of private mode.
     * The visible Activity may separately offer Android speech after user consent. */
    private fun startCommandRecognition() {
        if (!stopped) startCommandRecognitionOffline()
    }

    private fun startCommandRecognitionOffline() {
        if (stopped) return
        main.removeCallbacks(onlineTimeout)
        stage = "command_offline" // Set before cancelling online recognition callbacks.
        getSharedPreferences("wake_runtime", MODE_PRIVATE).edit().putString("stage", stage).apply()
        commandSpeech?.cancel()
        main.removeCallbacks(finishCommandAfterSilence)
        commandCollector.reset()
        closeRecognizer()
        DiagnosticEvents.record(this, "WAKE_COMMAND_START", "VOSK_LOCAL")
        WakeSessionMetrics.commandWindow()
        try {
            val next = Recognizer(requireNotNull(model), 16000.0f)
            val token = ++recognitionGeneration
            recognizer = next
            recognition = SpeechService(next, 16000.0f).also {
                it.startListening(listenerFor("command_offline", token), 12_000)
            }
            updateNotification("فرمان فارسی آفلاین را بگو…")
        } catch (e: Exception) { recoverOrStop("Vosk در تشخیص فرمان خطا داد: ${e.localizedMessage ?: "خطا"}") }
    }

    private fun listenerFor(expectedStage: String, token: Long): RecognitionListener =
        object : RecognitionListener {
            private fun receive(hypothesis: String?, final: Boolean) {
                // Never accept late results from the previous Vosk microphone session.
                main.post {
                    if (!WakeRecognitionGate.accepts(recognitionGeneration, token, stage, expectedStage)) {
                        WakeSessionMetrics.staleCallback()
                        return@post
                    }
                    if (expectedStage == "command_offline") {
                        val text = runCatching {
                            JSONObject(hypothesis ?: "{}").optString("text").trim()
                        }.getOrDefault("")
                        if (text.isNotBlank()) {
                            commandCollector.add(text)
                            main.removeCallbacks(finishCommandAfterSilence)
                            // Wait for a second segment rather than discarding the first.
                            // Even known commands get a short grace period for negation.
                            main.postDelayed(finishCommandAfterSilence,
                                if (commandCollector.isKnown()) 1_100L else 2_000L)
                        }
                        if (final) finishCollectedCommand()
                    } else processHypothesis(hypothesis, expectedStage)
                }
            }
            override fun onResult(hypothesis: String?) = receive(hypothesis, false)
            override fun onFinalResult(hypothesis: String?) = receive(hypothesis, true)
            override fun onPartialResult(hypothesis: String?) { /* never execute partial speech */ }
            override fun onTimeout() { main.post {
                if (WakeRecognitionGate.accepts(recognitionGeneration, token, stage, expectedStage) &&
                    expectedStage == "command_offline") {
                    if (commandCollector.hasSpeech()) finishCollectedCommand()
                    else {
                        WakeSessionMetrics.commandTimeout()
                        DiagnosticEvents.record(this@DanaWakeService, "WAKE_COMMAND_TIMEOUT")
                        startWakeRecognition()
                    }
                }
            } }
            override fun onError(e: Exception?) { main.post {
                if (WakeRecognitionGate.accepts(recognitionGeneration, token, stage, expectedStage)) {
                    if (expectedStage == "command_offline" && commandCollector.hasSpeech())
                        finishCollectedCommand()
                    else recoverOrStop("خطای میکروفون Vosk: ${e?.localizedMessage ?: "نامشخص"}")
                }
            } }
        }

    private fun finishCollectedCommand() {
        if (stopped || stage != "command_offline") return
        main.removeCallbacks(finishCommandAfterSilence)
        val phrase = commandCollector.phrase()
        val segments = commandCollector.segmentCount()
        commandCollector.reset()
        closeRecognizer()
        if (phrase.isBlank()) {
            WakeSessionMetrics.commandTimeout()
            startWakeRecognition()
            return
        }
        WakeSpeechProbe.capture(phrase)
        WakeSessionMetrics.commandCaptured()
        DiagnosticEvents.record(this, "WAKE_COMMAND_HEARD",
            if (segments > 1) "MULTI_SEGMENT" else "SINGLE_SEGMENT")
        stage = "reply"
        handleCommand(phrase)
    }

    private fun wakeAcknowledgement() {
        // No Persian TTS voice is installed on some phones. A short local tone still tells
        // the user when to speak; this does not use the internet or a speech provider.
        val tone = runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 75) }.getOrNull()
        if (tone != null) {
            runCatching { tone.startTone(ToneGenerator.TONE_PROP_BEEP, 130) }
            main.postDelayed({ runCatching { tone.release() } }, 220L)
        }
    }

    private fun processHypothesis(hypothesis: String?, sourceStage: String) {
        if (stopped) return
        val text = runCatching { JSONObject(hypothesis ?: "{}").optString("text").trim() }.getOrDefault("")
        if (text.isBlank()) return
        // Opt-in only: helps diagnose whether unrestricted Vosk hears the wake word at all.
        if (sourceStage == "wake") WakeSpeechProbe.capture(text)
        when (sourceStage) {
            "wake" -> {
                val inlineCommand = WakePhrase.extractAfterWake(text) ?: return
                DiagnosticEvents.record(this, "WAKE_WORD_DETECTED")
                WakeSessionMetrics.wakeDetected()
                closeRecognizer()
                if (inlineCommand.isNotBlank()) {
                    // One-turn hands-free command; do not require the microphone button.
                    WakeSpeechProbe.capture(inlineCommand)
                    WakeSessionMetrics.commandWindow()
                    WakeSessionMetrics.commandCaptured()
                    DiagnosticEvents.record(this, "WAKE_COMMAND_HEARD", "SAME_UTTERANCE")
                    stage = "reply"
                    handleCommand(inlineCommand)
                    return
                }
                stage = "awaiting_command"
                getSharedPreferences("wake_runtime", MODE_PRIVATE).edit()
                    .putString("stage", stage).apply()
                updateNotification("دانا شنید؛ بعد از بوق، فرمان را بگو")
                wakeAcknowledgement()
                // Always listen for the next turn using a *full* Persian Vosk recognizer.
                // Never parse a command from the restricted wake grammar.
                main.postDelayed({
                    if (!stopped && stage == "awaiting_command") startCommandRecognition()
                }, 220L) // Short gap after 130 ms beep; avoid clipping the first spoken word.
            }
        }
    }

    /** UI-required actions are handled by the visible Activity without a second microphone tap.
     * If the app is backgrounded Android may forbid launching UI; then we show a tap action. */
    private fun routeToVisibleUi(text: String): Boolean {
        if (!MainActivity.dispatchWakeCommand(text)) return false
        stage = "ui_handoff"
        getSharedPreferences("wake_runtime", MODE_PRIVATE).edit().putString("stage", stage).apply()
        WakeExecutionMetrics.outcome("UI_ROUTED")
        DiagnosticEvents.record(this, "WAKE_ACTION_ROUTED", "VISIBLE_UI")
        updateNotification("فرمان به صفحه دانا ارسال شد؛ منتظر نتیجه هستم")
        main.removeCallbacks(uiHandoffTimeout)
        main.postDelayed(uiHandoffTimeout, 8_000L)
        return true
    }

    private fun handleCommand(text: String, inferred: DanaAction? = null) {
        if (stopped) return
        val action = inferred ?: CommandEngine.parse(text)
        DiagnosticEvents.record(this, if (action is DanaAction.Unknown) "WAKE_COMMAND_UNKNOWN" else "WAKE_COMMAND_PARSED",
            action.javaClass.simpleName.uppercase(Locale.ROOT))
        WakeExecutionMetrics.received(action.javaClass.simpleName)
        if (action is DanaAction.Unknown) WakeSessionMetrics.unknownCommand()
        else WakeSessionMetrics.knownCommand()
        if (action is DanaAction.Unknown && inferred == null) {
            val preferences = UserMemory(this)
            if (preferences.cloudAiEnabled && !preferences.privateMode &&
                CloudIntentClient.isValidEndpoint(preferences.cloudEndpoint) &&
                !SafetyPolicy.explicitlyNegated(text)) {
                stage = "reply"
                updateNotification("در حال بررسی فرمان با هوش مصنوعی اختیاری…")
                val endpoint = preferences.cloudEndpoint
                worker.execute {
                    val result = runCatching {
                        CloudIntentClient(endpoint, SecureTokenStore(this).getToken()).infer(text)
                    }.getOrNull()
                    main.post {
                        if (!stopped && !UserMemory(this).privateMode && stage == "reply")
                            handleCommand(text, result ?: DanaAction.Unknown(text))
                    }
                }
                return
            }
        }
        val keyguard = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        val locked = keyguard.isDeviceLocked || keyguard.isKeyguardLocked
        // Lock-screen actions that expose information or launch other apps need unlocking.
        if (locked && !SafetyPolicy.permittedWithLockedScreen(action)) {
            notifyForUser(text, "برای این فرمان ابتدا قفل گوشی را باز کن")
            return
        }
        // These operations require an Activity (or explicit Android user confirmation).
        // A visible DANA screen can execute them immediately, without pressing the mic.
        if (WakeDispatchPolicy.requiresVisibleUi(action) && MainActivity.visibleNow &&
            routeToVisibleUi(text)) return
        when (action) {
            is DanaAction.Answer -> reply(action.text)
            DanaAction.Greeting -> reply("سلام، گوش می‌دم. چه کاری داری؟")
            DanaAction.Help -> reply("می‌تونم ساعت و تاریخ رو بگم، موسیقی رو کنترل کنم، " +
                "برنامه باز کنم، آلارم و یادآوری بذارم و کارهات رو ثبت کنم.")
            DanaAction.TodayDate -> {
                val today = android.icu.text.SimpleDateFormat("EEEE d MMMM yyyy",
                    android.icu.util.ULocale("fa_IR@calendar=persian")).format(Date())
                reply("امروز $today است.")
            }
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
            is DanaAction.MediaMute -> {
                val ok = runCatching {
                    audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                        if (action.enabled) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, 0)
                }.isSuccess
                reply(if (!ok) "کنترل صدای موسیقی ممکن نشد."
                    else if (action.enabled) "صدای موسیقی بی‌صدا شد."
                    else "صدای موسیقی دوباره فعال شد.")
            }
            is DanaAction.Brightness -> notifyForUser(text,
                "برای کنترل نور صفحه، دانا رو باز کن؛ ممکنه مجوز تغییر تنظیمات لازم باشه")
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

            is DanaAction.NoteAdd -> {
                if (UserMemory(this).privateMode) reply("در حالت خصوصی یادداشت ذخیره نمی‌کنم.")
                else if (action.text.isBlank() || action.text.length > 160) reply("یادداشت باید کوتاه باشه.")
                else {
                    PersonalStore(this).addNote(action.text)
                    reply("یادداشت ذخیره شد.")
                }
            }
            DanaAction.NoteList -> {
                val notes = PersonalStore(this).notes().take(4)
                reply(if (notes.isEmpty()) "هنوز یادداشتی نداری."
                    else "یادداشت‌های اخیر: " + notes.joinToString("؛ "))
            }
            is DanaAction.TaskAdd -> {
                if (UserMemory(this).privateMode) reply("در حالت خصوصی کار جدید ذخیره نمی‌کنم.")
                else if (action.text.isBlank() || action.text.length > 160) reply("عنوان کار باید کوتاه باشه.")
                else {
                    PersonalStore(this).addTask(action.text)
                    reply("کار جدید ثبت شد.")
                }
            }
            DanaAction.TaskList -> {
                val pending = PersonalStore(this).tasks().filterNot { it.done }.take(4)
                reply(if (pending.isEmpty()) "کار انجام‌نشده‌ای در فهرست نداری."
                    else "کارهای باقی‌مونده: " + pending.joinToString("؛ ") { it.text })
            }
            is DanaAction.TaskDone -> reply(if (PersonalStore(this).markFirstMatchingDone(action.text))
                "انجام شدن کار ثبت شد." else "این کار رو در فهرست پیدا نکردم.")
            is DanaAction.ReminderIn -> scheduleReminderFromWake(
                ReminderTimeResolver.inMinutes(System.currentTimeMillis(), action.minutes),
                action.text, text)
            is DanaAction.ReminderAt -> scheduleReminderFromWake(
                ReminderTimeResolver.atLocal(System.currentTimeMillis(), action.dayOffset,
                    action.hour, action.minute), action.text, text)
            DanaAction.ReminderList -> {
                val pending = ReminderStore(this).pending().take(4)
                reply(if (pending.isEmpty()) "یادآوری در انتظار نداری."
                    else "یادآوری‌های بعدی: " + pending.joinToString("؛ ") { it.text })
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
                if (launched) reply("درخواست باز کردن ${action.name} ارسال شد.", "APP_LAUNCH_REQUESTED")
                else notifyForUser(text,
                    "باز کردن ${action.name} از پس‌زمینه مجاز نشد؛ برای اجرا این اعلان را لمس کن")
            }
            is DanaAction.Unknown -> reply("این فرمان در موتور آفلاین دانا شناخته نمی‌شه. کوتاه‌تر بگو یا بگو راهنما.", "UNSUPPORTED")
            else -> notifyForUser(text, "برای ادامه فرمان، اعلان دانا را لمس کن")
        }
    }

    private fun scheduleReminderFromWake(at: Long, reminderText: String, original: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifyForUser(original, "برای یادآوری‌ها، مجوز اعلان‌های دانا رو فعال کن")
            return
        }
        val notifier = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (!notifier.areNotificationsEnabled()) {
            notifyForUser(original, "اعلان‌های دانا در تنظیمات گوشی غیرفعال است")
            return
        }
        if (reminderText.isBlank() || reminderText.length > 160 || at <= System.currentTimeMillis()) {
            reply("متن یا زمان یادآوری معتبر نیست.")
            return
        }
        val saved = runCatching {
            val item = ReminderStore(this).add(reminderText, at)
            ReminderScheduler(this).schedule(item)
        }.isSuccess
        reply(if (saved) "یادآوری ثبت شد. ممکنه به خاطر ذخیره باتری کمی دیرتر اعلام بشه."
            else "ثبت یادآوری ممکن نشد؛ تنظیمات اعلان رو بررسی کن.")
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

    private fun reply(message: String, outcome: String = "RESPONSE_ONLY") {
        if (stopped) return
        stage = "reply"
        WakeExecutionMetrics.outcome(outcome)
        DiagnosticEvents.record(this, "WAKE_ACTION_RESULT", outcome)
        MainActivity.showWakeFeedback(message)
        updateNotification(message)
        main.removeCallbacks(responseWatchdog)
        main.postDelayed(responseWatchdog, if (voice?.isReady() == true) 50_000L else 15_000L)
        if (voice?.isReady() != true) {
            // A silent TTS engine must not make an otherwise parsed command look ignored.
            val open = PendingIntent.getActivity(this, 39,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val result = Notification.Builder(this, ACTION_CHANNEL)
                .setSmallIcon(R.drawable.ic_dana)
                .setContentTitle("دانا: نتیجه فرمان صوتی")
                .setContentText(message)
                .setStyle(Notification.BigTextStyle().bigText(message))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .build()
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(7122, result)
            wakeAcknowledgement()
        }
        voice?.speak(message) { main.postDelayed({ startWakeRecognition() }, 300) }
            ?: main.postDelayed({ startWakeRecognition() }, 300)
    }

    private fun notifyForUser(text: String, message: String) {
        stage = "reply"
        WakeExecutionMetrics.outcome("USER_TAP_REQUIRED")
        DiagnosticEvents.record(this, "WAKE_ACTION_RESULT", "USER_TAP_REQUIRED")
        MainActivity.showWakeFeedback(message)
        val pending = PendingIntent.getActivity(this, 37,
            Intent(this, MainActivity::class.java).apply {
                action = ACTION_OPEN_COMMAND
                putExtra(EXTRA_COMMAND, text)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val note = Notification.Builder(this, ACTION_CHANNEL)
            .setSmallIcon(R.drawable.ic_dana).setContentTitle("دانا: برای اجرای فرمان لمس کن")
            .setContentText(message).setStyle(Notification.BigTextStyle().bigText(message))
            .setContentIntent(pending).setAutoCancel(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(7121, note)
        // Do not misreport this as completed; keep the actionable notification visible.
        updateNotification(message)
        main.removeCallbacks(responseWatchdog)
        main.postDelayed(responseWatchdog, if (voice?.isReady() == true) 50_000L else 15_000L)
        if (voice?.isReady() != true) wakeAcknowledgement()
        voice?.speak("برای انجام این فرمان، اعلان دانا را لمس کن") {
            main.postDelayed({ startWakeRecognition() }, 300)
        } ?: main.postDelayed({ startWakeRecognition() }, 300)
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
        // Invalidate callbacks BEFORE cancel/shutdown; Vosk may emit a final result there.
        recognitionGeneration++
        recognition?.let { runCatching { it.cancel() }; runCatching { it.shutdown() } }
        recognition = null
        recognizer?.close(); recognizer = null
    }
    private fun recoverOrStop(reason: String = "خطای شنود Vosk") {
        if (stopped) return
        DiagnosticEvents.record(this, "WAKE_RECOVERY", DiagnosticEventRules.failureCategory(reason))
        closeRecognizer()
        errors++
        if (errors > 3) failWake("شنود بعد از چند خطا متوقف شد: ${reason.take(180)}")
        else main.postDelayed({ startWakeRecognition() }, (1000L * errors))
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        DiagnosticEvents.record(this, "WAKE_SERVICE_DESTROYED")
        stopped = true; stage = "closed"; aliveInProcess = false
        if (powerReceiverRegistered) {
            unregisterReceiver(powerReceiver)
            powerReceiverRegistered = false
        }
        main.removeCallbacksAndMessages(null)
        commandCollector.reset()
        commandSpeech?.close(); commandSpeech = null
        closeRecognizer(); model?.close(); model = null
        voice?.close(); voice = null
        worker.shutdownNow()
        getSharedPreferences("wake_runtime", MODE_PRIVATE).edit()
            .putBoolean("running", false).putBoolean("ready", false)
            .putString("stage", "stopped").apply()
        super.onDestroy()
    }
}
