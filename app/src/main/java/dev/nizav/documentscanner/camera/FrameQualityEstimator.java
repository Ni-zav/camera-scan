package dev.nizav.documentscanner.camera;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfDouble;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

public final class FrameQualityEstimator implements AutoCloseable {
    private static final int MAX_EDGE = 480;
    private static final double MIN_LUMA = 42.0;
    private static final double MAX_LUMA = 224.0;
    private static final double MIN_LAPLACIAN_VARIANCE = 24.0;

    private final Mat scaled = new Mat();
    private final Mat laplacian = new Mat();
    private final MatOfDouble mean = new MatOfDouble();
    private final MatOfDouble stddev = new MatOfDouble();

    public FrameQuality measure(Mat gray) {
        double scale = Math.min(
                1.0,
                MAX_EDGE / (double) Math.max(gray.cols(), gray.rows())
        );

        if (scale < 1.0) {
            Imgproc.resize(
                    gray,
                    scaled,
                    new Size(
                            Math.max(1, Math.round(gray.cols() * scale)),
                            Math.max(1, Math.round(gray.rows() * scale))
                    ),
                    0.0,
                    0.0,
                    Imgproc.INTER_AREA
            );
        } else {
            gray.copyTo(scaled);
        }

        double luminance = Core.mean(scaled).val[0];

        Imgproc.Laplacian(
                scaled,
                laplacian,
                CvType.CV_64F,
                3,
                1.0,
                0.0
        );
        Core.meanStdDev(laplacian, mean, stddev);
        double sigma = stddev.toArray()[0];
        double variance = sigma * sigma;

        return new FrameQuality(
                variance,
                luminance,
                luminance >= MIN_LUMA && luminance <= MAX_LUMA,
                variance >= MIN_LAPLACIAN_VARIANCE
        );
    }

    @Override
    public void close() {
        scaled.release();
        laplacian.release();
        mean.release();
        stddev.release();
    }
}
