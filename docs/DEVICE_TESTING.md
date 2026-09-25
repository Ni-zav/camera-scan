# Real-Device Validation Matrix

Use this after building the current project-first version.

## Device record

For every run record:

- app commit SHA
- device model
- Android/API version
- RAM
- screen size/aspect ratio
- debug or release
- whether camera hardware exists
- whether Google Play services exists

## 1. Home / projects

Fresh install:

- [ ] opens Documents home, not camera
- [ ] does not request Camera permission
- [ ] empty state looks intentional
- [ ] New document opens Material dialog
- [ ] an empty document can be created
- [ ] empty document survives leaving/reopening app
- [ ] first page becomes project cover
- [ ] page count updates
- [ ] dynamic color does not make text/icons unreadable
- [ ] dark theme remains readable

## 2. Project page views

With at least 5 pages:

- [ ] Gallery is default
- [ ] gallery shows two-column larger previews
- [ ] List mode shows small thumbnail on left
- [ ] switching Gallery/List animates without losing scroll/page state unexpectedly
- [ ] page tap opens Page Details
- [ ] returning preserves document
- [ ] 20+ pages scroll without loading full-resolution page bitmaps into every row

## 3. Import-only flow

Without granting Camera permission:

- [ ] create document
- [ ] import one photo
- [ ] import several photos
- [ ] cancel Photo Picker
- [ ] import queue opens each image sequentially in editor
- [ ] pages save to the correct project
- [ ] interrupt import, open another project, verify old queue cannot land in the other project

If testing a device without camera hardware:

- [ ] app installs
- [ ] projects/import/OCR remain usable

## 4. Camera permission lifecycle

From Project -> Scan page:

- [ ] Camera permission is requested here, not earlier
- [ ] denial exits scanner safely
- [ ] grant starts camera
- [ ] Back releases camera
- [ ] reopen Scan reacquires camera
- [ ] Home -> resume works
- [ ] lock/unlock works
- [ ] repeated Scan -> Crop -> Scan does not leak camera
- [ ] no duplicate auto capture after returning from crop

## 5. Live overlay

Test paper:

- centered
- near each screen edge
- portrait
- landscape
- 20-40° perspective

Verify:

- [ ] overlay follows paper boundary
- [ ] no stable systematic offset
- [ ] smoothing reduces jitter without excessive lag
- [ ] overlay disappears after detection loss

Do not fix device-specific drift with arbitrary screen offsets. Investigate CameraX ViewPort/crop/rotation mapping.

## 6. Auto capture quality

Test:

- [ ] normal light
- [ ] low light
- [ ] overexposed page
- [ ] deliberate hand motion
- [ ] deliberate defocus
- [ ] refocus + hold steady

Expected:

- dark/bright/blurry frames delay auto capture
- manual shutter remains available
- a stable good frame fires once

Do not tune the Laplacian threshold from a single phone.

## 7. 8-handle page boundary

In Adjust page, verify there are:

- [ ] four corner handles
- [ ] top midpoint
- [ ] right midpoint
- [ ] bottom midpoint
- [ ] left midpoint

Test flat page:

- [ ] midpoints begin centered on straight detected edges
- [ ] flatten remains visually equivalent to normal perspective crop

Test bowed edges:

- [ ] drag top midpoint to a curved top edge
- [ ] drag bottom midpoint independently
- [ ] drag left/right midpoint independently
- [ ] rendered white boundary follows the visible curve
- [ ] flatten straightens that boundary in output

Test corners:

- [ ] moving a corner also moves adjacent midpoint partially so the curve does not violently kink
- [ ] all handles remain clamped to image bounds

Look for:

- folds
- self-intersecting boundary
- extreme handle placement
- severe stretching near corners

The editor does not currently reject every pathological self-intersection, so adversarial handle placement is a useful test.

## 8. Curved-boundary performance

Use a 12-20 MP source.

Check:

- [ ] Flatten does not block UI thread
- [ ] no OOM
- [ ] 4096px output cap respected
- [ ] repeated Adjust -> Flatten cycles return to a stable memory range

The remap should use banded map allocation rather than two full-page maps.

## 9. Page cleanup

Compare Original against:

- Clean color
- Paper clean
- Grayscale
- B&W
- Finger fix
- Dewarp

Test:

- clean white paper
- gray/yellow paper
- side shadow
- phone shadow
- colored ink
- photo-heavy page
- finger on edge
- skin-colored printed image near edge
- slightly curved book page
- blank page

Finger fix and Dewarp should stay user-invoked.

## 10. OCR: one page

Open Page Details:

- [ ] page image displays
- [ ] Extract text runs off UI thread
- [ ] OCR text becomes editable
- [ ] Save text persists manual edit
- [ ] reopen page retains text
- [ ] detected data updates

Text samples:

- email address
- Indonesian phone number
- ISO/slash/date
- Rp/IDR amount
- ordinary paragraph

Confirm detected-data cards do not claim semantic meaning beyond matching these simple fields.

## 11. OCR: project

With multiple pages:

- [ ] Extract text indexes pages sequentially
- [ ] progress changes page by page
- [ ] indexed count updates
- [ ] project text preview appears
- [ ] page rows show Text ready
- [ ] UI remains responsive during OCR

Also test:

- [ ] small text
- [ ] low contrast
- [ ] handwriting
- [ ] non-Latin script

Non-Latin is a documented limitation of the bundled Latin recognizer.

## 12. Searchable PDF

Export a mixed portrait/landscape project:

- [ ] PDF opens
- [ ] page order matches project order
- [ ] visible raster matches processed page
- [ ] obvious OCR words can be searched
- [ ] text extraction works where recognition succeeds
- [ ] weak OCR on one page does not crash whole export
- [ ] export also refreshes stored OCR metadata

Android 10+:

- [ ] PDF under Downloads/CameraScan
- [ ] JPEG pages under Pictures/CameraScan
- [ ] no zero-byte pending MediaStore items after successful export
- [ ] share chooser can open PDF in another app

## 13. Persistence

Create multiple projects.

- [ ] projects remain after process death
- [ ] page order remains
- [ ] OCR text remains
- [ ] OCR structured data remains
- [ ] app relaunch never creates a new implicit camera/session
- [ ] deleting a page deletes its stored file
- [ ] deleting a project in a future UI must cascade page metadata/files consistently

## 14. Memory

Useful command:

```bash
adb shell dumpsys meminfo dev.nizav.documentscanner
```

Take comparable snapshots:

1. cold Documents home
2. after scrolling many project/page thumbnails
3. 60 seconds camera preview
4. after 10 Scan -> Crop cycles
5. during 20-page OCR
6. during 20-page PDF export
7. after work completes

Expected structural behavior:

- camera memory only appears when scanner is open
- RecyclerView thumbnails stay bounded by sampled decode/LruCache
- OCR processes pages sequentially
- export does not load every full page bitmap simultaneously
- repeated crop cycles do not grow memory indefinitely

## 15. Regression corpus

Keep a private test corpus outside Git:

```text
flat-dark-background/
flat-light-background/
white-on-white/
low-light/
glare/
shadow/
bowed-edges/
curved-book/
finger/
receipts/
photo-heavy/
```

For geometry changes, save both:

- source image
- final flattened output

This makes 8-handle warp changes comparable instead of subjective.
