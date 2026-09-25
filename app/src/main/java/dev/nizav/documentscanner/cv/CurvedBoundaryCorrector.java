package dev.nizav.documentscanner.cv;

import android.graphics.Bitmap;

import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.imgproc.Imgproc;

public final class CurvedBoundaryCorrector {
    private static final int MAX_OUTPUT_EDGE = 4096;
    private static final int MAP_BAND_HEIGHT = 128;
    private static final int ARC_SAMPLES = 24;

    private CurvedBoundaryCorrector() {
    }

    public static Bitmap warp(Bitmap input, Boundary8 normalized) {
        Boundary8 pixels = normalized.denormalized(
                input.getWidth(),
                input.getHeight()
        );

        int width = Math.max(
                1,
                (int) Math.round(Math.max(
                        edgeLength(pixels, Boundary8.TL, Boundary8.TM, Boundary8.TR),
                        edgeLength(pixels, Boundary8.BL, Boundary8.BM, Boundary8.BR)
                ))
        );
        int height = Math.max(
                1,
                (int) Math.round(Math.max(
                        edgeLength(pixels, Boundary8.TL, Boundary8.LM, Boundary8.BL),
                        edgeLength(pixels, Boundary8.TR, Boundary8.RM, Boundary8.BR)
                ))
        );

        double scale = Math.min(
                1.0,
                MAX_OUTPUT_EDGE / (double) Math.max(width, height)
        );
        width = Math.max(1, (int) Math.round(width * scale));
        height = Math.max(1, (int) Math.round(height * scale));

        Point tl = pixels.get(Boundary8.TL);
        Point tr = pixels.get(Boundary8.TR);
        Point br = pixels.get(Boundary8.BR);
        Point bl = pixels.get(Boundary8.BL);

        float[] topX = new float[width];
        float[] topY = new float[width];
        float[] bottomX = new float[width];
        float[] bottomY = new float[width];
        fillCurve(
                pixels.get(Boundary8.TL),
                pixels.get(Boundary8.TM),
                pixels.get(Boundary8.TR),
                topX,
                topY
        );
        fillCurve(
                pixels.get(Boundary8.BL),
                pixels.get(Boundary8.BM),
                pixels.get(Boundary8.BR),
                bottomX,
                bottomY
        );

        float[] leftX = new float[height];
        float[] leftY = new float[height];
        float[] rightX = new float[height];
        float[] rightY = new float[height];
        fillCurve(
                pixels.get(Boundary8.TL),
                pixels.get(Boundary8.LM),
                pixels.get(Boundary8.BL),
                leftX,
                leftY
        );
        fillCurve(
                pixels.get(Boundary8.TR),
                pixels.get(Boundary8.RM),
                pixels.get(Boundary8.BR),
                rightX,
                rightY
        );

        Mat src = new Mat();
        Mat dst = new Mat(height, width, CvType.CV_8UC4);
        Mat mapX = new Mat();
        Mat mapY = new Mat();
        Mat band = new Mat();

        try {
            Utils.bitmapToMat(input, src);

            for (int y0 = 0; y0 < height; y0 += MAP_BAND_HEIGHT) {
                int rows = Math.min(MAP_BAND_HEIGHT, height - y0);
                float[] xMap = new float[rows * width];
                float[] yMap = new float[rows * width];

                for (int localY = 0; localY < rows; localY++) {
                    int y = y0 + localY;
                    float v = height <= 1
                            ? 0f
                            : y / (float) (height - 1);
                    float oneMinusV = 1f - v;

                    for (int x = 0; x < width; x++) {
                        float u = width <= 1
                                ? 0f
                                : x / (float) (width - 1);
                        float oneMinusU = 1f - u;

                        float bilinearX =
                                oneMinusU * oneMinusV * (float) tl.x
                                + u * oneMinusV * (float) tr.x
                                + u * v * (float) br.x
                                + oneMinusU * v * (float) bl.x;

                        float bilinearY =
                                oneMinusU * oneMinusV * (float) tl.y
                                + u * oneMinusV * (float) tr.y
                                + u * v * (float) br.y
                                + oneMinusU * v * (float) bl.y;

                        int index = localY * width + x;
                        xMap[index] =
                                oneMinusV * topX[x]
                                + v * bottomX[x]
                                + oneMinusU * leftX[y]
                                + u * rightX[y]
                                - bilinearX;

                        yMap[index] =
                                oneMinusV * topY[x]
                                + v * bottomY[x]
                                + oneMinusU * leftY[y]
                                + u * rightY[y]
                                - bilinearY;
                    }
                }

                mapX.create(rows, width, CvType.CV_32FC1);
                mapY.create(rows, width, CvType.CV_32FC1);
                mapX.put(0, 0, xMap);
                mapY.put(0, 0, yMap);

                Imgproc.remap(
                        src,
                        band,
                        mapX,
                        mapY,
                        Imgproc.INTER_CUBIC,
                        Core.BORDER_REPLICATE
                );
                band.copyTo(dst.rowRange(y0, y0 + rows));
            }

            Bitmap output = Bitmap.createBitmap(
                    width,
                    height,
                    Bitmap.Config.ARGB_8888
            );
            Utils.matToBitmap(dst, output);
            return output;
        } finally {
            src.release();
            dst.release();
            mapX.release();
            mapY.release();
            band.release();
        }
    }

    private static void fillCurve(
            Point start,
            Point midpointOnCurve,
            Point end,
            float[] x,
            float[] y
    ) {
        Point control = controlFromMidpoint(
                start,
                midpointOnCurve,
                end
        );

        for (int i = 0; i < x.length; i++) {
            double t = x.length <= 1
                    ? 0.0
                    : i / (double) (x.length - 1);
            Point p = quadratic(start, control, end, t);
            x[i] = (float) p.x;
            y[i] = (float) p.y;
        }
    }

    private static double edgeLength(
            Boundary8 boundary,
            int startIndex,
            int midpointIndex,
            int endIndex
    ) {
        Point start = boundary.get(startIndex);
        Point mid = boundary.get(midpointIndex);
        Point end = boundary.get(endIndex);
        Point control = controlFromMidpoint(start, mid, end);

        double total = 0.0;
        Point previous = start;
        for (int i = 1; i <= ARC_SAMPLES; i++) {
            double t = i / (double) ARC_SAMPLES;
            Point current = quadratic(start, control, end, t);
            total += Math.hypot(
                    current.x - previous.x,
                    current.y - previous.y
            );
            previous = current;
        }
        return total;
    }

    private static Point controlFromMidpoint(
            Point start,
            Point midpoint,
            Point end
    ) {
        return new Point(
                2.0 * midpoint.x - 0.5 * (start.x + end.x),
                2.0 * midpoint.y - 0.5 * (start.y + end.y)
        );
    }

    private static Point quadratic(
            Point start,
            Point control,
            Point end,
            double t
    ) {
        double omt = 1.0 - t;
        return new Point(
                omt * omt * start.x
                        + 2.0 * omt * t * control.x
                        + t * t * end.x,
                omt * omt * start.y
                        + 2.0 * omt * t * control.y
                        + t * t * end.y
        );
    }
}
