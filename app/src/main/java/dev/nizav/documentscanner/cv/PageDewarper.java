package dev.nizav.documentscanner.cv;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;

import org.opencv.android.Utils;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

public final class PageDewarper {
    private static final int ANALYSIS_MAX_EDGE = 720;
    private static final int ANALYSIS_BINS = 24;
    private static final int RENDER_STRIPS = 72;

    private PageDewarper() {
    }

    public static Result dewarp(Bitmap input) {
        if (input == null || input.isRecycled()) {
            return new Result(null, false, 0f);
        }

        double analysisScale = Math.min(
                1.0,
                ANALYSIS_MAX_EDGE
                        / (double) Math.max(input.getWidth(), input.getHeight())
        );
        int analysisWidth = Math.max(
                1,
                (int) Math.round(input.getWidth() * analysisScale)
        );
        int analysisHeight = Math.max(
                1,
                (int) Math.round(input.getHeight() * analysisScale)
        );

        Bitmap preview = Bitmap.createScaledBitmap(
                input,
                analysisWidth,
                analysisHeight,
                true
        );

        Mat rgba = new Mat();
        Mat gray = new Mat();
        Mat binary = new Mat();
        Mat lines = new Mat();
        Mat kernel = null;

        try {
            Utils.bitmapToMat(preview, rgba);
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY);
            Imgproc.GaussianBlur(gray, gray, new Size(3, 3), 0.0);

            Imgproc.threshold(
                    gray,
                    binary,
                    0.0,
                    255.0,
                    Imgproc.THRESH_BINARY_INV | Imgproc.THRESH_OTSU
            );

            int kernelWidth = Math.max(9, analysisWidth / 45);
            kernel = Imgproc.getStructuringElement(
                    Imgproc.MORPH_RECT,
                    new Size(kernelWidth, 1)
            );
            Imgproc.morphologyEx(
                    binary,
                    lines,
                    Imgproc.MORPH_OPEN,
                    kernel
            );

            ShiftEstimate estimate = estimateVerticalCurve(lines);
            if (!estimate.valid) {
                return new Result(
                        input.copy(Bitmap.Config.ARGB_8888, false),
                        false,
                        estimate.confidence
                );
            }

            Bitmap output = renderShiftedStrips(
                    input,
                    estimate.shifts,
                    analysisHeight
            );
            return new Result(output, true, estimate.confidence);
        } finally {
            if (preview != input && !preview.isRecycled()) {
                preview.recycle();
            }
            rgba.release();
            gray.release();
            binary.release();
            lines.release();
            if (kernel != null) kernel.release();
        }
    }

    private static ShiftEstimate estimateVerticalCurve(Mat mask) {
        int width = mask.cols();
        int height = mask.rows();
        if (width < ANALYSIS_BINS * 4 || height < 40) {
            return ShiftEstimate.invalid();
        }

        byte[] pixels = new byte[width * height];
        mask.get(0, 0, pixels);

        double[][] projections = new double[ANALYSIS_BINS][height];
        for (int bin = 0; bin < ANALYSIS_BINS; bin++) {
            int x0 = bin * width / ANALYSIS_BINS;
            int x1 = (bin + 1) * width / ANALYSIS_BINS;
            for (int y = 0; y < height; y++) {
                int offset = y * width;
                double sum = 0.0;
                for (int x = x0; x < x1; x++) {
                    sum += pixels[offset + x] & 0xff;
                }
                projections[bin][y] = sum;
            }
        }

        int center = ANALYSIS_BINS / 2;
        double[] reference = new double[height];
        for (int y = 0; y < height; y++) {
            reference[y] = (
                    projections[center - 1][y]
                            + projections[center][y]
                            + projections[center + 1][y]
            ) / 3.0;
        }

        int maxShift = Math.max(3, height / 24);
        float[] shifts = new float[ANALYSIS_BINS];
        double confidenceSum = 0.0;

        for (int bin = 0; bin < ANALYSIS_BINS; bin++) {
            Correlation best = bestShift(
                    reference,
                    projections[bin],
                    maxShift
            );
            shifts[bin] = best.shift;
            confidenceSum += best.score;
        }

        float[] smooth = new float[ANALYSIS_BINS];
        for (int i = 0; i < ANALYSIS_BINS; i++) {
            float sum = 0f;
            int count = 0;
            for (int j = Math.max(0, i - 2);
                 j <= Math.min(ANALYSIS_BINS - 1, i + 2);
                 j++) {
                sum += shifts[j];
                count++;
            }
            smooth[i] = sum / count;
        }

        float centerShift = smooth[center];
        float min = Float.MAX_VALUE;
        float max = -Float.MAX_VALUE;
        for (int i = 0; i < smooth.length; i++) {
            smooth[i] -= centerShift;
            min = Math.min(min, smooth[i]);
            max = Math.max(max, smooth[i]);
        }

        float amplitude = max - min;
        float confidence = (float) (confidenceSum / ANALYSIS_BINS);
        boolean valid = amplitude >= 1.75f && confidence >= 0.20f;

        return new ShiftEstimate(smooth, valid, confidence);
    }

    private static Correlation bestShift(
            double[] reference,
            double[] candidate,
            int maxShift
    ) {
        double bestScore = -1.0;
        int bestShift = 0;

        for (int shift = -maxShift; shift <= maxShift; shift++) {
            double dot = 0.0;
            double refSq = 0.0;
            double candidateSq = 0.0;

            int start = Math.max(0, -shift);
            int end = Math.min(reference.length, candidate.length - shift);

            for (int y = start; y < end; y++) {
                double a = reference[y];
                double b = candidate[y + shift];
                dot += a * b;
                refSq += a * a;
                candidateSq += b * b;
            }

            double denom = Math.sqrt(refSq * candidateSq);
            double score = denom > 1e-9 ? dot / denom : 0.0;
            if (score > bestScore) {
                bestScore = score;
                bestShift = shift;
            }
        }

        return new Correlation(bestShift, Math.max(0.0, bestScore));
    }

    private static Bitmap renderShiftedStrips(
            Bitmap input,
            float[] analysisShifts,
            int analysisHeight
    ) {
        Bitmap output = Bitmap.createBitmap(
                input.getWidth(),
                input.getHeight(),
                Bitmap.Config.ARGB_8888
        );
        Canvas canvas = new Canvas(output);
        canvas.drawColor(Color.WHITE);

        Paint paint = new Paint(
                Paint.ANTI_ALIAS_FLAG
                        | Paint.FILTER_BITMAP_FLAG
                        | Paint.DITHER_FLAG
        );

        float yScale = input.getHeight() / (float) analysisHeight;

        for (int strip = 0; strip < RENDER_STRIPS; strip++) {
            int x0 = strip * input.getWidth() / RENDER_STRIPS;
            int x1 = (strip + 1) * input.getWidth() / RENDER_STRIPS;
            if (x1 <= x0) continue;

            float normalizedX = (strip + 0.5f) / RENDER_STRIPS;
            float binPosition =
                    normalizedX * (analysisShifts.length - 1);
            int leftBin = Math.max(
                    0,
                    Math.min(
                            analysisShifts.length - 1,
                            (int) Math.floor(binPosition)
                    )
            );
            int rightBin = Math.min(
                    analysisShifts.length - 1,
                    leftBin + 1
            );
            float mix = binPosition - leftBin;
            float shift = analysisShifts[leftBin]
                    + (analysisShifts[rightBin]
                    - analysisShifts[leftBin]) * mix;

            // Correlation shift says where source content moved relative
            // to the center strip. Move the strip in the opposite direction.
            float destY = -shift * yScale;

            Rect src = new Rect(
                    Math.max(0, x0 - 1),
                    0,
                    Math.min(input.getWidth(), x1 + 1),
                    input.getHeight()
            );
            RectF dst = new RectF(
                    src.left,
                    destY,
                    src.right,
                    destY + input.getHeight()
            );
            canvas.drawBitmap(input, src, dst, paint);
        }

        return output;
    }

    public static final class Result {
        public final Bitmap bitmap;
        public final boolean applied;
        public final float confidence;

        Result(Bitmap bitmap, boolean applied, float confidence) {
            this.bitmap = bitmap;
            this.applied = applied;
            this.confidence = confidence;
        }
    }

    private static final class ShiftEstimate {
        final float[] shifts;
        final boolean valid;
        final float confidence;

        ShiftEstimate(float[] shifts, boolean valid, float confidence) {
            this.shifts = shifts;
            this.valid = valid;
            this.confidence = confidence;
        }

        static ShiftEstimate invalid() {
            return new ShiftEstimate(new float[ANALYSIS_BINS], false, 0f);
        }
    }

    private static final class Correlation {
        final int shift;
        final double score;

        Correlation(int shift, double score) {
            this.shift = shift;
            this.score = score;
        }
    }
}
