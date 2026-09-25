# Camera Scan

Native Android document scanning app built in Java/XML.

The app is **document-first**, not camera-first. Launching Camera Scan opens the document/project library. A document can exist empty; camera access is requested only after the user opens a document and chooses **Scan page**.

## Product flow

```text
Documents home
  └─ create/open document
       ├─ page gallery (default)
       ├─ compact page list
       ├─ import photos
       ├─ scan page
       ├─ extract/index text
       └─ export searchable PDF
            |
            v
       Page editor
       ├─ 8-handle curved boundary
       ├─ flatten
       ├─ cleanup filters
       ├─ optional dewarp
       └─ add page
```

## Documents and pages

Documents are persistent projects backed by Room.

Each document stores:

- name
- creation/update timestamps
- ordered pages
- page image path
- OCR text
- extracted structured OCR data
- OCR timestamp

Processed page images live in app-private persistent storage under the document. They are no longer an ephemeral scan session.

The home screen shows documents with their first page as the cover and page count. New documents start empty.

Inside a document:

- **Gallery** is the default view with larger page previews.
- **List** switches to a compact row layout with the thumbnail on the left.
- **Import** uses Android Photo Picker.
- **Scan page** opens CameraX only for that action.
- **Extract text** indexes all pages with bundled ML Kit OCR.
- **Export PDF** creates a searchable PDF and processed JPEG pages.

## 8-handle curved boundary flattening

The page editor no longer assumes every paper edge is a straight line.

It exposes eight draggable handles:

```text
TL ----- TM ----- TR
|                 |
LM                RM
|                 |
BL ----- BM ----- BR
```

- four corner handles: TL / TR / BR / BL
- four edge midpoint handles: TM / RM / BM / LM

Each edge is represented by a quadratic curve passing through its corner endpoints and the user's side midpoint.

The four curved edges define a curved quadrilateral. `CurvedBoundaryCorrector` maps that shape into a rectangle with a Coons-style surface:

```text
S(u,v) =
    (1-v) Top(u)
  + v Bottom(u)
  + (1-u) Left(v)
  + u Right(v)
  - bilinearCorners(u,v)
```

This lets the user straighten mildly bowed paper sides before the later page-dewarp tool is applied.

To keep memory bounded, OpenCV remap coordinates are produced in 128-row bands instead of allocating two full-page floating-point maps.

Output longest edge remains capped at 4096 px.

## Additional page correction

After flattening:

- Original
- Clean color
- Paper clean / illumination normalization
- Grayscale
- adaptive B&W
- conservative finger repair
- optional text-baseline page dewarp

The 8-handle boundary correction and the later dewarp solve different problems:

- **8-handle flatten** follows visibly curved outer paper edges.
- **Dewarp** estimates internal text-line curvature after the boundary has already been flattened.

## OCR and detected data

OCR is available at two levels.

### Page

Open a page to:

- view the processed scan
- run OCR
- inspect/edit the extracted text
- save edited text
- inspect detected:
  - dates
  - monetary amounts
  - email addresses
  - phone numbers

The structured values are lightweight deterministic extraction over the OCR text and are stored as JSON with the page.

### Document

The document screen can OCR/index every page sequentially.

It shows:

- indexed page count
- a text preview
- per-page "Text ready" state

Final searchable PDF export also performs OCR so a PDF can still be exported even when the user did not manually index first.

OCR uses the bundled ML Kit Latin recognizer, so the recognition model is packaged with the APK rather than downloaded after install.

## Camera pipeline

CameraX is opened only from **Scan page**.

The real-time path uses:

- shared CameraX ViewPort for Preview / Analysis / Capture
- Y/luminance plane only
- bounded working resolution
- Canny + morphology + contour quad detection
- fixed five-frame median quad consensus
- temporal stabilization
- blur/exposure quality checks
- quality-gated auto capture
- tap-to-focus / metering
- torch
- manual capture

Post-capture detection adds:

- second Canny threshold
- adaptive threshold
- probabilistic Hough fallback
- GrabCut foreground fallback
- manual 8-handle correction as the final reliability fallback

## Material UI

The application remains native **Java + XML Views**.

UI uses Material Components for Android:

