@echo off
setlocal
set "APP_HOME=%~dp0"
if not exist "%APP_HOME%gradle\wrapper\gradle-wrapper.jar" (
 powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%APP_HOME%scripts\fetch_wrapper.ps1"
 if errorlevel 1 exit /b 2
)
if not "%JAVA_HOME%"=="" (
 set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
) else (
 set "JAVA_EXE=java.exe"
)
"%JAVA_EXE%" -Xmx64m -Xms64m -Dorg.gradle.appname=gradlew -classpath "%APP_HOME%gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
exit /b %ERRORLEVEL%
