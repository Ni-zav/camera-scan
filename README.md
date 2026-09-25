# Native Android Document Scanner

A standalone, local-first Android document scanner research app implementing the core workflow of products such as CamScanner while keeping the camera, geometry, image processing, lifecycle, and export pipeline under our control.

This repository is the scanner project and can be opened directly in Android Studio.

## Current feature set

### Capture

- CameraX rear-camera preview
- shared CameraX `ViewPort` for Preview, ImageAnalysis, and ImageCapture
- tap-to-focus and exposure metering
- torch
- live document boundary overlay
- multi-frame median quad consensus plus temporal smoothing
- live blur/exposure quality checks
- quality-gated auto capture with cooldown
- manual shutter at any time

### Document detection

The fast live path uses:

- the Y/luminance plane only
- CameraX's actual visible crop
- downsampling
- Gaussian blur
- Canny edge detection
- morphological closing
- contours
- polygon approximation
- convex four-corner validation
- geometric scoring

The more expensive post-capture path additionally tries:

- a second Canny threshold
- adaptive thresholding
- probabilistic Hough line reconstruction
- GrabCut foreground segmentation fallback

Automatic detection is always only a proposal. If it is wrong or no paper boundary can be found, the user can move all four corners manually.

### Editing

- draggable four-corner crop
- perspective flattening / homography
- Original
- Clean color
- Paper clean / illumination normalization
- Grayscale
- adaptive black and white
- conservative edge-finger repair using a skin-color mask plus Telea inpainting
- optional curved-page dewarp based on text-baseline displacement

The dewarp and finger-repair tools are user-invoked because applying them blindly to every document can damage valid content.

### Multi-page workflow

- capture multiple pages
- import up to 20 images at once using Android Photo Picker
- sequential crop/edit flow for imported images
- reorder pages
- delete pages
- start a new session
- session data stored in app cache while editing

### OCR and export

- bundled ML Kit Latin text recognition
- no runtime OCR-model download
- searchable multi-page PDF
- processed JPEG page export
- Android 10+ publishing through MediaStore:
  - PDF -> Downloads/PaperScanner
  - JPEG pages -> Pictures/PaperScanner
- secure PDF sharing using FileProvider
- OCR failure on one page does not prevent raster PDF export

## Local-first behavior

The application manifest has no `INTERNET` permission.

Camera processing, OpenCV processing, the bundled OCR model, PDF construction, and session management are local. The **first development build** still needs internet access on your computer so Gradle can download the Gradle distribution and Maven dependencies.

No API key and no `google-services.json` are required.

## Toolchain

| Component | Version |
|---|---|
| Language | Java 17 |
| minSdk | 24 |
| compileSdk | 36 |
| targetSdk | 36 |
| Android Gradle Plugin | 9.4.0 |
| Gradle wrapper | 9.6.0 |
| CameraX | 1.6.2 |
| OpenCV Android AAR | 4.14.0 |
| ML Kit bundled Latin OCR | 16.0.1 |

The Gradle 9.6.0 binary distribution is SHA-256 pinned in `gradle-wrapper.properties`. CI also verifies the generated wrapper JAR checksum before building.

## Build

See **[BUILD.md](./BUILD.md)** for exact Windows/macOS/Linux setup, Android Studio steps, command-line builds, APK installation, release notes, and troubleshooting.

Quick debug build:

```bash
./gradlew :app:assembleDebug
```

Windows PowerShell:

```powershell
.\gradlew.bat :app:assembleDebug
```

APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Architecture and performance

See **[docs/RESEARCH.md](./docs/RESEARCH.md)**.

The core design is intentionally split into two paths:

```text
REAL TIME
CameraX Y plane
  -> visible crop
  -> bounded analysis resolution
  -> document detector
  -> 5-frame quad consensus
  -> temporal stabilizer
  -> quality gate
  -> lightweight overlay

POST CAPTURE
JPEG / imported image
  -> EXIF normalize + bounded decode
  -> accurate multi-pass detector
  -> manual crop
  -> perspective flatten
  -> optional dewarp
  -> optional enhancement / repair
  -> session page
  -> bundled OCR
  -> searchable PDF + JPEG export
```

Heavy fallbacks and OCR never run in the continuous camera hot path.

## Device validation

Camera behavior cannot be validated from compilation alone. Test on real devices with the matrix in **[docs/DEVICE_TESTING.md](./docs/DEVICE_TESTING.md)**.

CI currently performs:

```text
:app:assembleDebug
:app:lintDebug
```

on every scanner-project change.

## Important limits

This is a strong native scanner baseline, not a claim of complete commercial CamScanner parity.

- OCR is the bundled **Latin** ML Kit recognizer.
- There is **no custom trained document-segmentation model** in the repository. Difficult-page recovery uses deterministic contour, Hough, adaptive-threshold, and GrabCut fallbacks so the build stays reproducible and does not depend on an unreviewed binary model.
- Shadow/stain cleanup is deterministic illumination normalization, not a learned restoration model.
- Finger repair is deliberately conservative and limited to plausible skin-colored regions near page edges.
- Dewarp estimates vertical page bend from text-baseline correlation; it is not a learned 3D page reconstruction system.
- Added session pages store the processed page, not the original source, so a page cannot currently be re-cropped after it has been added. Delete it and scan/import it again if needed.
- OCR text is placed underneath the raster page in the PDF. Search/text extraction is supported, but the visible PDF remains the exact scanned raster rather than reconstructed typeset text.

These limits are explicit so future improvements can be measured instead of hidden behind broad "AI cleanup" claims.

## Source layout

```text
app/src/main/java/dev/nizav/documentscanner/
├── MainActivity.java
├── ScannerApp.java
├── camera/
│   ├── AutoCaptureGate.java
│   ├── DocumentAnalyzer.java
│   ├── FrameQuality.java
│   └── FrameQualityEstimator.java
├── cv/
│   ├── DocumentDetector.java
│   ├── ImageEnhancer.java
│   ├── PageDewarper.java
│   ├── PerspectiveCorrector.java
│   ├── Quad.java
│   ├── QuadConsensus.java
│   └── QuadStabilizer.java
├── data/
│   ├── ImportQueueStore.java
│   └── ScanSessionStore.java
├── export/
│   ├── MediaStorePublisher.java
│   ├── SessionExporter.java
│   └── ShareUtils.java
├── ocr/
│   ├── OcrEngine.java
│   └── OcrLine.java
├── ui/
│   ├── CropActivity.java
│   ├── DocumentCropView.java
│   ├── DocumentOverlayView.java
│   └── SessionActivity.java
└── util/
    └── BitmapUtils.java
```
