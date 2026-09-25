package dev.nizav.documentscanner.ui;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

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
import java.util.concurrent.atomic.AtomicInteger;

public final class CropActivity extends AppCompatActivity {
    public static final String EXTRA_IMAGE_PATH = "image_path";
    public static final String EXTRA_PROJECT_ID = "project_id";
    private static final int MAX_DECODE_EDGE = 3072;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicInteger renderGeneration = new AtomicInteger();

    private long projectId;
    private ProjectRepository repository;

    private DocumentCropView cropView;
    private View filterBar;
    private Button flattenButton;
    private Button saveButton;
    private Button dewarpButton;

    private Bitmap sourceBitmap;
    private Bitmap baseWarped;
    private Bitmap displayedBitmap;
    private Boundary8 cropBoundary;
    private boolean flattened;
    private boolean transformBusy;

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

        cropView = findViewById(R.id.cropView);
        filterBar = findViewById(R.id.filterBar);
        flattenButton = findViewById(R.id.flattenButton);
        saveButton = findViewById(R.id.saveButton);
        dewarpButton = findViewById(R.id.dewarpButton);

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
        worker.execute(() -> {
            try {
                Bitmap bitmap = BitmapUtils.decodeOriented(
                        file,
                        MAX_DECODE_EDGE
                );
                Quad detected = BitmapUtils.detectDocument(bitmap);

                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        bitmap.recycle();
                        return;
                    }
                    sourceBitmap = bitmap;
                    displayedBitmap = bitmap;
                    cropBoundary = Boundary8.fromQuad(detected);
                    cropView.setDocument(bitmap, cropBoundary, true);
                    flattenButton.setEnabled(true);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    Toast.makeText(
                            this,
                            "Unable to open capture: " + e.getMessage(),
                            Toast.LENGTH_LONG
                    ).show();
                    finish();
                });
            }
        });
    }

    private void flatten() {
        if (sourceBitmap == null) return;

        cropBoundary = cropView.getNormalizedBoundary();
        transformBusy = true;
        flattenButton.setEnabled(false);
        saveButton.setEnabled(false);
        int generation = renderGeneration.incrementAndGet();

        worker.execute(() -> {
            Bitmap warped = CurvedBoundaryCorrector.warp(sourceBitmap, cropBoundary);
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

                cropView.setDocument(warped, null, false);
                filterBar.setVisibility(View.VISIBLE);
                flattenButton.setText(R.string.adjust);
                flattenButton.setEnabled(true);
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
        cropView.setDocument(sourceBitmap, cropBoundary, true);
        filterBar.setVisibility(View.GONE);
        flattenButton.setText(R.string.flatten);
        saveButton.setEnabled(false);

        if (oldBase != null && oldBase != sourceBitmap) {
            worker.execute(() -> {
                if (!oldBase.isRecycled()) {
                    oldBase.recycle();
                }
            });
        }
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
        if (!flattened || bitmap == null || bitmap.isRecycled()) return;

        saveButton.setEnabled(false);
        flattenButton.setEnabled(false);

        worker.execute(() -> {
            try {
                repository.addPage(projectId, bitmap);
                runOnUiThread(() -> {
                    setResult(Activity.RESULT_OK);
                    finish();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    saveButton.setEnabled(true);
                    flattenButton.setEnabled(true);
                    Toast.makeText(
                            this,
                            "Save failed: " + e.getMessage(),
                            Toast.LENGTH_LONG
                    ).show();
                });
            }
        });
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
        renderGeneration.incrementAndGet();
        worker.shutdown();
        super.onDestroy();
    }
}
