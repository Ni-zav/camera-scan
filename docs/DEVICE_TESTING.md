# Real-Device Scanner Test Matrix

Compilation and lint cannot tell us whether a scanner is good. Camera exposure, focus, YUV layout, glare, paper/background contrast, and OEM camera behavior require real-device validation.

Use this matrix before changing CV thresholds or declaring scanner quality stable.

## Record for every test device

Capture:

- manufacturer/model
- Android version
- API level
- RAM
- rear-camera resolution used by CameraX if known
- screen resolution/aspect ratio
- whether Google Play services is present
- app commit SHA
- debug or release build

Do not compare results from unknown app revisions.

## Basic functional flow

- [ ] Fresh install asks for Camera permission
- [ ] Denying Camera permission does not crash
- [ ] Granting Camera permission opens preview
- [ ] Preview fills the expected region
- [ ] Tap to focus responds
- [ ] Torch toggles on a device with flash
- [ ] Torch control remains harmless on a device without flash
- [ ] Manual capture succeeds
- [ ] Crop editor opens with a reasonable proposed quad
- [ ] Four corner handles are individually draggable
- [ ] Perspective flatten produces the expected orientation/aspect ratio
- [ ] Page can be added to the session
- [ ] Second/third pages can be added
- [ ] Pages can be reordered
- [ ] Pages can be deleted
- [ ] New session clears current session only after confirmation

## Live overlay geometry

The overlay must track the paper itself, not merely look approximately centered.

Test:

- [ ] paper centered
- [ ] paper touching near left edge
- [ ] paper touching near right edge
- [ ] paper near top
- [ ] paper near bottom
- [ ] portrait page
- [ ] landscape page
- [ ] 20-40 degree camera tilt

Fail if a persistent systematic offset appears between visible paper edges and overlay edges.

If it fails on one device, investigate ViewPort/crop/rotation metadata first. Do not add arbitrary display offsets.

## Detection backgrounds

Test one ordinary A4/Letter sheet on:

- [ ] dark matte desk
- [ ] wooden desk
- [ ] patterned surface
- [ ] white/light desk
- [ ] fabric
- [ ] another larger sheet of paper beneath it

Record whether:

- live contour appears
- post-capture automatic crop succeeds
- Hough fallback helps
- GrabCut fallback helps
- manual crop is required

Manual crop being required in an adversarial scene is acceptable. A confidently wrong automatic crop is more important to investigate.

## Lighting

### Low light

- [ ] room ambient only
- [ ] auto capture refuses truly blurred/dark frames
- [ ] torch improves quality when enabled
- [ ] manual capture remains possible

### Bright light

- [ ] bright office light
- [ ] near-window daylight
- [ ] auto capture does not fire on heavily clipped paper
- [ ] printed text remains legible after Paper clean

### Uneven illumination

- [ ] shadow from phone across page
- [ ] shadow across one page corner
- [ ] warm lamp from one side
- [ ] Paper clean reduces low-frequency shading without erasing ink

## Motion/focus

- [ ] move phone slowly while page is detected
- [ ] overlay does not wildly jump frame to frame
- [ ] auto capture waits for stability
- [ ] deliberately defocus
- [ ] auto capture waits while blur score is poor
- [ ] refocus and hold steady
- [ ] auto capture eventually fires once
- [ ] no immediate duplicate capture after returning from crop

Do not tune the Laplacian threshold from one phone only. Different camera sharpening pipelines can shift the measured variance.

## Paper geometry

- [ ] page nearly front-on
- [ ] strong perspective trapezoid
- [ ] one corner close to frame edge
- [ ] page partially outside frame
- [ ] small page occupying <~20% of camera image
- [ ] receipt/narrow document

Verify both proposed crop and final homography.

## Glare

Use a glossy page or laminated document:

- [ ] mild glare
- [ ] glare across one edge
- [ ] severe center glare

Expected: manual crop may be necessary when glare destroys a boundary. The app must remain usable.

## Finger repair

Use a plain page and deliberately hold a corner.

- [ ] finger near outer page edge
- [ ] finger not present
- [ ] skin-colored printed element near page edge
- [ ] large photo containing skin near edge

Finger fix should:

- be opt-in
- refuse implausibly huge masks
- avoid changing the page when no conservative mask is accepted

If it damages legitimate page content, tighten the mask; do not make the feature automatic.

## Paper clean

