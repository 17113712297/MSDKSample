@echo off
setlocal

set "SCRIPT_DIR=%~dp0"

rem Prefer the real local SDK path. X:\Sdk is the old subst-mapped path and
rem does not exist unless setup-android-sdk-drive.bat was run.
set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
if not exist "%ADB%" set "ADB=X:\Sdk\platform-tools\adb.exe"
if not exist "%ADB%" if defined ANDROID_HOME set "ADB=%ANDROID_HOME%\platform-tools\adb.exe"
set "APK=%SCRIPT_DIR%app\build\outputs\apk\debug\app-debug.apk"
set "PACKAGE_NAME=com.example.msdksample"
set "ACTIVITY_NAME=%PACKAGE_NAME%/.MainActivity"

if not exist "%ADB%" (
  echo [FAIL] adb not found: %ADB%
  exit /b 1
)

if not exist "%APK%" (
  echo [FAIL] APK not found: %APK%
  echo Run build-debug-fixed.bat first.
  exit /b 1
)

"%ADB%" devices
"%ADB%" shell am force-stop %PACKAGE_NAME% >nul 2>nul
"%ADB%" install -r -t "%APK%"
if errorlevel 1 exit /b 1

"%ADB%" shell am start -n %ACTIVITY_NAME%
