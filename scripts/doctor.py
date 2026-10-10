#!/usr/bin/env python3
"""Safe local prerequisites check. Does not install SDK or claim APK build success."""
from pathlib import Path
import os
import re
import shutil
import subprocess
import sys

root = Path(__file__).resolve().parents[1]
print('DANA Android 1.3 RC4 build and device prerequisites — environment diagnostics')
for name in ('java', 'javac', 'python3', 'gradle', 'adb', 'sdkmanager'):
    found = shutil.which(name)
    print(f'{name:12}: {found or "not in PATH"}')
if shutil.which('java'):
    print(subprocess.run(['java', '-version'], capture_output=True, text=True).stderr.splitlines()[0])
sdk_dirs = [os.getenv('ANDROID_SDK_ROOT'), os.getenv('ANDROID_HOME'),
            str(Path.home() / 'Android/Sdk'), str(Path.home() / 'Library/Android/sdk')]
sdk = next((Path(d) for d in sdk_dirs if d and Path(d).is_dir()), None)
if sdk:
    print('SDK:', sdk)
    print('Android 35:', (sdk / 'platforms/android-35/android.jar').is_file())
    print('Build Tools 35:', (sdk / 'build-tools/35.0.0').is_dir())
else:
    print('SDK: NOT FOUND (install through Android Studio SDK Manager)')
wrapper = root / 'gradle/wrapper/gradle-wrapper.jar'
print('Official wrapper cached:', wrapper.is_file())
print('Gradle build files:', all((root / p).is_file() for p in
   ('settings.gradle.kts', 'build.gradle.kts', 'app/build.gradle.kts')))
print('Next step: run gradlew.bat --version (Windows) or ./gradlew --version (Linux/macOS) with internet for initial setup.')
