# Local Build and Installation

This document is the reproducible setup for `camera-scan`.

## 1. Requirements

### Required

- Git
- JDK 17
- Android SDK Platform 36
- Android SDK Build Tools 36.0.0 or newer compatible 36.x release
- Android SDK Platform Tools if you want to use `adb`
- internet access for the first Gradle/dependency download

You **do not** need:

- a global Gradle install
- an OpenCV SDK checked into the repo
- an ML Kit model downloaded manually
- Firebase
- `google-services.json`
- an API key
- NDK/CMake for this project

The committed Gradle wrapper downloads Gradle 9.6.0.

AGP 9.4.0 is configured with JDK 17 and compile/target SDK 36.

## 2. Clone

From the parent directory where you keep projects:

```bash
git clone https://github.com/Ni-zav/camera-scan.git
cd camera-scan
```

The repository is private, so authenticate with your normal GitHub credential, SSH key, or GitHub CLI as appropriate.

## 3. Install Android SDK components

### Android Studio

Open:

```text
Tools -> SDK Manager
```

Install:

- Android SDK Platform 36
- Android SDK Build-Tools 36.x
- Android SDK Platform-Tools

### sdkmanager alternative

If Android command-line tools are already installed:

```bash
sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0"
```

## 4. JDK

Verify:

```bash
java -version
```

Use JDK 17.

For Android Studio, set the Gradle JDK to JDK 17 if Studio does not select it automatically.

## 5. Android SDK path

Android Studio normally creates `local.properties` automatically.

If you build only from a terminal and Gradle cannot find the SDK, create:

```text
local.properties
```

Example on Windows:

```properties
sdk.dir=C\:\\Users\\YOUR_USER\\AppData\\Local\\Android\\Sdk
```

Example on Linux:

```properties
sdk.dir=/home/YOUR_USER/Android/Sdk
```

Example on macOS:

```properties
sdk.dir=/Users/YOUR_USER/Library/Android/sdk
```

Do not commit your machine-specific `local.properties`.

## 6. Verify the wrapper

The project pins the Gradle 9.6.0 distribution SHA-256 in:

```text
gradle/wrapper/gradle-wrapper.properties
```

Linux/macOS:

```bash
./gradlew --version
```

Windows PowerShell / CMD:

```powershell
.\gradlew.bat --version
```

Expected Gradle version:

```text
9.6.0
```

The first run downloads Gradle 9.6.0 and Maven dependencies.

## 7. Build debug APK

### Linux/macOS

```bash
./gradlew --no-daemon :app:assembleDebug
```

### Windows

```powershell
.\gradlew.bat --no-daemon :app:assembleDebug
```

Output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 8. Run lint

Linux/macOS:

```bash
./gradlew --no-daemon :app:lintDebug
```

Windows:

```powershell
.\gradlew.bat --no-daemon :app:lintDebug
```

Reports are under:

```text
app/build/reports/
```

## 9. Clean build

Linux/macOS:

```bash
./gradlew clean :app:assembleDebug
```

Windows:

```powershell
.\gradlew.bat clean :app:assembleDebug
```

Use this if cached generated files appear inconsistent after dependency/toolchain changes.

## 10. Android Studio

1. Start Android Studio.
2. Choose **Open**.
3. select the `camera-scan` directory, not the repository root.
4. Allow Gradle sync.
5. Confirm Gradle JDK is JDK 17.
6. Connect a physical Android device or create an emulator.
7. Choose the `app` run configuration.
8. Click Run.

A physical phone is strongly recommended for scanner testing because emulator camera input cannot faithfully reproduce focus, exposure, glare, motion blur, OEM YUV stride behavior, or paper/background contrast.

## 11. Install the debug APK manually

Enable Developer options and USB debugging on the device.

Check the connection:

```bash
adb devices
```

Install/update:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

If multiple devices are connected, use an explicit serial:

```bash
adb -s DEVICE_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
```

Package name:

```text
dev.nizav.documentscanner
```

## 12. Runtime permissions and network

The application requests only:

```text
android.permission.CAMERA
```

There is no `INTERNET` permission and no broad media/storage permission.

Gallery import uses Android's Photo Picker / document-provider fallback.

The bundled ML Kit Latin OCR model is packaged with the app, so OCR does not need a model download after install.

## 13. Where exports go

During an unfinished scan, session pages are stored under app cache and are not intended as permanent files.

Permanent export copies are written to app-scoped Documents storage.

On Android 10 / API 29 and newer, export also publishes:

```text
PDF:   Downloads/PaperScanner/
JPEG:  Pictures/PaperScanner/
```

The share action uses a read-only temporary FileProvider URI rather than exposing a raw filesystem path.

On Android versions below API 29, the MediaStore publishing helper intentionally does not attempt the Android-10 scoped-storage path; the app-scoped export remains available to the app/share flow.

## 14. Debugging useful commands

Clear the app:

```bash
adb shell pm clear dev.nizav.documentscanner
```

View app logs:

```bash
adb logcat | grep -i "documentscanner\|ScannerApp"
```

Windows PowerShell equivalent:

```powershell
adb logcat | Select-String -Pattern "documentscanner|ScannerApp"
```

Uninstall:

```bash
adb uninstall dev.nizav.documentscanner
```

## 15. Common build failures

### Android SDK location not found

Create/fix `local.properties` or set `ANDROID_HOME` / Android Studio's SDK location.

### SDK platform android-36 not installed

Install:

```bash
sdkmanager "platforms;android-36"
```

### Build Tools missing

Install:

```bash
sdkmanager "build-tools;36.0.0"
```

### Wrong Java version

Confirm the shell and Android Studio Gradle JDK are JDK 17.

### Wrapper download/checksum failure

Do not bypass the checksum casually. Confirm:

- `distributionUrl` still points to Gradle 9.6.0
- the checked-in checksum has not been edited
- your network/proxy is not replacing downloads

### OpenCV initialization failure

The app uses the Maven Android AAR and `OpenCVLoader.initLocal()`.

Try:

```bash
./gradlew clean :app:assembleDebug
```

then fully uninstall/reinstall the app. If a device ABI problem remains, capture `adb logcat`.

### OCR increases APK size

Expected. This project intentionally uses the bundled/offline Latin model so OCR works immediately without a runtime model download.

### Camera permission denied

Grant camera permission in Android Settings or clear app data and relaunch.

### Scanner works but edges look wrong on one phone

Do not tune the overlay by arbitrary screen offsets. Capture the device model, Android version, preview orientation, and a screenshot. Preview/analysis geometry is already aligned through a shared CameraX `ViewPort`; an OEM-specific camera issue should be diagnosed at the image/crop-transform layer.

## 16. Release build

A release variant exists and enables R8/resource shrinking:

```bash
./gradlew :app:assembleRelease
```

The repository does **not** contain a private release signing key.

For distributable release builds, configure a signing key locally or use Android Studio:

```text
Build -> Generate Signed App Bundle / APK
```

Never commit a keystore password or private signing key to this research repository.

## 17. Recommended local validation before changing algorithms

Run:

```bash
./gradlew :app:assembleDebug :app:lintDebug
```

Then install the APK and execute the device matrix in [docs/DEVICE_TESTING.md](./docs/DEVICE_TESTING.md).

Compilation can validate API use and lifecycle warnings; it cannot validate camera geometry or scan quality.
