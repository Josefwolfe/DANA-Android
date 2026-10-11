package com.dana.ai

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.speech.SpeechRecognizer
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Generated only after pressing the diagnostics button. No raw audio, commands, names or tokens. */
object DiagnosticReport {
    private fun flag(value: Boolean) = if (value) "YES" else "NO"

    fun build(context: Context, voiceReady: Boolean, voiceCount: Int, offlineActive: Boolean, ttsDetails: String = ""): String {
        val memory = UserMemory(context)
        val wake = context.getSharedPreferences("wake_runtime", Context.MODE_PRIVATE)
        val root = context.getDir(DanaWakeService.MODEL_DIR, Context.MODE_PRIVATE)
        val model = File(root, "am/final.mdl")
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val battery = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val notification = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        }.getOrDefault("unknown")
        fun granted(permission: String) = flag(context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED)
        fun check(label: String, value: Any?) = "$label: ${value ?: "unknown"}\n"
        val audioPermission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
            else Manifest.permission.READ_EXTERNAL_STORAGE
        val exactAlarm = if (Build.VERSION.SDK_INT >= 31)
            runCatching { flag(alarm.canScheduleExactAlarms()) }.getOrDefault("unknown") else "not_required"
        val onDevice = if (Build.VERSION.SDK_INT >= 31)
            runCatching { flag(SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) }.getOrDefault("unknown")
            else "not_available"
        val recognition = runCatching { flag(SpeechRecognizer.isRecognitionAvailable(context)) }
            .getOrDefault("unknown")
        val modelSizeMb = if (model.isFile) model.length() / (1024L * 1024L) else 0L
        val freeMb = root.usableSpace / (1024L * 1024L)
        val lastFailure = DiagnosticEventRules.failureCategory(wake.getString("last_stop_reason", null))
        return buildString {
            append("DANA DIAGNOSTICS v1.9.8 (user initiated)\n")
            append("=================================\n")
            append(check("Generated", now))
            append(check("App version", version))
            append(check("Android API", Build.VERSION.SDK_INT))
            append(check("Android release", Build.VERSION.RELEASE))
            append(check("Manufacturer", Build.MANUFACTURER))
            append(check("Model", Build.MODEL))
            append("\n[PRIVACY]\n")
            append(check("Private mode", flag(memory.privateMode)))
            append(check("Online speech consent", flag(memory.microphoneDisclosureAccepted)))
            append(check("Cloud AI enabled", flag(memory.cloudAiEnabled && !memory.privateMode)))
            append("Private mode disables new diagnostic event recording.\n")
            append("\n[PERMISSIONS]\n")
            append(check("Microphone", granted(Manifest.permission.RECORD_AUDIO)))
            append(check("Notifications", if (Build.VERSION.SDK_INT >= 33)
                granted(Manifest.permission.POST_NOTIFICATIONS) else "not_required"))
            append(check("Notifications enabled", flag(notification.areNotificationsEnabled())))
            append(check("Music files", granted(audioPermission)))
            append(check("Contacts", granted(Manifest.permission.READ_CONTACTS)))
            append(check("Camera / torch", granted(Manifest.permission.CAMERA)))
            append(check("Modify system settings", flag(Settings.System.canWrite(context))))
            append(check("Exact alarms", exactAlarm))
            append("\n[WAKE / VOSK]\n")
            append(check("Model files present", flag(WakeModelInstaller.installed(root))))
            append(check("Model final.mdl size MB", modelSizeMb))
            append(check("Free internal storage MB", freeMb))
            append(check("Wake service in process", flag(DanaWakeService.running(context))))
            append(check("Wake runtime ready", flag(wake.getBoolean("ready", false))))
            append(check("Wake runtime stage", wake.getString("stage", "unknown")
                ?.uppercase(Locale.ROOT)?.let { DiagnosticEventRules.safeToken(it) } ?: "unknown"))
            append(check("Wake runtime running flag", flag(wake.getBoolean("running", false))))
            append(check("Wake auto resume", flag(memory.wakeAutoResume)))
            append(check("Last wake failure category", lastFailure))
            append(check("One-shot Vosk listening", flag(offlineActive)))
            append("\n[WHISPER OFFLINE COMMANDS]\n")
            val whisperRoot = context.getDir(WhisperModelInstaller.DIR, Context.MODE_PRIVATE)
            append(check("Whisper model installed", flag(WhisperModelInstaller.installed(whisperRoot))))
            append(check("Whisper native library available", flag(WhisperNative.available)))
            append(check("Whisper model MB", if (WhisperModelInstaller.installed(whisperRoot))
                WhisperModelInstaller.model(whisperRoot).length() / (1024 * 1024) else 0))
            append(WhisperMetrics.report())
            append(check("Whisper disabled for current wake service", flag(
                wake.getBoolean("whisper_disabled_for_session", false))))
            append("Offline command engine preference: WHISPER (bounded) > ANDROID_ON_DEVICE > VOSK\n")
            append("\n[SPEECH / TTS]\n")
            append(check("Android recognizer available", recognition))
            append(check("Android on-device recognizer", onDevice))
            append(check("Persian TTS ready", flag(voiceReady)))
            append(check("Persian TTS voice count", voiceCount))
            append(ttsDetails)
            append(check("Continuous conversation", flag(memory.conversationMode)))
            append("\n[POWER]\n")
            append(check("Battery percent", battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)))
            append(check("Battery saver", flag(power.isPowerSaveMode)))
            append(check("Battery optimization exempt", runCatching {
                flag(power.isIgnoringBatteryOptimizations(context.packageName))
            }.getOrDefault("unknown")))
            append("\n[VOICE SESSION COUNTERS — MEMORY ONLY]\n")
            append(WakeSessionMetrics.report())
            append("\n[OFFLINE COMMAND RECOGNITION — MEMORY ONLY]\n")
            append(WakeAsrMetrics.report())
            append("\n[WAKE COMMAND EXECUTION — MEMORY ONLY]\n")
            append(WakeExecutionMetrics.report())
            append("Counts reset when Android terminates the app process.\n")
            append("\n[OPTIONAL TRANSCRIPT PROBE]\n")
            append(WakeSpeechProbe.report())
            append("\n[COMMAND STATISTICS]\n")
            append(runCatching { TestLog(context).report() }
                .getOrDefault("(آمار فرمان‌ها در دسترس نیست)"))
                .append('\n')
            append("\n[RECENT SAFE EVENTS (max 80)]\n")
            append(DiagnosticEvents.recent(context)).append('\n')
            append("\n[REPORT LIMITATIONS]\n")
            append("No audio, contacts, messages, notes, device IDs, tokens, or file paths are included by default.\n")
            append("An explicitly enabled 10-minute transcript probe may show the last recognized phrase.\n")
            append("Dispatched actions are not proof of completion by other apps.\n")
            append("This is an app diagnostic snapshot, not Android logcat or a full system crash trace.\n")
        }
    }
}
