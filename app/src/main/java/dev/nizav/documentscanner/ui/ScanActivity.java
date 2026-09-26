package dev.nizav.documentscanner.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Size;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
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

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.common.util.concurrent.ListenableFuture;

import dev.nizav.documentscanner.R;
import dev.nizav.documentscanner.ScannerApp;
import dev.nizav.documentscanner.camera.DocumentAnalyzer;
import dev.nizav.documentscanner.camera.FrameQuality;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class ScanActivity extends MaterialMotionActivity {
    public static final String EXTRA_PROJECT_ID = "project_id";

    private final ExecutorService analyzerExecutor =
            Executors.newSingleThreadExecutor();

    private long projectId;
    private PreviewView previewView;
    private DocumentOverlayView overlay;
    private TextView statusText;
    private FloatingActionButton captureButton;
    private MaterialButton torchButton;
    private MaterialButton autoCaptureButton;

    private ProcessCameraProvider cameraProvider;
    private ImageCapture imageCapture;
    private ImageAnalysis imageAnalysis;
    private Camera camera;
    private DocumentAnalyzer analyzer;

    private boolean torchEnabled;
    private boolean autoCaptureEnabled = true;
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
                            finish();
                        }
                    }
            );

    private final ActivityResultLauncher<Intent> cropLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    this::onCropResult
            );

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_scan);

        projectId = getIntent().getLongExtra(EXTRA_PROJECT_ID, -1L);
        if (projectId <= 0L) {
            finish();
            return;
        }

        previewView = findViewById(R.id.previewView);
        overlay = findViewById(R.id.documentOverlay);
        statusText = findViewById(R.id.statusText);
        captureButton = findViewById(R.id.captureButton);
        torchButton = findViewById(R.id.torchButton);
        autoCaptureButton = findViewById(R.id.autoCaptureButton);

        MaterialToolbar toolbar = findViewById(R.id.scanToolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        previewView.setImplementationMode(
                PreviewView.ImplementationMode.PERFORMANCE
        );

        captureButton.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            capture();
        });
        torchButton.setOnClickListener(v -> toggleTorch());
        autoCaptureButton.setOnClickListener(v -> {
            autoCaptureEnabled = !autoCaptureEnabled;
            autoCaptureButton.setText(
                    autoCaptureEnabled
                            ? R.string.auto_on
                            : R.string.auto_off
            );
            if (analyzer != null) {
                analyzer.resetAutoCapture();
            }
        });
        previewView.setOnTouchListener((v, event) -> focusAt(event));

        if (!ScannerApp.isOpenCvReady()) {
            Toast.makeText(
                    this,
                    "OpenCV is unavailable",
                    Toast.LENGTH_LONG
            ).show();
            finish();
            return;
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA);
        }
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

        disposeAnalyzerUseCase();

        analyzer = new DocumentAnalyzer(
                (quad, stable, score, quality, ready) ->
                        runOnUiThread(() -> {
                            overlay.setDocument(quad, stable);
                            updateStatus(quad != null, stable, quality);
                            if (ready
                                    && autoCaptureEnabled
                                    && !captureInFlight
                                    && activeSource == null) {
                                capture();
                            }
                        })
        );
        imageAnalysis = analysis;
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
        torchButton.setText(
                torchEnabled ? R.string.torch_on : R.string.torch
        );
    }

    private void capture() {
        ImageCapture capture = imageCapture;
        if (capture == null || captureInFlight || activeSource != null) {
            return;
        }

        captureInFlight = true;
        captureButton.setEnabled(false);
        autoCaptureButton.setEnabled(false);
        statusText.setText(R.string.status_processing);

        File output = new File(
                getCacheDir(),
                "capture_" + System.currentTimeMillis() + ".jpg"
        );
        activeSource = output;

        ImageCapture.OutputFileOptions options =
                new ImageCapture.OutputFileOptions.Builder(output).build();

        capture.takePicture(
                options,
                ContextCompat.getMainExecutor(this),
                new ImageCapture.OnImageSavedCallback() {
                    @Override
                    public void onImageSaved(
                            @NonNull ImageCapture.OutputFileResults results
                    ) {
                        captureInFlight = false;
                        captureButton.setEnabled(true);
                        autoCaptureButton.setEnabled(true);
                        if (analyzer != null) {
                            analyzer.resetAutoCapture();
                        }

                        Intent intent = new Intent(
                                ScanActivity.this,
                                CropActivity.class
                        );
                        intent.putExtra(
                                CropActivity.EXTRA_IMAGE_PATH,
                                output.getAbsolutePath()
                        );
                        intent.putExtra(
                                CropActivity.EXTRA_PROJECT_ID,
                                projectId
                        );
                        cropLauncher.launch(intent);
                    }

                    @Override
                    public void onError(@NonNull ImageCaptureException error) {
                        activeSource = null;
                        output.delete();
                        captureInFlight = false;
                        captureButton.setEnabled(true);
                        autoCaptureButton.setEnabled(true);
                        if (analyzer != null) {
                            analyzer.resetAutoCapture();
                        }
                        Toast.makeText(
                                ScanActivity.this,
                                "Capture failed: " + error.getMessage(),
                                Toast.LENGTH_LONG
                        ).show();
                    }
                }
        );
    }

    private void onCropResult(ActivityResult result) {
        File source = activeSource;
        activeSource = null;
        if (source != null) {
            source.delete();
        }

        if (result.getResultCode() == RESULT_OK) {
            setResult(RESULT_OK);
            finish();
            return;
        }

        if (analyzer != null) {
            analyzer.resetAutoCapture();
        }
    }

    private void disposeAnalyzerUseCase() {
        ImageAnalysis oldAnalysis = imageAnalysis;
        imageAnalysis = null;
        if (oldAnalysis != null) {
            oldAnalysis.clearAnalyzer();
        }

        DocumentAnalyzer oldAnalyzer = analyzer;
        analyzer = null;
        if (oldAnalyzer != null) {
            try {
                analyzerExecutor.execute(oldAnalyzer::close);
            } catch (java.util.concurrent.RejectedExecutionException ignored) {
                // Executor teardown already started. Do not release OpenCV
                // Mats from the UI thread while analysis may still be active.
            }
        }
    }

    @Override
    protected void onDestroy() {
        disposeAnalyzerUseCase();

        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }

        analyzerExecutor.shutdown();
        super.onDestroy();
    }
}
