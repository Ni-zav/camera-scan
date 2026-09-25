# Architecture and Research Notes

Implementation baseline: 2026-09-25.

## Product architecture

Camera Scan is now document-first.

```text
MainActivity
  Documents / Projects
        |
        v
ProjectActivity
  persistent pages
  gallery/list modes
  OCR index + export
   |          |
   |          +--> PageDetailActivity
   |                 OCR/edit/data
   |
   +--> Import -> CropActivity
   |
   +--> ScanActivity -> CropActivity
                         |
                         +--> 8-handle curved boundary
                         +--> flatten
                         +--> filters
                         +--> optional dewarp
                         +--> ProjectRepository.addPage()
```

The camera stack does not exist on normal home/project browsing.

## Persistence

Room 2.8.5 stores metadata.

### ProjectEntity

- id
- name
- createdAt
- updatedAt

### PageEntity

- id
- projectId
- position
- filePath
- ocrText
- ocrDataJson
- ocrUpdatedAt
- createdAt
- updatedAt

Pages use a foreign key with cascade deletion.

Image pixels are not placed in SQLite. Processed JPEGs live in private files storage while Room stores paths and metadata. This avoids large database blobs and keeps page decoding stream/file based.

Project list rows query page count and first-page cover path in SQL.

## Material UI architecture

The app stays Java/XML Views and uses Material Components 1.14.0.

Material is used for:

- Material 3 Day/Night theme
- Android dynamic colors where available
- toolbars
- cards
- buttons and segmented view toggle
- text fields
- dialogs
- extended FABs
- shared-axis activity transitions
- fade-through state transitions

RecyclerView handles project/page virtualization.

Thumbnail images are:

- sampled down to a bounded size
- decoded as RGB_565
- loaded on a two-thread executor
- cached in a bounded LruCache

This prevents full page bitmaps from being loaded just to render lists.

## Project page views

Gallery mode is default and uses two columns.

List mode uses a single column with a small thumbnail on the left.

Switching layouts does not duplicate page state; both layouts render the same ordered PageEntity list.

## Camera lifecycle

CameraX exists only in ScanActivity.

Preview, ImageAnalysis, and ImageCapture share one CameraX ViewPort.

The analyzer:

1. copies only the Y plane
2. applies ImageProxy crop rect
3. rotates to display orientation
4. runs bounded document detection
5. applies recent-frame consensus/stabilization
6. computes quality
7. optionally signals auto-capture

Every ImageProxy closes in `finally`.

## Live scanner performance

Live path intentionally avoids final-quality operations.

- Y plane only
- analysis throttled to roughly 12.5 Hz
- detector longest edge <= 900 px
- quality image <= 480 px
- one analyzer executor
- KEEP_ONLY_LATEST backpressure
- reused byte arrays/Mats
- fixed five-quad median window
- constant-complexity overlay geometry

OCR, Hough fallback, GrabCut, enhancement, curved remapping, and PDF construction are not in the continuous live path.

## Automatic boundary detection

The normal detector uses:

- Gaussian blur
- Canny
- morphology
- contours
- CHAIN_APPROX_SIMPLE
- approxPolyDP
- convex four-corner validation
- area/right-angle/rectangularity score

Post-capture additionally evaluates:

- second Canny thresholds
- adaptive thresholding
- probabilistic Hough line reconstruction
- GrabCut foreground segmentation fallback

Automatic detection seeds the editor. It is not authoritative.

## 8-handle curved boundary model

A projective homography assumes all four page sides are straight.

That is insufficient when a sheet is slightly bowed/curling on a table.

The editor now models eight points:

```text
P0 TL
P1 top midpoint
P2 TR
P3 right midpoint
P4 BR
P5 bottom midpoint
P6 BL
P7 left midpoint
```

Each side is a quadratic Bézier.

For start point `A`, visible midpoint `M`, endpoint `B`, the quadratic control point is derived so the curve passes through M at t=0.5:

```text
C = 2M - 0.5(A + B)
```

Then:

```text
Q(t) = (1-t)^2 A + 2(1-t)t C + t^2 B
```

This means the side handle is intuitive: it lies on the paper boundary itself, not at the abstract Bézier control location.

### Curved quadrilateral -> rectangle

For normalized destination coordinates u/v, CurvedBoundaryCorrector evaluates:

```text
S(u,v) =
    (1-v) Top(u)
  + v Bottom(u)
  + (1-u) Left(v)
  + u Right(v)
  - B(u,v)
```

where `B(u,v)` is bilinear interpolation of the four corners.

