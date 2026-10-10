@echo off
setlocal
cd /d %~dp0\..
for %%V in (DANA_KEYSTORE_PATH DANA_KEYSTORE_PASSWORD DANA_KEY_ALIAS DANA_KEY_PASSWORD) do (
  if not defined %%V (echo Missing required environment variable: %%V & exit /b 2)
)
if not exist "%DANA_KEYSTORE_PATH%" (echo Keystore not found: %DANA_KEYSTORE_PATH% & exit /b 2)
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
%GRADLE_CMD% --no-daemon :app:testDebugUnitTest :app:lintRelease :app:assembleRelease || exit /b 1
if not exist app\build\outputs\apk\release\app-release.apk (echo Release APK was not produced & exit /b 3)
certutil -hashfile app\build\outputs\apk\release\app-release.apk SHA256
