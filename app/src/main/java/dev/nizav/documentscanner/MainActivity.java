package dev.nizav.documentscanner;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.util.Size;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.core.UseCaseGroup;
import androidx.camera.core.ViewPort;
import androidx.camera.core.resolutionselector.AspectRatioStrategy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import dev.nizav.documentscanner.camera.DocumentAnalyzer;
import dev.nizav.documentscanner.camera.FrameQuality;
import dev.nizav.documentscanner.data.ImportQueueStore;
import dev.nizav.documentscanner.data.ScanSessionStore;
import dev.nizav.documentscanner.ui.CropActivity;
import dev.nizav.documentscanner.ui.DocumentOverlayView;
import dev.nizav.documentscanner.ui.SessionActivity;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class MainActivity extends AppCompatActivity {
    private PreviewView previewView;
    private DocumentOverlayView overlay;
    private TextView statusText;
    private Button captureButton;
    private Button torchButton;
    private ToggleButton autoCaptureButton;
    private Button importButton;
    private Button pagesButton;

    private final ExecutorService analyzerExecutor =
            Executors.newSingleThreadExecutor();
    private final ExecutorService ioExecutor =
            Executors.newSingleThreadExecutor();

    private ProcessCameraProvider cameraProvider;
    private ImageCapture imageCapture;
    private Camera camera;
    private DocumentAnalyzer analyzer;
    private boolean torchEnabled;
    private boolean captureInFlight;
    private int lastStatus = -1;
    private File activeSource;

    private final ActivityResultLauncher<String> permissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestPermission(),
                    granted -> {
                        if (granted) {
                            startCamera();
                        } else {
                            Toast.makeText(
                                    this,
                                    R.string.camera_permission_required,
                                    Toast.LENGTH_LONG
                            ).show();
                        }
                    }
            );

    private final ActivityResultLauncher<Intent> cropLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    this::onCropResult
            );

    private final ActivityResultLauncher<PickVisualMediaRequest> galleryLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.PickMultipleVisualMedia(20),
                    this::onGalleryPicked
            );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.previewView);
        overlay = findViewById(R.id.documentOverlay);
        statusText = findViewById(R.id.statusText);
        captureButton = findViewById(R.id.captureButton);
        torchButton = findViewById(R.id.torchButton);
        autoCaptureButton = findViewById(R.id.autoCaptureButton);
        importButton = findViewById(R.id.importButton);
        pagesButton = findViewById(R.id.pagesButton);

        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        previewView.setImplementationMode(
                PreviewView.ImplementationMode.PERFORMANCE
        );

        captureButton.setOnClickListener(v -> capture(false));
        torchButton.setOnClickListener(v -> toggleTorch());
        autoCaptureButton.setOnCheckedChangeListener((button, checked) -> {
            if (analyzer != null) analyzer.resetAutoCapture();
        });
        importButton.setOnClickListener(v -> launchGallery());
        pagesButton.setOnClickListener(v -> startActivity(
                new Intent(this, SessionActivity.class)
        ));
        previewView.setOnTouchListener((v, event) -> focusAt(event));

        if (!ScannerApp.isOpenCvReady()) {
            Toast.makeText(
                    this,
                    "OpenCV is unavailable",
                    Toast.LENGTH_LONG
            ).show();
            captureButton.setEnabled(false);
            return;
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA);
        }

        updatePageCount();
        launchNextImportedIfNeeded();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updatePageCount();
    }

    private void launchGallery() {
        PickVisualMediaRequest request =
                new PickVisualMediaRequest.Builder()
                        .setMediaType(
                                ActivityResultContracts.PickVisualMedia
                                        .ImageOnly.INSTANCE
                        )
                        .setMaxItems(20)
                        .build();
        galleryLauncher.launch(request);
    }

    private void onGalleryPicked(List<Uri> uris) {
        if (uris == null || uris.isEmpty()) {
            return;
        }

        importButton.setEnabled(false);
        statusText.setText(R.string.status_importing);

        ioExecutor.execute(() -> {
            try {
                int copied = ImportQueueStore.replaceWith(this, uris);
                runOnUiThread(() -> {
                    importButton.setEnabled(true);
                    if (copied == 0) {
                        Toast.makeText(
                                this,
                                R.string.import_failed,
                                Toast.LENGTH_LONG
                        ).show();
                        return;
                    }
                    launchNextImportedIfNeeded();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    importButton.setEnabled(true);
                    Toast.makeText(
                            this,
                            "Import failed: " + e.getMessage(),
                            Toast.LENGTH_LONG
                    ).show();
                });
            }
        });
    }

    private void launchNextImportedIfNeeded() {
        if (activeSource != null) {
            return;
        }
        File next = ImportQueueStore.peek(this);
        if (next != null) {
            launchCrop(next);
        }
    }

    private void onCropResult(ActivityResult result) {
        File completed = activeSource;
        activeSource = null;

        if (completed != null) {
            ImportQueueStore.complete(completed);
        }

        updatePageCount();
        launchNextImportedIfNeeded();
    }

    private void updatePageCount() {
        int count = ScanSessionStore.pageCount(this);
        pagesButton.setText(
                getString(R.string.pages_count, count)
        );
        pagesButton.setEnabled(count > 0);
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(this);

        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                previewView.post(this::bindUseCases);
            } catch (Exception e) {
                Toast.makeText(
                        this,
                        "Unable to start camera: " + e.getMessage(),
                        Toast.LENGTH_LONG
                ).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindUseCases() {
        if (isFinishing() || isDestroyed() || cameraProvider == null) {
            return;
        }

        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        ResolutionSelector analysisResolution = new ResolutionSelector.Builder()
                .setAspectRatioStrategy(
                        AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
                )
                .setResolutionStrategy(
                        new ResolutionStrategy(
                                new Size(1280, 960),
                                ResolutionStrategy
                                        .FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                        )
                )
                .build();

        imageCapture = new ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build();

        ImageAnalysis analysis = new ImageAnalysis.Builder()
                .setResolutionSelector(analysisResolution)
                .setBackpressureStrategy(
                        ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
                )
                .setOutputImageFormat(
                        ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888
                )
                .build();

        if (analyzer != null) {
            analyzer.close();
        }
        analyzer = new DocumentAnalyzer(
                (quad, stable, score, quality, autoCaptureReady) ->
                        runOnUiThread(() -> {
                            overlay.setDocument(quad, stable);
                            updateStatus(quad != null, stable, quality);

                            if (autoCaptureReady
                                    && autoCaptureButton.isChecked()
                                    && !captureInFlight
                                    && activeSource == null) {
                                capture(true);
                            }
                        })
        );
        analysis.setAnalyzer(analyzerExecutor, analyzer);

        cameraProvider.unbindAll();

        UseCaseGroup.Builder group = new UseCaseGroup.Builder()
                .addUseCase(preview)
                .addUseCase(imageCapture)
                .addUseCase(analysis);

        ViewPort viewPort = previewView.getViewPort();
        if (viewPort != null) {
            group.setViewPort(viewPort);
        }

        camera = cameraProvider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                group.build()
        );

        torchButton.setEnabled(camera.getCameraInfo().hasFlashUnit());
    }

    private void updateStatus(
            boolean hasQuad,
            boolean stable,
            FrameQuality quality
    ) {
        int state;
        int text;

        if (!hasQuad) {
            state = 0;
            text = R.string.status_searching;
        } else if (quality != null && !quality.exposureOk) {
            if (quality.luminance < 42.0) {
                state = 3;
                text = R.string.status_dark;
            } else {
                state = 4;
                text = R.string.status_bright;
            }
        } else if (quality != null && !quality.sharpnessOk) {
            state = 5;
            text = R.string.status_blurry;
        } else if (stable) {
            state = 2;
            text = R.string.status_stable;
        } else {
            state = 1;
            text = R.string.status_found;
        }

        if (state != lastStatus && activeSource == null) {
            lastStatus = state;
            statusText.setText(text);
        }
    }

    private boolean focusAt(MotionEvent event) {
        if (event.getActionMasked() != MotionEvent.ACTION_UP || camera == null) {
            return true;
        }

        androidx.camera.core.MeteringPoint point =
                previewView.getMeteringPointFactory().createPoint(
                        event.getX(),
                        event.getY()
                );

        FocusMeteringAction action = new FocusMeteringAction.Builder(
                point,
                FocusMeteringAction.FLAG_AF | FocusMeteringAction.FLAG_AE
        ).setAutoCancelDuration(3, TimeUnit.SECONDS).build();

        camera.getCameraControl().startFocusAndMetering(action);
        return true;
    }

    private void toggleTorch() {
        if (camera == null || !camera.getCameraInfo().hasFlashUnit()) {
            return;
        }

        torchEnabled = !torchEnabled;
        camera.getCameraControl().enableTorch(torchEnabled);
        torchButton.setAlpha(torchEnabled ? 1f : 0.65f);
    }

    private void capture(boolean automatic) {
        ImageCapture capture = imageCapture;
        if (capture == null || captureInFlight || activeSource != null) {
            return;
        }

        captureInFlight = true;
        captureButton.setEnabled(false);
        importButton.setEnabled(false);
        autoCaptureButton.setEnabled(false);
        statusText.setText(R.string.status_processing);

        File output = new File(
                getCacheDir(),
                "capture_" + System.currentTimeMillis() + ".jpg"
        );

        ImageCapture.OutputFileOptions options =
                new ImageCapture.OutputFileOptions.Builder(output).build();

        capture.takePicture(
                options,
                ContextCompat.getMainExecutor(this),
                new ImageCapture.OnImageSavedCallback() {
                    @Override
                    public void onImageSaved(
                            @NonNull ImageCapture.OutputFileResults outputFileResults
                    ) {
                        captureInFlight = false;
                        captureButton.setEnabled(true);
                        importButton.setEnabled(true);
                        autoCaptureButton.setEnabled(true);
                        if (analyzer != null) analyzer.resetAutoCapture();
                        launchCrop(output);
                    }

                    @Override
                    public void onError(@NonNull ImageCaptureException exception) {
                        captureInFlight = false;
                        captureButton.setEnabled(true);
                        importButton.setEnabled(true);
                        autoCaptureButton.setEnabled(true);
                        if (analyzer != null) analyzer.resetAutoCapture();
                        statusText.setText(R.string.status_searching);
                        Toast.makeText(
                                MainActivity.this,
                                "Capture failed: " + exception.getMessage(),
                                Toast.LENGTH_LONG
                        ).show();
                    }
                }
        );
    }

    private void launchCrop(File source) {
        activeSource = source;
        Intent intent = new Intent(this, CropActivity.class);
        intent.putExtra(
                CropActivity.EXTRA_IMAGE_PATH,
                source.getAbsolutePath()
        );
        cropLauncher.launch(intent);
    }

    @Override
    protected void onDestroy() {
        if (analyzer != null) {
            analyzer.close();
            analyzer = null;
        }
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
        analyzerExecutor.shutdownNow();
        ioExecutor.shutdownNow();
        super.onDestroy();
    }
}
