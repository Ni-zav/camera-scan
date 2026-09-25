# Document Scanner Architecture and Research Notes

Research/implementation baseline: 2026-09-24.

## Goal

Build a native Android document scanner with explicit ownership of:

- camera lifecycle
- coordinate systems
- document detection
- crop editing
- projective flattening
- enhancement
- curved-page correction
- multi-page state
- OCR
- PDF/JPEG export
- memory and latency behavior

The design favors bounded, measurable local processing over opaque server calls.

## High-level architecture

```text
MainActivity
├── CameraX Preview
├── ImageAnalysis
│   └── DocumentAnalyzer
│       ├── Y-plane copy
│       ├── visible crop
│       ├── orientation normalization
│       ├── DocumentDetector
│       ├── QuadConsensus
│       ├── QuadStabilizer
│       ├── FrameQualityEstimator
│       └── AutoCaptureGate
├── ImageCapture
└── Photo Picker import
          |
          v
CropActivity
├── accurate DocumentDetector
├── manual DocumentCropView
├── PerspectiveCorrector
├── PageDewarper (optional)
└── ImageEnhancer
          |
          v
ScanSessionStore
├── reorder
├── delete
└── new session
          |
          v
SessionExporter
├── OcrEngine (bundled ML Kit Latin)
├── searchable PdfDocument
├── MediaStorePublisher
└── ShareUtils / FileProvider
```

## Why the live and final paths differ

A common scanner-performance mistake is running final-quality CV on every camera frame.

The live path only needs a fast, stable proposal. The captured-image path needs the best practical geometry.

Therefore:

### Live

- Y plane only
- CameraX visible crop only
- ~12.5 analysis attempts/sec maximum
- working longest edge <= 900 px
- one primary Canny pass
- no Hough fallback
- no GrabCut
- no OCR
- no filter/dewarp
- fixed-size recent-quad consensus

### Post capture

- bounded RGB bitmap
- working detection longest edge <= 1600 px
- multiple edge/threshold passes
- Hough fallback when contours are weak
- GrabCut fallback only if earlier geometry remains poor
- manual crop is always available
- full perspective correction once
- optional page restoration once
- OCR at final export

The expensive work therefore scales with user actions, not camera FPS.

## Camera lifecycle and coordinate correctness

Preview, ImageAnalysis, and ImageCapture are bound together using one CameraX `UseCaseGroup` with `PreviewView.getViewPort()`.

This matters because camera use cases may otherwise receive different sensor crops/resolutions. A raw "analysis pixels -> screen pixels" scale can drift even when the math looks correct.

The analyzer:

1. copies plane 0 / luma,
2. applies `ImageProxy.getCropRect()`,
3. rotates the cropped view according to CameraX metadata,
4. normalizes the final quad to that visible region.

The overlay then uses normalized coordinates directly across the visible PreviewView.

Every `ImageProxy` is closed in `finally`.

## Live frame data path

### Y-plane copy

Only luma is copied from `YUV_420_888`.

The implementation handles row stride and pixel stride instead of assuming tightly packed pixels.

Reusable byte arrays and Mats prevent large per-frame Java/native allocation churn.

Complexity: `O(N)` for source luma pixels.

### Visible crop

The OpenCV crop is a submatrix/header over the copied luma Mat.

Additional pixel-copy complexity: effectively `O(1)` for the submatrix itself.

### Downscale

The longest detector edge is bounded to 900 px.

Document boundaries are low-frequency geometry, so this removes detail that costs CPU but usually does not improve live boundary placement.

Complexity: `O(N)`.

## Core document detector

### Preprocessing

- 5x5 Gaussian blur
- Canny
- morphological close

Fixed kernels make these linear in working pixels for practical complexity: `O(N)`.

### Contours

`findContours(..., CHAIN_APPROX_SIMPLE)` extracts candidate boundaries while compressing straight segments.

Large-enough contours are approximated with `approxPolyDP` at a small fixed set of epsilon ratios.

Candidates must:

- approximate to exactly four points
- be convex
- cover a minimum area
- have minimum side length

The detector does not globally sort contours. It retains the best candidate while walking them.

The geometric score is:

```text
0.58 * visible area
+ 0.27 * right-angle quality
+ 0.15 * contour/quad rectangularity
```

This biases toward the large rectangular object expected in document scanning.

## Multi-frame consensus

Raw edge detection jitters by a few pixels because exposure, focus, hand motion, and sensor noise change each frame.

The analyzer maintains a fixed five-quad history and takes the median of each of the eight normalized coordinates.

Because the window size is fixed, the practical cost is `O(1)` per analyzed frame.

A subsequent exponential stabilizer:

- smooths residual motion
- requires repeated low-motion frames before declaring the page stable
- removes the overlay after consecutive detection misses

This separates "a rectangle was detected" from "the phone/page is stable enough to capture."

## Capture quality gate

Auto capture also checks:

- mean luminance
- Laplacian variance as a sharpness proxy
- document detection score
- geometric stability
- several consecutive acceptable frames
- capture cooldown

This prevents one transient good-looking frame from firing the shutter immediately.

The quality check runs on a further reduced image with longest edge <= 480 px.

## Accurate post-capture recovery

The final detector evaluates, in order:

1. Canny 50/150
2. Canny 30/100
3. adaptive Gaussian threshold
4. probabilistic Hough-line fallback when the best result is weak
5. GrabCut foreground fallback if geometry still remains weak

### Hough fallback

The fallback extracts long line segments, bounds the candidate set, groups approximately parallel/perpendicular line families, selects extreme opposing lines, intersects them, and scores the resulting quad.

It is post-capture only because line transforms cost more than the normal contour path.

### GrabCut fallback

GrabCut is used as a classical foreground segmentation proposal with an inset foreground rectangle.

The resulting probable/definite foreground mask is cleaned and fed back into the same quad contour scoring.

It is intentionally penalized slightly so a solid geometric edge/Hough result wins whenever both are plausible.

### Why there is no custom trained document model

No custom TFLite/ONNX document-segmentation model is committed.

That is intentional:

- a model needs a known training/evaluation provenance
- its license must permit redistribution
- accuracy needs to be measured on our target device/document distribution
- a random binary model would make the "native research" less auditable, not more

The deterministic fallbacks make the entire scanner buildable from source today.

A trained segmentation model can later be added behind the same normalized-quad interface and benchmarked against this baseline.

## Manual crop is the reliability boundary

Automatic detection never blocks a scan.

If all automatic methods fail, the captured image opens with a safe inset rectangle. The user can drag all four corners.

Normalized crop coordinates are independent from:

- screen density
- preview size
- device resolution
- decoded bitmap size

This is important for correctness and UI simplicity.

## Perspective flattening

A flat page photographed under perspective is modeled by a homography.

The selected corners:

```text
top-left
top-right
bottom-right
bottom-left
```

are mapped to a rectangle using `getPerspectiveTransform`.

Destination dimensions come from opposing edge lengths.

`warpPerspective` produces the flattened page with the longest output edge capped at 4096 px.

Complexity is proportional to destination pixels: `O(M)`.

## Page enhancement

### Original

Direct copy of the flattened page.

### Clean color

- RGB -> Lab
- CLAHE on lightness
- convert back
- mild unsharp mask

### Paper clean

Designed for uneven illumination/shadows:

- RGB -> Lab
- estimate smooth low-frequency illumination using large Gaussian blur
- normalize lightness against the estimated background
- mild CLAHE
- pull weak chroma moderately toward neutral paper

It is deterministic illumination correction, **not** a learned stain-restoration model.

### Grayscale

Direct luminance conversion.

### Black & white

- grayscale
- small blur
- adaptive Gaussian threshold

Adaptive thresholding tolerates nonuniform illumination better than one global threshold.

### Finger repair

This is intentionally conservative and user-invoked.

It:

- converts to YCrCb
- finds a broad plausible skin-color region
- restricts the mask to an outer page band
- rejects masks that are too tiny or too large
- morphologically closes/dilates the accepted mask
- runs Telea inpainting

This can still misclassify skin-colored printed content near an edge, which is why it is never automatic.

Enhancement filters are linear in output pixels for their fixed/local operations in practical use: approximately `O(M)`.

## Curved-page dewarp

Perspective correction cannot flatten a genuinely curved book/page surface.

The optional dewarp performs a conservative baseline-based correction:

1. downscale analysis to <= 720 px longest edge
2. grayscale and Otsu threshold
3. horizontal morphology to emphasize text baselines
4. split the page into 24 vertical bins
5. compute row projections per bin
6. cross-correlate each projection with the center reference
7. smooth the estimated vertical displacement
8. reject low-confidence / tiny deformation
9. render 72 shifted vertical strips at final size

The analysis dimensions and bin count are bounded constants.

Rendering remains approximately linear in output pixels.

Why strips instead of one full-resolution `remap`? A conventional full-resolution remap needs large X/Y float maps. The strip renderer avoids two additional image-sized float buffers while still correcting moderate vertical page bowing.

This is not a full 3D reconstruction and should remain optional.

## Import path

Multiple gallery images are selected with Android Photo Picker and copied sequentially into a bounded cache queue.

Benefits:

- no broad storage permission
- the crop activity receives ordinary local Files
- no long-lived dependency on a provider URI permission
- only one imported page is edited at a time

I/O complexity is `O(B)` for imported bytes.

## Session lifecycle

Processed pages are stored in the app cache using ordered filenames.

The session supports:

- append page
- list
- reorder
- delete
- clear/new session

Reordering is implemented with temporary filenames so collisions cannot overwrite another page.

Only processed page images are retained. Original captures/imports are disposable queue/cache sources.

## OCR

The project uses:

```text
com.google.mlkit:text-recognition:16.0.1
```

the bundled Latin text recognizer.