Compare Original vs Paper clean:

- [ ] clean white document
- [ ] gray/recycled paper
- [ ] yellowish paper
- [ ] strong shadow
- [ ] colored handwriting
- [ ] photo-heavy brochure

The filter should improve paper uniformity without turning meaningful colors into artifacts.

## Dewarp

Use:

- [ ] flat printed page
- [ ] slightly curved book page
- [ ] strongly curved book page
- [ ] blank page
- [ ] image/photo-heavy page
- [ ] page with many horizontal text lines

Expected:

- flat pages should usually report no reliable curve
- slight/moderate text-page bowing can be corrected
- blank/photo-heavy pages may not produce enough baseline evidence and should be rejected
- output must not show severe strip seams

The heuristic is not a replacement for learned 3D page reconstruction.

## Gallery import

- [ ] import one image
- [ ] import several images
- [ ] import 20 images
- [ ] cancel picker
- [ ] one provider item becomes unreadable
- [ ] rotate through each crop flow
- [ ] imported image with EXIF rotation
- [ ] large phone photo

The queue should process one source at a time without requesting broad storage permission.

## Session stress

Create:

- [ ] 5-page session
- [ ] 20-page session

During the session:

- [ ] reorder first -> last
- [ ] reorder last -> first
- [ ] delete middle page
- [ ] add more pages after reorder/delete
- [ ] leave and reopen Pages screen
- [ ] start New session

Watch for:

- page-order collisions
- stale filenames
- excessive memory growth
- UI freeze

## OCR

Use pages containing:

- [ ] clean printed English
- [ ] mixed upper/lowercase
- [ ] numbers/currency
- [ ] small text
- [ ] skewed text after flatten
- [ ] low-contrast text
- [ ] handwriting
- [ ] non-Latin script

The bundled recognizer is Latin-focused. Non-Latin text is a known limitation, not a regression.

## Searchable PDF

Export a multi-page session.

Verify:

- [ ] PDF opens
- [ ] all pages appear in session order
- [ ] portrait and landscape pages render correctly
- [ ] visible page exactly matches raster scan
- [ ] text search finds obvious OCR-recognized words
- [ ] copying/searching text does not shift visible raster
- [ ] one OCR-poor page does not prevent PDF generation

## MediaStore and sharing

Android 10+:

- [ ] PDF appears under Downloads/PaperScanner
- [ ] JPEG pages appear under Pictures/PaperScanner
- [ ] no zero-byte pending items remain after a successful export
- [ ] share chooser opens
- [ ] recipient app can read the shared PDF
- [ ] recipient does not receive a raw filesystem path

Also test export cancellation/app termination during export if robustness around partial MediaStore records becomes a release priority.

## Lifecycle

During camera preview:

- [ ] Home -> return
- [ ] lock -> unlock
- [ ] switch to another app -> return
- [ ] screen rotation policy is respected
- [ ] repeated Crop -> camera -> Crop transitions
- [ ] repeated Pages -> camera transitions

Check for:

- black preview
- multiple simultaneous analyzer callbacks
- camera-in-use errors
- leaked image buffers
- stale overlay
- duplicate auto capture

## Memory/performance observations

Do not invent target FPS/latency numbers before measuring devices.

What should be structurally true:

- preview remains interactive while CV runs
- analysis never queues an unbounded frame backlog
- memory does not grow continuously while preview sits idle
- repeated capture/edit cycles return near a stable memory baseline
- filters/dewarp/export do not block touch rendering on the main thread
- OCR/export handles pages sequentially
- 20-page session export does not load all full-size bitmaps simultaneously

Useful tools:

- Android Studio Profiler
- Perfetto
- `adb shell dumpsys meminfo dev.nizav.documentscanner`
- `adb logcat`

Example memory snapshot:

```bash
adb shell dumpsys meminfo dev.nizav.documentscanner
```

Take comparable snapshots:

1. after cold launch
2. after 60 seconds preview
3. after 10 capture/edit cycles
4. during a 20-page export
5. after export completes

## Regression artifacts

For a serious tuning pass, keep a small local test corpus with categories such as:

```text
flat-dark-background/
flat-light-background/
low-light/
glare/
shadow/
curved-book/
finger/
receipts/
photo-heavy/
```

Do not commit private/personal documents.

For each algorithm change, compare proposed corner coordinates and final page images against the same corpus before changing thresholds globally.
