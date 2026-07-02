@echo off
setlocal

set "SCRIPT_DIR=%~dp0"
set "STUDIO_EXE=C:\Program Files\Android\Android Studio\bin\studio64.exe"
set "STUDIO_JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"

call "%SCRIPT_DIR%setup-android-sdk-drive.bat"
if errorlevel 1 exit /b 1

if not exist "%STUDIO_EXE%" (
  echo [FAIL] Android Studio not found: %STUDIO_EXE%
  exit /b 1
)

set "JAVA_HOME=%STUDIO_JAVA_HOME%"
set "ANDROID_HOME=X:\Sdk"
set "ANDROID_SDK_ROOT=X:\Sdk"

call "%SCRIPT_DIR%gradlew.bat" --stop >nul 2>nul

echo [OK] Launching Android Studio with:
echo      JAVA_HOME=%JAVA_HOME%
echo      ANDROID_HOME=%ANDROID_HOME%
echo      ANDROID_SDK_ROOT=%ANDROID_SDK_ROOT%
start "" "%STUDIO_EXE%"
