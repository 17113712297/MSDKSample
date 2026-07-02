## Local build note

This project must use an ASCII-only Android SDK path on Windows.

Use:

```text
X:\Sdk
```

Do not use a SDK path under a Chinese username directory such as:

```text
C:\Users\王纬天\AppData\Local\Android\Sdk
```

Reason:

- The `opencv` native CMake/NDK build can fail on Windows when the SDK/NDK path contains non-ASCII characters.
- The failure appears in `:opencv:configureCMakeDebug[arm64-v8a]`.
- A common error is:

```text
clang: error: unable to execute command: unspecified system_category error
```

Recommended local environment before building:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME='X:\Sdk'
$env:ANDROID_SDK_ROOT='X:\Sdk'
.\gradlew.bat assembleDebug --console=plain
```

Current local fix:

- Map `%LOCALAPPDATA%\Android` to `X:` with [setup-android-sdk-drive.bat](/D:/MSDK_merge/setup-android-sdk-drive.bat).
- `local.properties` must keep `sdk.dir=X\:\\Sdk`.
- Open Android Studio with [start-android-studio-fixed.bat](/D:/MSDK_merge/start-android-studio-fixed.bat).
- For command line builds, use [build-debug-fixed.bat](/D:/MSDK_merge/build-debug-fixed.bat).
