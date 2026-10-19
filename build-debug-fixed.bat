@echo off
setlocal

set "SCRIPT_DIR=%~dp0"

rem Java: prefer Android Studio bundled JBR, fall back to local D:\Androidstudio\jbr
set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
if not exist "%JAVA_HOME%\bin\java.exe" set "JAVA_HOME=D:\Androidstudio\jbr"

rem Android SDK: use the real local path. X:\Sdk is the old subst-mapped path.
set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
if not exist "%ANDROID_HOME%\platform-tools\adb.exe" (
  set "ANDROID_HOME=X:\Sdk"
  call "%SCRIPT_DIR%setup-android-sdk-drive.bat"
  if errorlevel 1 exit /b 1
)
set "ANDROID_SDK_ROOT=%ANDROID_HOME%"

call "%SCRIPT_DIR%gradlew.bat" --stop
call "%SCRIPT_DIR%gradlew.bat" :app:assembleDebug --console=plain
