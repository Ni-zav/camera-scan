package dev.nizav.documentscanner.ui;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;
import android.widget.TextView;
import android.view.ViewGroup;

import androidx.transition.TransitionManager;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.transition.MaterialFadeThrough;

import dev.nizav.documentscanner.R;
import dev.nizav.documentscanner.ScannerApp;
import dev.nizav.documentscanner.cv.Boundary8;
import dev.nizav.documentscanner.cv.CurvedBoundaryCorrector;
import dev.nizav.documentscanner.cv.ImageEnhancer;
import dev.nizav.documentscanner.cv.PageDewarper;
import dev.nizav.documentscanner.cv.Quad;
import dev.nizav.documentscanner.data.ProjectRepository;
import dev.nizav.documentscanner.util.BitmapUtils;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class CropActivity extends MaterialMotionActivity {
    public static final String EXTRA_IMAGE_PATH = "image_path";
    public static final String EXTRA_PROJECT_ID = "project_id";
    private static final int MAX_DECODE_EDGE = 3072;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService detectionWorker =
            Executors.newSingleThreadExecutor();
    private final AtomicInteger renderGeneration = new AtomicInteger();
    private final AtomicBoolean destroyed = new AtomicBoolean();
    private final Object sourceBitmapLock = new Object();

    private long projectId;
    private ProjectRepository repository;

    private ViewGroup editorRoot;
    private TextView editorHint;
    private DocumentCropView cropView;
    private View filterBar;
    private Button flattenButton;
    private Button saveButton;
    private Button dewarpButton;
    private LinearProgressIndicator edgeDetectionProgress;

    private Bitmap sourceBitmap;
    private Bitmap baseWarped;
    private Bitmap displayedBitmap;
    private Boundary8 cropBoundary;
    private boolean flattened;
    private boolean transformBusy;
    private boolean applyingBoundary;
    private boolean userAdjustedBoundary;
    private Future<?> detectionFuture;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_crop);

        projectId = getIntent().getLongExtra(EXTRA_PROJECT_ID, -1L);
        if (projectId <= 0L) {
            finish();
            return;
        }
        repository = new ProjectRepository(this);

        editorRoot = findViewById(R.id.editorRoot);
        editorHint = findViewById(R.id.editorHint);
        cropView = findViewById(R.id.cropView);

        MaterialToolbar toolbar = findViewById(R.id.cropToolbar);
        toolbar.setNavigationOnClickListener(v -> {
            setResult(Activity.RESULT_CANCELED);
            finish();
        });
        filterBar = findViewById(R.id.filterBar);
        flattenButton = findViewById(R.id.flattenButton);
        saveButton = findViewById(R.id.saveButton);
        dewarpButton = findViewById(R.id.dewarpButton);
        edgeDetectionProgress = findViewById(R.id.edgeDetectionProgress);

        cropView.setBoundaryChangeListener(boundary -> {
            if (!applyingBoundary) {
                userAdjustedBoundary = true;
            }
            updateBoundaryValidity(boundary);
        });

        findViewById(R.id.retakeButton).setOnClickListener(v -> {
            setResult(Activity.RESULT_CANCELED);
            finish();
        });
        flattenButton.setOnClickListener(v -> {
            if (transformBusy) return;
            if (flattened) {
                returnToCrop();
            } else {
                flatten();
            }
        });
        saveButton.setOnClickListener(v -> {
            if (!transformBusy) savePage();
        });

        findViewById(R.id.filterOriginal).setOnClickListener(
                v -> applyFilter(ImageEnhancer.Filter.ORIGINAL)
        );
        findViewById(R.id.filterColor).setOnClickListener(
                v -> applyFilter(ImageEnhancer.Filter.CLEAN_COLOR)
        );
        findViewById(R.id.filterCleanPaper).setOnClickListener(
                v -> applyFilter(ImageEnhancer.Filter.CLEAN_PAPER)
        );
        findViewById(R.id.filterGray).setOnClickListener(
                v -> applyFilter(ImageEnhancer.Filter.GRAYSCALE)
        );
        findViewById(R.id.filterBw).setOnClickListener(
                v -> applyFilter(ImageEnhancer.Filter.BLACK_WHITE)
        );
        findViewById(R.id.filterFinger).setOnClickListener(
                v -> applyFilter(ImageEnhancer.Filter.FINGER_FIX)
        );
        dewarpButton.setOnClickListener(v -> applyDewarp());

        if (!ScannerApp.isOpenCvReady()) {
            Toast.makeText(this, "OpenCV is unavailable", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        String path = getIntent().getStringExtra(EXTRA_IMAGE_PATH);
        if (path == null) {
            finish();
            return;
        }

        flattenButton.setEnabled(false);
        loadSource(new File(path));
    }

    private void loadSource(File file) {
        edgeDetectionProgress.setVisibility(View.VISIBLE);
        setEditorHint(R.string.loading_image, false);

        worker.execute(() -> {
            try {
                Bitmap bitmap = BitmapUtils.decodeOriented(
                        file,
                        MAX_DECODE_EDGE
                );

                Boundary8 fallback = Boundary8.fromQuad(
                        BitmapUtils.defaultQuad()
                );

                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        bitmap.recycle();
                        return;
                    }

                    sourceBitmap = bitmap;
                    displayedBitmap = bitmap;
                    cropBoundary = fallback;
                    setBoundaryProgrammatically(bitmap, fallback);
                    setEditorHint(R.string.finding_edges, false);
                });

                detectionFuture = detectionWorker.submit(() -> {
                    if (destroyed.get() || Thread.currentThread().isInterrupted()) {
                        return;
                    }
                    Quad detected;
                    synchronized (sourceBitmapLock) {
                        if (destroyed.get()
                                || Thread.currentThread().isInterrupted()) {
                            return;
                        }
                        detected = BitmapUtils.detectDocument(bitmap);
                    }
                    Boundary8 detectedBoundary = Boundary8.fromQuad(detected);

                    runOnUiThread(() -> {
                        if (destroyed.get() || isFinishing() || isDestroyed()) {
                            return;
                        }

                        edgeDetectionProgress.setVisibility(View.GONE);
                        if (!userAdjustedBoundary && !flattened) {
                            cropBoundary = detectedBoundary;
                            setBoundaryProgrammatically(
                                    bitmap,
                                    detectedBoundary
                            );
                        } else if (!flattened) {
                            updateBoundaryValidity(
                                    cropView.getNormalizedBoundary()
                            );
                        }
                    });
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    edgeDetectionProgress.setVisibility(View.GONE);
                    Toast.makeText(
                            this,
                            getString(
                                    R.string.image_open_failed,
                                    e.getMessage()
                            ),
                            Toast.LENGTH_LONG
                    ).show();
                    finish();
                });
            }
        });
    }

    private void setBoundaryProgrammatically(
            Bitmap bitmap,
            Boundary8 boundary
    ) {
        applyingBoundary = true;
        try {
            cropView.setDocument(bitmap, boundary, true);
            updateBoundaryValidity(boundary);
        } finally {
            applyingBoundary = false;
        }
    }

    private void flatten() {
        if (sourceBitmap == null) return;
        cancelEdgeDetection();

        cropBoundary = cropView.getNormalizedBoundary();
        if (!CurvedBoundaryCorrector.isValid(cropBoundary)) {
            updateBoundaryValidity(cropBoundary);
            return;
        }

        transformBusy = true;
        flattenButton.setEnabled(false);
        saveButton.setEnabled(false);
        int generation = renderGeneration.incrementAndGet();

        worker.execute(() -> {
            Bitmap warped;
            synchronized (sourceBitmapLock) {
                warped = CurvedBoundaryCorrector.warp(
                        sourceBitmap,
                        cropBoundary
                );
            }
            runOnUiThread(() -> {
                if (generation != renderGeneration.get()
                        || isFinishing()
                        || isDestroyed()) {
                    warped.recycle();
                    return;
                }

                Bitmap oldBase = baseWarped;
                baseWarped = warped;
                recycleTransientDisplay();

                if (oldBase != null
                        && oldBase != warped
                        && !oldBase.isRecycled()) {
                    oldBase.recycle();
                }

                displayedBitmap = warped;
                flattened = true;
                transformBusy = false;

                TransitionManager.beginDelayedTransition(
                        editorRoot,
                        new MaterialFadeThrough()
                );
                edgeDetectionProgress.setVisibility(View.GONE);
                cropView.setDocument(warped, null, false);
                setEditorHint(R.string.filter_hint, false);
                filterBar.setVisibility(View.VISIBLE);
                flattenButton.setText(R.string.adjust);
                flattenButton.setEnabled(true);
                saveButton.setVisibility(View.VISIBLE);
                saveButton.setEnabled(true);
                dewarpButton.setEnabled(true);
            });
        });
    }

    private void returnToCrop() {
        renderGeneration.incrementAndGet();
        recycleTransientDisplay();

        Bitmap oldBase = baseWarped;
        baseWarped = null;

        displayedBitmap = sourceBitmap;
        flattened = false;
        TransitionManager.beginDelayedTransition(
                editorRoot,
                new MaterialFadeThrough()
        );
        setBoundaryProgrammatically(sourceBitmap, cropBoundary);
        filterBar.setVisibility(View.GONE);
        flattenButton.setText(R.string.flatten);
        saveButton.setEnabled(false);
        saveButton.setVisibility(View.GONE);

        if (oldBase != null && oldBase != sourceBitmap) {
            worker.execute(() -> {
                if (!oldBase.isRecycled()) {
                    oldBase.recycle();
                }
            });
        }
    }

    private void updateBoundaryValidity(Boundary8 boundary) {
        if (flattened || transformBusy || sourceBitmap == null) {
            return;
        }

        boolean valid = CurvedBoundaryCorrector.isValid(boundary);
        flattenButton.setEnabled(valid);
        setEditorHint(
                valid
                        ? R.string.curved_crop_hint
                        : R.string.curved_crop_invalid,
                !valid
        );
    }

    private void setEditorHint(int textRes, boolean error) {
        editorHint.setText(textRes);
        editorHint.setAlpha(error ? 1f : 0.82f);
    }

    private void applyFilter(ImageEnhancer.Filter filter) {
        Bitmap base = baseWarped;
        if (transformBusy
                || !flattened
                || base == null
                || base.isRecycled()) {
            return;
        }

        int generation = renderGeneration.incrementAndGet();
        worker.execute(() -> {
            if (base.isRecycled()) {
                return;
            }

            Bitmap rendered = ImageEnhancer.apply(base, filter);
            runOnUiThread(() -> {
                if (generation != renderGeneration.get()
                        || isFinishing()
                        || isDestroyed()) {
                    rendered.recycle();
                    return;
                }

                recycleTransientDisplay();
                displayedBitmap = rendered;
                cropView.setDocument(rendered, null, false);
            });
        });
    }

    private void applyDewarp() {
        Bitmap base = baseWarped;
        if (transformBusy
                || !flattened
                || base == null
                || base.isRecycled()) {
            return;
        }

        transformBusy = true;
        dewarpButton.setEnabled(false);
        saveButton.setEnabled(false);
        flattenButton.setEnabled(false);
        int generation = renderGeneration.incrementAndGet();

        worker.execute(() -> {
            PageDewarper.Result result = PageDewarper.dewarp(base);
            runOnUiThread(() -> {
                if (generation != renderGeneration.get()
                        || isFinishing()
                        || isDestroyed()) {
                    if (result.bitmap != null) result.bitmap.recycle();
                    return;
                }

                transformBusy = false;
                dewarpButton.setEnabled(true);
                saveButton.setEnabled(true);
                flattenButton.setEnabled(true);

                if (result.bitmap == null) {
                    return;
                }

                if (!result.applied) {
                    result.bitmap.recycle();
                    Toast.makeText(
                            this,
                            R.string.dewarp_not_needed,
                            Toast.LENGTH_SHORT
                    ).show();
                    return;
                }

                recycleTransientDisplay();
                Bitmap oldBase = baseWarped;
                baseWarped = result.bitmap;
                displayedBitmap = result.bitmap;
                cropView.setDocument(result.bitmap, null, false);

                if (oldBase != null
                        && oldBase != result.bitmap
                        && !oldBase.isRecycled()) {
                    oldBase.recycle();
                }

                Toast.makeText(
                        this,
                        R.string.dewarp_applied,
                        Toast.LENGTH_SHORT
                ).show();
            });
        });
    }

    private void savePage() {
        Bitmap bitmap = displayedBitmap;
        if (!flattened || bitmap == null || bitmap.isRecycled()) {
            return;
        }

        cancelEdgeDetection();
        transformBusy = true;
        saveButton.setEnabled(false);
        flattenButton.setEnabled(false);
        dewarpButton.setEnabled(false);
        edgeDetectionProgress.setVisibility(View.VISIBLE);
        setEditorHint(R.string.saving_page, false);

        worker.execute(() -> {
            try {
                repository.addPage(projectId, bitmap);
                runOnUiThread(() -> {
                    if (destroyed.get() || isFinishing() || isDestroyed()) {
                        return;
                    }
                    edgeDetectionProgress.setVisibility(View.GONE);
                    setResult(Activity.RESULT_OK);
                    finish();
                });
            } catch (Throwable e) {
                ScannerApp.recordHandledFailure(
                        "save-page",
                        e
                );
                runOnUiThread(() -> {
                    if (destroyed.get() || isFinishing() || isDestroyed()) {
                        return;
                    }
                    transformBusy = false;
                    edgeDetectionProgress.setVisibility(View.GONE);
                    saveButton.setEnabled(true);
                    flattenButton.setEnabled(true);
                    dewarpButton.setEnabled(true);
                    Toast.makeText(
                            this,
                            getString(
                                    R.string.save_page_failed,
                                    safeMessage(e)
                            ),
                            Toast.LENGTH_LONG
                    ).show();
                });
            }
        });
    }

    private void cancelEdgeDetection() {
        Future<?> future = detectionFuture;
        detectionFuture = null;
        if (future != null && !future.isDone()) {
            future.cancel(true);
        }
        edgeDetectionProgress.setVisibility(View.GONE);
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName()
                : message;
    }

    private void recycleTransientDisplay() {
        if (displayedBitmap != null
                && displayedBitmap != sourceBitmap
                && displayedBitmap != baseWarped
                && !displayedBitmap.isRecycled()) {
            displayedBitmap.recycle();
        }
    }

    @Override
    protected void onDestroy() {
        destroyed.set(true);
        renderGeneration.incrementAndGet();
        cancelEdgeDetection();
        detectionWorker.shutdownNow();
        worker.shutdown();
        cropView.setBoundaryChangeListener(null);
        super.onDestroy();
    }
}
