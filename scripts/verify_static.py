#!/usr/bin/env python3
"""Static checks independent of the Android SDK; not a substitute for Gradle build."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
main = root / 'app/src/main'
xml_files = sorted((main / 'res').rglob('*.xml')) + [main / 'AndroidManifest.xml']
for file in xml_files:
    ET.parse(file)
print(f'PASS: {len(xml_files)} XML files parse.')

ns = '{http://schemas.android.com/apk/res/android}'
manifest = ET.parse(main / 'AndroidManifest.xml').getroot()
components = [c.get(ns + 'name', '') for c in manifest.findall('.//activity') +
              manifest.findall('.//service') + manifest.findall('.//receiver')]
for component in components:
    if component.startswith('.'):
        cls = main / 'java/com/dana/ai' / (component[1:] + '.kt')
        assert cls.is_file(), f'Manifest references missing class: {component}'
print(f'PASS: {len(components)} manifest components have source files.')

ids = set()
for file in (main / 'res/layout').rglob('*.xml'):
    ids |= set(re.findall(r'@\+id/([a-zA-Z0-9_]+)', file.read_text()))
for file in (main / 'java').rglob('*.kt'):
    used = set(re.findall(r'R\.id\.([a-zA-Z0-9_]+)', file.read_text()))
    assert used <= ids, f'{file.name}: missing view IDs: {sorted(used - ids)}'
print(f'PASS: all {len(ids)} declared view IDs cover Kotlin references.')

ignore = (root / '.gitignore').read_text()
assert 'local.properties' in ignore, 'Local SDK path must be ignored by Git.'
print('PASS: local.properties is Git-ignored.')

permissions = {p.get(ns + 'name') for p in manifest.findall('uses-permission')}
for needed in ('android.permission.RECEIVE_BOOT_COMPLETED', 'android.permission.POST_NOTIFICATIONS'):
    assert needed in permissions, f'Missing permission: {needed}'
receivers = {c.get(ns + 'name', ''): c for c in manifest.findall('.//receiver')}
assert '.ReminderReceiver' in receivers and '.ReminderBootReceiver' in receivers
assert receivers['.ReminderReceiver'].get(ns + 'exported') == 'false'
boot = receivers['.ReminderBootReceiver']
assert boot.find('.//action').get(ns + 'name') == 'android.intent.action.BOOT_COMPLETED'
assert boot.get(ns + 'exported') == 'false'

source = (main / 'java/com/dana/ai/ReminderScheduler.kt').read_text()
assert 'setAndAllowWhileIdle' in source and 'setExactAndAllowWhileIdle' not in source
assert (main / 'java/com/dana/ai/ReminderTimeResolver.kt').is_file()
print('PASS: reminder permission, receiver and inexact scheduling checks.')

quick = (main / 'java/com/dana/ai/QuickAccessNotification.kt').read_text()
assert 'FLAG_IMMUTABLE' in quick and 'ACTION_VOICE' in quick
assert 'foregroundServiceType="microphone"' in (main / 'AndroidManifest.xml').read_text()
assert 'OPEN_VOICE_FROM_NOTIFICATION' in quick
assert 'quickAccessEnabled' in (main / 'java/com/dana/ai/UserMemory.kt').read_text()
assert 'AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK' in (main / 'java/com/dana/ai/DanaVoice.kt').read_text()
print('PASS: opt-in shortcut and microphone foreground service declarations.')

manifest_xml = (main / 'AndroidManifest.xml').read_text()
assert 'android.intent.action.TTS_SERVICE' in manifest_xml, 'TTS discovery is required on Android 11+'
assert 'android:allowBackup="false"' in manifest_xml, 'Do not silently export personal information via backup'
print('PASS: Android 11+ TTS visibility and backup privacy checks.')

# Build integrity: pinned Gradle wrapper and explicit permission limitations.
wrapper = (root / 'gradle/wrapper/gradle-wrapper.properties').read_text()
assert 'gradle-8.9-bin.zip' in wrapper
assert 'distributionSha256Sum=d725d707bfabd4dfdc958c624003b3c80accc03f7037b5122c4b1d0ef15cecab' in wrapper
bootstrap = (root / 'scripts/fetch_wrapper.py').read_text()
assert '498495120a03b9a6ab5d155f5de3c8f0d986a449153702fb80fc80e134484f17' in bootstrap
assert (root / 'gradlew').is_file() and (root / 'gradlew.bat').is_file()
assert 'SEND_SMS' not in permissions, 'SMS is a confirmed draft, not silent sending.'
assert 'android.permission.FOREGROUND_SERVICE_MICROPHONE' in permissions
assert 'android.permission.FOREGROUND_SERVICE' in permissions
wake = (main / 'java/com/dana/ai/DanaWakeService.kt').read_text()
assert 'START_NOT_STICKY' in wake
assert 'onBind' in wake and 'startForeground' in wake
assert 'DanaWakeService' not in (main / 'java/com/dana/ai/ReminderBootReceiver.kt').read_text()
assert 'MODE_IN_CALL' in wake and 'MODE_IN_COMMUNICATION' in wake
assert 'ACTION_STOP' in wake and 'SpeechService' in wake
assert 'WakeModelInstaller.installed' in wake
assert 'com.alphacephei:vosk-android:0.3.47' in (root / 'app/build.gradle.kts').read_text()
assert (main / 'java/com/dana/ai/CloudIntentClient.kt').is_file()
assert 'memory.cloudAiEnabled' in (main / 'java/com/dana/ai/MainActivity.kt').read_text()
assert 'https' in (main / 'java/com/dana/ai/CloudIntentClient.kt').read_text()
assert 'DanaAction.Contacts' in (main / 'java/com/dana/ai/MainActivity.kt').read_text()
print('PASS: pinned Gradle, opt-in Vosk wake, HTTPS gateway and contact routing.')

# RC1 hardening: safe lock-screen dispatch, sealed gateway token, and optional-only network.
pkg = main / 'java/com/dana/ai'
assert (pkg / 'SafetyPolicy.kt').is_file()
assert 'permittedWithLockedScreen' in (pkg / 'DanaWakeService.kt').read_text()
assert 'privateMode' in (pkg / 'DanaWakeService.kt').read_text()
assert 'WakeEnergyPolicy.stopReason' in (pkg / 'DanaWakeService.kt').read_text()
assert 'AndroidKeyStore' in (pkg / 'SecureTokenStore.kt').read_text()
assert 'SecureTokenStore' in (pkg / 'MainActivity.kt').read_text()
assert 'HttpsURLConnection' in (pkg / 'CloudIntentClient.kt').read_text()
assert 'instanceFollowRedirects = false' in (pkg / 'CloudIntentClient.kt').read_text()
assert (root / 'gateway/dana_gateway.py').is_file()
assert (root / 'gateway/tests/test_gateway_http.py').is_file()
for sensitive in ('*.jks', 'local.properties', '.env'):
    assert sensitive in ignore or sensitive.replace('*', '') in ignore, f'Ignore rule for {sensitive} missing'
print('PASS: RC1 safety, Keystore, battery, HTTPS, optional gateway, and secret-ignore checks.')

# v1.1 security-sensitive Android flows.
activity = (pkg / 'MainActivity.kt').read_text()
assert 'DialogState.ALIAS_CONFIRM -> when' in activity
assert 'AliasConsentPolicy.allowed(alias, explicitlyConfirmed = true' in activity
assert activity.count('memory.saveAlias(') == 1, 'Unconfirmed alias writes are forbidden'
assert 'private fun confirmAliasIfNeeded' in activity
assert 'runCatching {' in activity and 'CloudIntentClient(memory.cloudEndpoint' in activity
assert 'catch (_: RuntimeException)' in activity and 'startForegroundService' in activity
cloud = (pkg / 'CloudIntentClient.kt').read_text()
assert 'connection?.disconnect()' in cloud and 'return try {' in cloud
assert 'fetch_wrapper.py' in (root / 'scripts/build_apk.sh').read_text()
assert 'fetch_wrapper.ps1' in (root / 'scripts/build_apk.bat').read_text()
print('PASS: alias consent, HTTPS error recovery, foreground microphone failure and Gradle bootstrap.')

# v1.3 RC4 delivery/build hardening.
app_gradle = (root / 'app/build.gradle.kts').read_text()
assert 'versionName = "1.9.7-whisper-offline-beta"' in app_gradle and 'versionCode = 26' in app_gradle
assert 'DANA_KEYSTORE_PATH' in app_gradle and 'DANA_KEYSTORE_PASSWORD' in app_gradle
assert (root / 'app/proguard-rules.pro').is_file()
assert 'android:usesCleartextTraffic="false"' in manifest_xml
assert 'android:networkSecurityConfig="@xml/network_security_config"' in manifest_xml
net = main / 'res/xml/network_security_config.xml'
assert net.is_file() and 'cleartextTrafficPermitted="false"' in net.read_text()
for script in ('build_release.sh', 'build_release.bat', 'preflight.py'):
    assert (root / 'scripts' / script).is_file(), f'Missing RC3 build script: {script}'
assert (root / '.github/workflows/android-release.yml').is_file()
assert 'apksigner' in (root / '.github/workflows/android-release.yml').read_text()
assert (root / 'docs/RELEASE_BUILD_FA.md').is_file() and (root / 'docs/FINAL_HANDOFF_FA.md').is_file()
expected_wrapper = root / 'gradle/wrapper/gradle-wrapper.jar.sha256.expected'
assert expected_wrapper.is_file() and '498495120a03b9a6ab5d155f5de3c8f0d986a449153702fb80fc80e134484f17' in expected_wrapper.read_text()
print('PASS: release signing, cleartext blocking, preflight and final handoff checks.')

# v1.3 RC4 finalization: safer calls, system-only notification listener, and wrapper-independent CI.
app_gradle = (root / 'app/build.gradle.kts').read_text()
assert 'versionName = "1.9.7-whisper-offline-beta"' in app_gradle and 'versionCode = 26' in app_gradle
assert 'android.permission.CALL_PHONE' not in permissions, 'RC4 must use ACTION_DIAL, not direct-call permission.'
activity = (pkg / 'MainActivity.kt').read_text()
assert 'Intent.ACTION_CALL' not in activity
assert 'Intent.ACTION_DIAL' in activity
notification_service = next(c for c in manifest.findall('.//service') if c.get(ns + 'name') == '.DanaNotificationService')
assert notification_service.get(ns + 'exported') == 'false'
for workflow in ('android-debug.yml', 'android-release.yml'):
    wf = (root / '.github/workflows' / workflow).read_text()
    assert "gradle-version: '8.9'" in wf and 'gradle --no-daemon' in wf
assert 'fetch_wrapper.py' in (root / 'scripts/build_apk.sh').read_text()
assert 'command -v gradle' in (root / 'scripts/build_apk.sh').read_text()
print('PASS: RC4 dialer-only calls, non-exported notification listener and wrapper-independent CI.')

# v1.8 regression guards: private mode must not disable local Vosk listening.
assert (pkg / 'OfflineSpeechManager.kt').is_file()
activity = (pkg / 'MainActivity.kt').read_text()
wake = (pkg / 'DanaWakeService.kt').read_text()
assert 'offlineSpeech.listen(root)' in activity
assert 'if (memory.privateMode && !voskInstalled' in activity
assert 'ACTION_PRIVACY_CHANGED' in wake and 'ACTION_PRIVACY_CHANGED' in activity
assert 'if (stopped || UserMemory(this).privateMode) return' not in wake
assert 'modelLoadTimeout' in wake and 'last_stop_reason' in wake
assert 'onProgress' in (pkg / 'WakeModelInstaller.kt').read_text()
print('PASS: v1.8 local-only private speech, Vosk diagnostics and ZIP progress guards.')

# v1.9 privacy-safe user-initiated diagnostics and export.
assert (pkg / 'DiagnosticReport.kt').is_file()
assert (pkg / 'DiagnosticEvents.kt').is_file()
assert (pkg / 'DiagnosticEventRules.kt').is_file()
assert 'diagnosticsButton' in activity
assert 'Intent.ACTION_SEND' in activity and 'ClipData.newPlainText' in activity
assert 'UserMemory(context).privateMode' in (pkg / 'DiagnosticEvents.kt').read_text()
assert 'safeToken' in (pkg / 'DiagnosticEvents.kt').read_text()
assert 'WAKE_MODEL_LOADING' in wake and 'MODEL_IMPORT_FAILED' in activity
assert (root / 'DIAGNOSTICS_V1_9_FA.md').is_file()
print('PASS: v1.9 diagnostics UI, user-driven share, privacy filtering and event capture.')

# v1.9.1 wake turn-taking: never parse commands from a grammar-limited wake recognizer.
assert (pkg / 'WakeRecognitionGate.kt').is_file()
assert (pkg / 'WakeSessionMetrics.kt').is_file()
assert 'listenerFor("wake", token)' in wake and 'listenerFor("command_offline", token)' in wake
assert 'startCommandRecognitionOnDevice()' in wake and 'startCommandRecognitionOffline()' in wake
assert 'recognitionGeneration++' in wake and 'stage = "awaiting_command"' in wake
assert 'WakeSessionMetrics.report()' in (pkg / 'DiagnosticReport.kt').read_text()
assert 'دانا چراغ قوه رو روشن کن' not in wake.split('private fun startWakeRecognition()', 1)[1].split('private fun startCommandRecognition()', 1)[0]
print('PASS: v1.9.1 separate wake/command recognizers, stale callback guard and private session counters.')

# v1.9.2: buffered command recognition and explicitly opt-in, memory-only speech probe.
assert (pkg / 'WakeCommandCollector.kt').is_file()
assert (pkg / 'WakeSpeechProbe.kt').is_file()
assert 'finishCollectedCommand()' in wake and 'commandCollector.add(text)' in wake
assert 'WakeSpeechProbe.capture(phrase)' in wake
assert 'wakeProbeButton' in activity and 'WakeSpeechProbe.report()' in (pkg / 'DiagnosticReport.kt').read_text()
assert 'WakeSpeechProbe.toggle()' in activity
print('PASS: v1.9.2 buffered offline commands and opt-in transient transcription probe.')

# v1.9.3 Persian speech output: engine selection, manual test and diagnostic state.
voice_source = (pkg / 'DanaVoice.kt').read_text()
activity_source = (pkg / 'MainActivity.kt').read_text()
assert 'TextToSpeech(appContext, callback' in voice_source
assert 'fun chooseEngine(' in voice_source and 'fun refresh()' in voice_source
assert 'engine.setAudioAttributes(attributes)' in voice_source
assert 'PERSIAN_NOT_SUPPORTED' in voice_source and 'SYNTHESIS_ERROR_' in voice_source
assert 'testPersianSpeech()' in activity_source and 'openTtsSettings()' in activity_source
assert 'ACTION_TTS_CHANGED' in (pkg / 'DanaWakeService.kt').read_text()
assert 'voice.diagnostics()' in activity_source and 'append(ttsDetails)' in (pkg / 'DiagnosticReport.kt').read_text()
print('PASS: v1.9.3 user-driven Persian TTS engine selection, test, wake reload and diagnostics.')

# v1.9.4: parsed commands must reach visible UI or provide an actionable notification.
assert (pkg / 'WakeExecutionMetrics.kt').is_file()
assert 'MainActivity.dispatchWakeCommand(text)' in wake
assert 'WakeDispatchPolicy.requiresVisibleUi(action)' in wake
assert 'WakeExecutionMetrics.received(' in wake and 'WakeExecutionMetrics.outcome(' in wake
assert 'ACTION_UI_COMMAND_COMPLETE' in wake and 'ACTION_UI_COMMAND_COMPLETE' in activity_source
assert 'Notification.Builder(this, ACTION_CHANNEL)' in wake
assert 'USER_TAP_REQUIRED' in wake and 'APP_LAUNCH_REQUESTED' in wake
assert 'WakeExecutionMetrics.report()' in (pkg / 'DiagnosticReport.kt').read_text()
assert 'private var visibleActivity: WeakReference<MainActivity>?' in activity_source
print('PASS: v1.9.4 visible UI dispatch, actionable notifications, and execution diagnostics.')

# v1.9.5: safe Vosk speech variants, camera alias and honest torch outcome.
engine = (pkg / 'CommandEngine.kt').read_text()
assert 'Vosk can mistake' in engine
assert 'کنم|کنی|کنه' in engine
assert '"بیار", "روشن"' in engine
assert '"TORCH_SET_REQUESTED"' in wake and '"TORCH_SET_FAILED"' in wake
assert '"TORCH_SET_FAILED"' in (pkg / 'WakeExecutionMetrics.kt').read_text()
assert 'DANA 1.9.7 WhisperOfflineHybrid' in (main / 'res/layout/activity_main.xml').read_text()
print('PASS: v1.9.5 safe Persian command aliases, camera launch, torch diagnostics and version label.')

# v1.9.6: explicitly on-device Android recognition after Vosk wake, with local fallback.
assert (pkg / 'WakeAsrRoutingPolicy.kt').is_file() and (pkg / 'WakeAsrMetrics.kt').is_file()
assert 'requireOnDevice = true' in wake and 'allowOnline = false' in wake
assert 'startCommandRecognitionOnDevice()' in wake and 'fallbackToVosk(' in wake
assert 'onDeviceUnavailableForSession = true' in wake
assert 'WakeAsrMetrics.report()' in (pkg / 'DiagnosticReport.kt').read_text()
assert 'if (!allowOnline && !hasOnDeviceRecognizer())' in (pkg / 'SpeechManager.kt').read_text()
assert 'recognizer = if (!allowOnline)' in (pkg / 'SpeechManager.kt').read_text()
print('PASS: v1.9.6 strict on-device offline command path, Vosk fallback and safe diagnostics.')

# v1.9.7: real offline Whisper C++ bridge and explicit opt-in model import.
assert (pkg / 'WhisperNative.kt').is_file()
assert (pkg / 'WhisperModelInstaller.kt').is_file()
assert (pkg / 'WhisperCommandRecognizer.kt').is_file()
assert (root / 'app/src/main/cpp/whisper_jni.cpp').is_file()
assert (root / 'app/src/main/cpp/CMakeLists.txt').is_file()
assert (root / 'scripts/fetch_whisper_cpp.sh').is_file()
assert 'startCommandRecognitionWhisper(' in wake
assert 'WhisperModelInstaller.installed' in wake
assert 'WhisperMetrics.report()' in (pkg / 'DiagnosticReport.kt').read_text()
assert 'importWhisperModelButton' in (main / 'res/layout/activity_main.xml').read_text()
assert 'importWhisperModel()' in (pkg / 'MainActivity.kt').read_text()
assert 'externalNativeBuild' in app_gradle
for wf in ('android-debug.yml', 'android-release.yml'):
    assert 'fetch_whisper_cpp.sh' in (root / '.github/workflows' / wf).read_text()
print('PASS: v1.9.7 pinned Whisper JNI build, model import and offline command routing.')
