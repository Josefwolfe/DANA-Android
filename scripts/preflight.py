#!/usr/bin/env python3
"""Strict prerequisites check for building DANA outside ChatGPT."""
from pathlib import Path
import os, re, shutil, subprocess, sys

root = Path(__file__).resolve().parents[1]
errors=[]

def tool(name):
    p=shutil.which(name)
    print(f"{name:12}: {p or 'NOT FOUND'}")
    return p

print("DANA Android 1.9.5 — build preflight")
java=tool('java'); tool('python3'); tool('adb'); tool('sdkmanager')
if not java:
    errors.append('JDK is not installed or java is not in PATH.')
else:
    out=subprocess.run([java,'-version'],capture_output=True,text=True)
    line=(out.stderr or out.stdout).splitlines()[0] if (out.stderr or out.stdout) else ''
    print('java version :', line)
    m=re.search(r'version "(?:1\.)?(\d+)', line)
    if not m or int(m.group(1)) < 17:
        errors.append('JDK 17 or newer is required.')

candidates=[os.getenv('ANDROID_SDK_ROOT'),os.getenv('ANDROID_HOME'),str(Path.home()/'Android/Sdk'),str(Path.home()/'Library/Android/sdk')]
sdk=next((Path(x) for x in candidates if x and Path(x).is_dir()),None)
print('Android SDK :', sdk or 'NOT FOUND')
if not sdk:
    errors.append('Android SDK is not installed. Install API 35 and Build Tools 35.0.0 from Android Studio SDK Manager.')
else:
    if not (sdk/'platforms/android-35/android.jar').is_file(): errors.append('Android platform 35 is missing.')
    if not (sdk/'build-tools/35.0.0').is_dir(): errors.append('Android Build Tools 35.0.0 is missing.')

for f in ('settings.gradle.kts','build.gradle.kts','app/build.gradle.kts','gradle/wrapper/gradle-wrapper.properties'):
    if not (root/f).is_file(): errors.append(f'Missing build file: {f}')

if errors:
    print('\nPRECHECK: FAILED')
    for e in errors: print(' -',e)
    sys.exit(2)
print('\nPRECHECK: PASS')