- Material 3 theme
- dynamic color when supported
- MaterialToolbar
- MaterialCardView
- MaterialButton / toggle groups
- Material TextInput
- Material dialogs
- extended FABs
- Material Shared Axis activity transitions
- Material Fade Through content transitions

No Compose runtime is required.

## Persistence and privacy

- Room stores project/page metadata.
- Page files use app-private persistent storage.
- Temporary camera/import sources use cache.
- Import queues are scoped to a specific project.
- Photo Picker avoids broad storage permission.
- Camera hardware is optional at install time; import-only use still works.
- Camera permission is requested only when scanning.
- The manifest has no `INTERNET` permission.
- OCR, OpenCV processing, project storage, and PDF construction are local.

## Export

Project export produces:

- multi-page searchable PDF
- processed JPEG pages

On Android 10+ MediaStore publishes to:

```text
PDF:  Downloads/CameraScan/
JPEG: Pictures/CameraScan/
```

Sharing uses FileProvider content URIs.

## Toolchain

| Component | Version |
|---|---|
| Language/UI | Java 17 + XML Views |
| minSdk | 24 |
| compileSdk / targetSdk | 36 / 36 |
| Android Gradle Plugin | 9.4.0 |
| Gradle wrapper | 9.6.0 |
| Material Components | 1.14.0 |
| Room | 2.8.5 |
| RecyclerView | 1.4.0 |
| CameraX | 1.6.2 |
| OpenCV Android AAR | 4.14.0 |
| ML Kit bundled Latin OCR | 16.0.1 |

## Build

See [BUILD.md](./BUILD.md).

Quick build:

```bash
./gradlew :app:assembleDebug
```

Windows:

```powershell
.\gradlew.bat :app:assembleDebug
```

APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Source layout

```text
app/src/main/java/dev/nizav/documentscanner/
├── MainActivity.java                  # document library
├── ScannerApp.java
├── camera/
│   ├── AutoCaptureGate.java
│   ├── DocumentAnalyzer.java
│   ├── FrameQuality.java
│   └── FrameQualityEstimator.java
├── cv/
│   ├── Boundary8.java
│   ├── CurvedBoundaryCorrector.java
│   ├── DocumentDetector.java
│   ├── ImageEnhancer.java
│   ├── PageDewarper.java
│   ├── Quad.java
│   ├── QuadConsensus.java
│   └── QuadStabilizer.java
├── data/
│   ├── ImportQueueStore.java
│   ├── ProjectRepository.java
│   └── db/
│       ├── AppDatabase.java
│       ├── PageDao.java
│       ├── PageEntity.java
│       ├── ProjectDao.java
│       ├── ProjectEntity.java
│       └── ProjectRow.java
├── export/
│   ├── MediaStorePublisher.java
│   ├── ProjectExporter.java
│   └── ShareUtils.java
├── ocr/
│   ├── DocumentDataExtractor.java
│   ├── OcrEngine.java
│   ├── OcrLine.java
│   └── OcrPipeline.java
├── ui/
│   ├── CropActivity.java
│   ├── DocumentCropView.java
│   ├── DocumentOverlayView.java
│   ├── MaterialMotionActivity.java
│   ├── PageAdapter.java
│   ├── PageDetailActivity.java
│   ├── ProjectActivity.java
│   ├── ProjectAdapter.java
│   ├── ScanActivity.java
│   └── ThumbnailLoader.java
└── util/
    └── BitmapUtils.java
```

## Known limits

- Bundled OCR is Latin-focused.
- The 8-handle model corrects one midpoint bend per side; it is not arbitrary spline mesh editing.
- The optional dewarp is a conservative text-baseline heuristic, not learned 3D page reconstruction.
- OCR data extraction is regex/rule based, not a semantic invoice/receipt parser.
- A stored processed page currently does not retain its original source plus edit recipe for lossless later re-cropping.
- Classical edge detection can still need manual correction under severe glare, occlusion, or very low contrast.

See [docs/RESEARCH.md](./docs/RESEARCH.md) for implementation details and [docs/DEVICE_TESTING.md](./docs/DEVICE_TESTING.md) for the device validation matrix.