This is a transfinite/Coons-style interpolation. It honors all four curved page boundaries while smoothly interpolating the interior.

The mapping is supplied to OpenCV `remap`.

### Memory optimization

A full-resolution remap normally requires two output-sized float maps.

Instead this implementation generates maps in bands of 128 destination rows.

For destination width W and band height H:

```text
map memory ≈ 2 * W * H * sizeof(float)
```

instead of:

```text
2 * W * fullPageHeight * sizeof(float)
```

The output longest edge is capped at 4096 px.

Runtime remains approximately O(output pixels).

## Why PageDewarper still exists

Boundary curvature and internal page curvature are related but different.

The 8-handle editor uses user-visible outer edges.

PageDewarper runs after flattening and looks for vertical displacement of horizontal text structure across the page. It can compensate for moderate book/page bow even when outer boundaries alone do not describe the internal surface.

It stays optional because image/photo-heavy pages may not provide reliable text-line structure.

## Crop/edit threading

CropActivity owns a serial worker for:

- decode
- accurate detection
- curved flattening
- filters
- dewarp
- final page encoding

A render generation counter discards stale filter/dewarp results.

The UI thread handles only view state and drawing.

## Import path

Android Photo Picker selects up to 20 images.

Imported content is copied into a cache queue scoped by project ID:

```text
cache/document_scanner_import_queue/project_<id>/
```

Only one source is sent through CropActivity at a time.

This avoids:

- broad storage permission
- holding many page bitmaps simultaneously
- an interrupted queue accidentally being attached to a different document

## OCR architecture

Bundled ML Kit Latin text recognition runs locally.

OCR can be requested:

- on one page
- across a project sequentially
- during PDF export

Page OCR text and structured data are persisted in Room.

DocumentDataExtractor derives simple fields from recognized text:

- dates
- money-looking amounts
- email addresses
- phone numbers

This extractor is deterministic regex/rule logic. It should not be described as semantic invoice/receipt understanding.

### OCR memory behavior

Pages are recognized one at a time.

The OCR decode longest edge is bounded to 2200 px.

Project-wide OCR does not hold all page bitmaps simultaneously.

## Searchable PDF

For every page:

1. decode bounded page bitmap
2. recognize OCR text
3. update persisted page OCR metadata
4. create A4 portrait/landscape PdfDocument page
5. emit recognized text drawing operations
6. draw the scan raster over those text operations
7. finish the page

The raster controls appearance. The underlying PDF text operations enable search/text extraction.

An OCR failure should not make the page raster itself unexportable.

## Export/storage

Persistent app data:

```text
Room: camera_scan.db
Files: files/projects/<projectId>/pages/
```

Temporary:

```text
cache/
```

Android 10+ public export:

```text
Downloads/CameraScan/
Pictures/CameraScan/
```

FileProvider is used for sharing.

No raw file URI is exposed.

## Privacy/lifecycle

- no INTERNET permission
- no broad media/storage permission
- camera hardware is optional
- camera permission requested only in ScanActivity
- import/OCR/project browsing work without opening CameraX
- bundled OCR model
- no cloud document upload

## Complexity overview

Let:

- N = live working pixels
- P = retained contour boundary points
- M = output/edit page pixels
- B = copied file bytes
- K = number of project pages

| Stage | Practical complexity |
|---|---:|
| Y-plane copy | O(N) |
| live blur/Canny/morphology | O(N) |
| contour extraction | O(N + P) |
| quad score | O(P) |
| 5-frame consensus | O(1), fixed window |
| live quality | O(N) on <=480px image |
| overlay | O(1) |
| accurate detection | several O(N + P) passes |
| curved boundary remap | O(M) |
| filters | ~O(M) |
| dewarp | ~O(M) with fixed bins/strips |
| import/copy | O(B) |
| Room page listing | O(K) |
| thumbnail decode | bounded sampled decode |
| project OCR | K sequential OCR operations |
| export | K sequential OCR/render operations |

## Known technical limits

- eight handles give one quadratic bend per edge, not arbitrary spline control
- dewarp is not learned 3D surface reconstruction
- OCR is Latin-focused
- regex field extraction is deliberately simple
- page originals/edit recipes are not yet retained for lossless recrop later
- manually edited OCR text is updated by a later full-project OCR/export run
- classical paper detection can still fail under extreme glare/occlusion/low contrast

## Validation

CI checks:

```text
:app:assembleDebug
:app:lintDebug
```

Real-device validation is still required. See [DEVICE_TESTING.md](./DEVICE_TESTING.md).