The model is packaged with the APK and works without a runtime model download.

OCR is performed:

- one page at a time
- only during final export
- off the UI thread

OCR is not placed in the camera analyzer.

The neural-network internals are library/model implementation details, so its exact algorithmic complexity is not claimed here.

## Searchable PDF construction

For each page:

1. decode a bounded export bitmap
2. run OCR
3. create portrait/landscape A4 PdfDocument page
4. map recognized lines into PDF coordinates
5. draw OCR text operations
6. draw the raster scan over the text
7. finish the page

The raster completely covers the text visually, preserving the scanned appearance while PDF search/text extraction can still find the hidden text operators.

If OCR fails on a page, export continues without text for that page.

## Export and sharing

Permanent export copies are written into app-scoped Documents storage.

Android 10+ also uses MediaStore:

- PDFs in Downloads/PaperScanner
- JPEGs in Pictures/PaperScanner
- `IS_PENDING` is used while bytes are being written

Sharing uses FileProvider content URIs with temporary read permission.

No raw `file://` path is exposed to another app.

## Threading

### Camera

One dedicated executor owns ImageAnalysis ordering.

The main thread only receives compact geometry/quality results and draws the overlay.

### Crop editor

One serial worker owns:

- decode
- accurate detect
- warp
- dewarp
- filters
- add-page encode

A monotonically increasing generation ID rejects stale filter/dewarp results.

### Session export

One serial worker owns OCR/PDF/export.

This avoids multiple page-sized transforms/OCR jobs fighting for heap and native memory at once.

## Memory strategy

Live:

- reusable Y byte buffer
- reusable stride scratch buffer
- reusable OpenCV Mats
- zero-copy crop submatrix
- bounded CV resolution
- no RGB Android Bitmap per analysis frame

Post capture:

- source decode <= 3072 px longest edge
- detector working image <= 1600 px
- perspective output <= 4096 px
- dewarp analysis <= 720 px
- export decode <= 2200 px
- pages processed sequentially
- filters processed sequentially

The design chooses predictable peaks instead of maximum raw camera resolution.

## Complexity summary

Let:

- `N` = live working pixels
- `P` = retained contour boundary points
- `M` = processed/export page pixels
- `B` = imported/exported bytes

| Stage | Practical complexity | Frequency |
|---|---:|---|
| Y-plane copy | O(N) | <= ~12.5 Hz |
| crop submatrix | O(1) header | <= ~12.5 Hz |
| rotate/downscale | O(N) | <= ~12.5 Hz |
| blur/Canny/morphology | O(N) | <= ~12.5 Hz |
| contour extraction | O(N + P) | <= ~12.5 Hz |
| quad scoring | O(P), no global sort | <= ~12.5 Hz |
| 5-quad consensus | O(1), fixed window | <= ~12.5 Hz |
| quality estimate | O(N) on <=480px image | <= ~12.5 Hz |
| overlay | O(1), 4 edges + handles | result updates |
| accurate multipass detection | several O(N + P) passes | once/source |
| Hough fallback | higher/data-dependent, bounded use | difficult source only |
| GrabCut fallback | iterative, expensive | difficult source only |
| perspective warp | O(M) | once/crop |
| page enhancement | ~O(M) | user action |
| dewarp render | ~O(M), fixed strips | user action |
| file import/copy | O(B) | imported sources |
| OCR | model-dependent | once/page/export |
| raster PDF/JPEG | O(M) | once/page/export |

## UI efficiency

The camera view hierarchy is intentionally small:

- PreviewView
- one custom quad overlay
- status text
- torch
- auto toggle
- import
- shutter
- pages button

The crop surface draws:

- one bitmap
- one four-edge path
- four handles

No RecyclerView, Compose runtime, animation loop, or idle render loop is used for the scanner hot path.

## Privacy/security properties

- no INTERNET permission
- no broad external-storage permission
- Photo Picker/provider input
- cache for temporary working data
- MediaStore for public exports on API 29+
- FileProvider for sharing
- no embedded secret/API key
- no private signing key in the repository

## Known failure modes

Expect classical geometry to be challenged by:

- white page on nearly identical white background
- severe glare
- fully covered corners
- strong rectangular objects larger than the paper
- page occupying too little of the image
- extreme motion blur
- folds/curls with very little printed horizontal structure
- illustrations/photos where dewarp baseline correlation has little signal

The manual crop/editor remains the final fallback.

## Recommended future experiments

These should be measured against the current baseline rather than added blindly:

- retain original sources for post-session recrop
- stronger page-session thumbnail UI
- language-selectable bundled OCR models
- custom document segmentation model with a licensed/reproducible training pipeline
- learned shadow/finger cleanup with objective before/after test set
- calibration profiles for problematic OEM cameras
- Macrobenchmark / Perfetto device benchmark suite
- page sharpness selection across a short pre-capture frame ring buffer

See [DEVICE_TESTING.md](./DEVICE_TESTING.md) for the current validation matrix.
