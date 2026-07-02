@echo off
setlocal

set "SCRIPT_DIR=%~dp0"
set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
set "ANDROID_HOME=X:\Sdk"
set "ANDROID_SDK_ROOT=X:\Sdk"

call "%SCRIPT_DIR%setup-android-sdk-drive.bat"
if errorlevel 1 exit /b 1

call "%SCRIPT_DIR%gradlew.bat" --stop
call "%SCRIPT_DIR%gradlew.bat" :app:assembleDebug --console=plain
