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
assert 'versionName = "1.3.0-rc4"' in app_gradle and 'versionCode = 13' in app_gradle
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
assert 'versionName = "1.3.0-rc4"' in app_gradle and 'versionCode = 13' in app_gradle
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
