package dev.nizav.documentscanner.camera;

import android.graphics.Rect;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;

import dev.nizav.documentscanner.cv.DetectionResult;
import dev.nizav.documentscanner.cv.DocumentDetector;
import dev.nizav.documentscanner.cv.Quad;
import dev.nizav.documentscanner.cv.QuadConsensus;
import dev.nizav.documentscanner.cv.QuadStabilizer;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;

import java.nio.ByteBuffer;

public final class DocumentAnalyzer implements ImageAnalysis.Analyzer, AutoCloseable {
    private static final long MIN_ANALYSIS_INTERVAL_NS = 80_000_000L;

    public interface Listener {
        void onDocument(
                Quad normalizedQuad,
                boolean stable,
                float score,
                FrameQuality quality,
                boolean autoCaptureReady
        );
    }

    private final DocumentDetector detector = new DocumentDetector();
    private final QuadConsensus consensus = new QuadConsensus();
    private final QuadStabilizer stabilizer = new QuadStabilizer();
    private final FrameQualityEstimator qualityEstimator =
            new FrameQualityEstimator();
    private final AutoCaptureGate autoCaptureGate = new AutoCaptureGate();
    private final Listener listener;

    private final Mat rawGray = new Mat();
    private final Mat rotatedGray = new Mat();

    private byte[] yBytes = new byte[0];
    private byte[] rowBytes = new byte[0];
    private long lastAnalysisNs;

    public DocumentAnalyzer(Listener listener) {
        this.listener = listener;
    }

    @Override
    public void analyze(@NonNull ImageProxy image) {
        long now = SystemClock.elapsedRealtimeNanos();
        if (now - lastAnalysisNs < MIN_ANALYSIS_INTERVAL_NS) {
            image.close();
            return;
        }
        lastAnalysisNs = now;

        Mat cropped = null;
        try {
            Mat gray = copyLuma(image);
            Rect crop = image.getCropRect();

            int left = Math.max(0, Math.min(crop.left, gray.cols() - 1));
            int top = Math.max(0, Math.min(crop.top, gray.rows() - 1));
            int right = Math.max(left + 1, Math.min(crop.right, gray.cols()));
            int bottom = Math.max(top + 1, Math.min(crop.bottom, gray.rows()));

            cropped = gray.submat(top, bottom, left, right);
            Mat oriented = orient(
                    cropped,
                    image.getImageInfo().getRotationDegrees()
            );

            DetectionResult detection = detector.detect(oriented, false);
            Quad consensusQuad = consensus.update(
                    detection == null ? null : detection.quad
            );
            QuadStabilizer.State state = stabilizer.update(consensusQuad);
            FrameQuality quality = qualityEstimator.measure(oriented);

            float score = detection == null ? 0f : detection.score;
            boolean trigger = autoCaptureGate.update(
                    detection != null,
                    state.stable,
                    score,
                    quality,
                    SystemClock.elapsedRealtime()
            );

            listener.onDocument(
                    state.quad,
                    state.stable,
                    score,
                    quality,
                    trigger
            );
        } finally {
            if (cropped != null) {
                cropped.release();
            }
            image.close();
        }
    }

    public void resetAutoCapture() {
        autoCaptureGate.reset();
        consensus.reset();
        stabilizer.reset();
    }

    private Mat copyLuma(ImageProxy image) {
        ImageProxy.PlaneProxy plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer().duplicate();
        int width = image.getWidth();
        int height = image.getHeight();
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();
        int required = width * height;

        if (yBytes.length < required) {
            yBytes = new byte[required];
        }

        if (pixelStride == 1) {
            for (int row = 0; row < height; row++) {
                int rowStart = row * rowStride;
                if (rowStart >= buffer.limit()) {
                    break;
                }
                buffer.position(rowStart);
                int readable = Math.min(width, buffer.remaining());
                buffer.get(yBytes, row * width, readable);
                for (int col = readable; col < width; col++) {
                    yBytes[row * width + col] = 0;
                }
            }
        } else {
            if (rowBytes.length < rowStride) {
                rowBytes = new byte[rowStride];
            }
            for (int row = 0; row < height; row++) {
                int rowStart = row * rowStride;
                if (rowStart >= buffer.limit()) {
                    break;
                }
                buffer.position(rowStart);
                int readable = Math.min(rowStride, buffer.remaining());
                buffer.get(rowBytes, 0, readable);
                int out = row * width;
                for (int col = 0; col < width; col++) {
                    int srcIndex = col * pixelStride;
                    yBytes[out + col] = srcIndex < readable
                            ? rowBytes[srcIndex]
                            : 0;
                }
            }
        }

        rawGray.create(height, width, CvType.CV_8UC1);
        rawGray.put(0, 0, yBytes, 0, required);
        return rawGray;
    }

    private Mat orient(Mat input, int rotationDegrees) {
        switch (rotationDegrees) {
            case 90:
                Core.rotate(input, rotatedGray, Core.ROTATE_90_CLOCKWISE);
                return rotatedGray;
            case 180:
                Core.rotate(input, rotatedGray, Core.ROTATE_180);
                return rotatedGray;
            case 270:
                Core.rotate(input, rotatedGray, Core.ROTATE_90_COUNTERCLOCKWISE);
                return rotatedGray;
            default:
                return input;
        }
    }

    @Override
    public void close() {
        detector.close();
        qualityEstimator.close();
        rawGray.release();
        rotatedGray.release();
    }
}
