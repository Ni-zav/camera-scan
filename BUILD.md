# Local Build and Installation

## Requirements

- Git
- JDK 17
- Android SDK Platform 36
- Android SDK Build Tools 36.0.0 or compatible 36.x
- Android SDK Platform Tools for `adb`
- internet access for the first development build so Gradle/Maven dependencies can be downloaded

You do **not** need:

- a global Gradle install
- NDK/CMake
- a separately downloaded OpenCV SDK
- Firebase
- `google-services.json`
- an API key
- a separately downloaded OCR model

The repository includes the Gradle wrapper for Gradle 9.6.0.

## Clone

```bash
git clone https://github.com/Ni-zav/camera-scan.git
cd camera-scan
```

If the repository is private, authenticate with your normal GitHub credentials/SSH/GitHub CLI.

## Android SDK

Install in Android Studio's SDK Manager:

- Android SDK Platform 36
- Android SDK Build Tools 36.x
- Android SDK Platform Tools

Command-line alternative:

```bash
sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0"
```

## JDK

```bash
java -version
```

Use JDK 17.

Android Studio should also use JDK 17 for Gradle.

## local.properties

Android Studio normally creates it automatically.

If Gradle cannot locate your SDK, create `local.properties` in the cloned repository root.

Windows example:

```properties
sdk.dir=C\:\\Users\\YOUR_USER\\AppData\\Local\\Android\\Sdk
```

Linux:

```properties
sdk.dir=/home/YOUR_USER/Android/Sdk
```

macOS:

```properties
sdk.dir=/Users/YOUR_USER/Library/Android/sdk
```

Do not commit `local.properties`.

## Verify wrapper

Linux/macOS:

```bash
./gradlew --version
```

Windows:

```powershell
.\gradlew.bat --version
```

Expected Gradle:

```text
9.6.0
```

The Gradle distribution checksum is pinned in `gradle/wrapper/gradle-wrapper.properties`.

## Build debug APK

Linux/macOS:

```bash
./gradlew --no-daemon :app:assembleDebug
```

Windows:

```powershell
.\gradlew.bat --no-daemon :app:assembleDebug
```

Output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Lint

Linux/macOS:

```bash
./gradlew --no-daemon :app:lintDebug
```

Windows:

```powershell
.\gradlew.bat --no-daemon :app:lintDebug
```

For the same checks used by CI:

```bash
./gradlew --no-daemon :app:assembleDebug :app:lintDebug
```

## Android Studio

1. Open the cloned `camera-scan` folder.
2. Allow Gradle sync.
3. Confirm Gradle JDK = JDK 17.
4. Connect a device or create an emulator.
5. Run the `app` configuration.

A physical phone is strongly recommended for camera/CV validation.

## Install APK manually

```bash
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Package:

```text
dev.nizav.documentscanner
```

## First launch behavior

The app opens the **Documents** home screen.

It should **not**:

- launch the camera
- request Camera permission
- create a fake scan session

Create an empty document first.

From inside that document you can:

- import pages without Camera permission
- tap **Scan page** to open the camera

Camera permission is requested only at that point.

The manifest does not require camera hardware, so import/project/OCR use can still exist on devices without a camera.

## Persistent data

Room database:

```text
camera_scan.db
```

Processed project page files live in app-private persistent storage conceptually under:

```text
files/projects/<projectId>/pages/
```

Temporary camera captures and Photo Picker import queues use app cache.

Clearing app data/uninstalling removes the private project database and stored project pages.

## OCR

OCR uses the bundled ML Kit Latin text-recognition model.

It can run:

- on one page from Page Details
- across an entire document
- during searchable PDF export

No model download or network permission is needed after installation.

OCR data extraction also detects simple:

- dates
- amounts
- email addresses
- phone numbers

These values are stored in Room with the OCR text.

## Exports

Permanent export copies are also written into app-scoped Documents storage.

Android 10+ additionally publishes:

```text
PDF:  Downloads/CameraScan/
JPEG: Pictures/CameraScan/
```

PDF sharing uses FileProvider.

## Useful adb commands

Clear app data:

```bash
adb shell pm clear dev.nizav.documentscanner
```

Logs:

```bash
adb logcat | grep -i "documentscanner\|ScannerApp"
```

PowerShell:

```powershell
adb logcat | Select-String -Pattern "documentscanner|ScannerApp"
```

Uninstall:

```bash
adb uninstall dev.nizav.documentscanner
```

## Release build

```bash
./gradlew :app:assembleRelease
```

Release R8/resource shrinking is enabled.

The repository intentionally does not contain a private signing key. Configure signing locally or use Android Studio:

```text
Build -> Generate Signed App Bundle / APK
```

Never commit keystore passwords or private signing keys.

## Common problems

### SDK not found

Create/fix `local.properties` or configure the Android SDK location.

### android-36 missing

```bash
sdkmanager "platforms;android-36"
```

### Build Tools missing

```bash
sdkmanager "build-tools;36.0.0"
```

### Wrong Java

Use JDK 17 both in the shell and Android Studio's Gradle JDK setting.

### OCR makes the APK larger

Expected. The OCR model is bundled intentionally for immediate offline recognition.

### No camera permission at startup

Expected. This is the intended project-first UX.

### Camera permission denied

Import still works. For scanning, grant Camera permission and reopen **Scan page**.

### Boundary overlay differs from paper on one phone

Do not add arbitrary display offsets. Record the device model, Android version, orientation, screenshot, and camera logs. Preview/analysis/capture already share a CameraX ViewPort.

## Device testing

After building/installing, follow [docs/DEVICE_TESTING.md](./docs/DEVICE_TESTING.md), especially:

- project persistence
- gallery/list switching
- import-only flow
- camera lifecycle
- 8-handle curved boundary
- OCR persistence
- searchable PDF
