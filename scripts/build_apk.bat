@echo off
setlocal
cd /d %~dp0\..
python scripts\verify_static.py || exit /b 1
python -m unittest discover -s gateway\tests -v || exit /b 1
python scripts\preflight.py || exit /b 1
if exist gradle\wrapper\gradle-wrapper.jar (
  set GRADLE_CMD=gradlew.bat
) else (
  where gradle >nul 2>nul
  if %ERRORLEVEL%==0 (
    set GRADLE_CMD=gradle
  ) else (
    powershell -ExecutionPolicy Bypass -File scripts\fetch_wrapper.ps1 || exit /b 1
    set GRADLE_CMD=gradlew.bat
  )
)
%GRADLE_CMD% --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug || exit /b 1
echo APK: %CD%\app\build\outputs\apk\debug\app-debug.apk
