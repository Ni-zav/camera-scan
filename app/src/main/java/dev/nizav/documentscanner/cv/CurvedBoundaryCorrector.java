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
    private static final int VALIDATION_STEPS = 8;
    private static final double MIN_NORMALIZED_AREA = 0.02;
    private static final double MIN_LOCAL_JACOBIAN = 0.00001;
    private static final double MAX_SURFACE_OVERSHOOT = 0.06;

    private CurvedBoundaryCorrector() {
    }

    public static boolean isValid(Boundary8 boundary) {
        Point tl = boundary.get(Boundary8.TL);
        Point tr = boundary.get(Boundary8.TR);
        Point br = boundary.get(Boundary8.BR);
        Point bl = boundary.get(Boundary8.BL);

        double signedArea =
                0.5 * (
                        tl.x * tr.y - tl.y * tr.x
                        + tr.x * br.y - tr.y * br.x
                        + br.x * bl.y - br.y * bl.x
                        + bl.x * tl.y - bl.y * tl.x
                );

        if (Math.abs(signedArea) < MIN_NORMALIZED_AREA) {
            return false;
        }

        if (edgeLength(
                boundary,
                Boundary8.TL,
                Boundary8.TM,
                Boundary8.TR
        ) < 0.08
                || edgeLength(
                        boundary,
                        Boundary8.TR,
                        Boundary8.RM,
                        Boundary8.BR
                ) < 0.08
                || edgeLength(
                        boundary,
                        Boundary8.BL,
                        Boundary8.BM,
                        Boundary8.BR
                ) < 0.08
                || edgeLength(
                        boundary,
                        Boundary8.TL,
                        Boundary8.LM,
                        Boundary8.BL
                ) < 0.08) {
            return false;
        }

        double expectedSign = Math.signum(signedArea);
        double step = 1.0 / VALIDATION_STEPS;

        for (int y = 0; y <= VALIDATION_STEPS; y++) {
            double v = y * step;
            for (int x = 0; x <= VALIDATION_STEPS; x++) {
                double u = x * step;
                Point point = surfacePoint(boundary, u, v);
                if (point.x < -MAX_SURFACE_OVERSHOOT
                        || point.x > 1.0 + MAX_SURFACE_OVERSHOOT
                        || point.y < -MAX_SURFACE_OVERSHOOT
                        || point.y > 1.0 + MAX_SURFACE_OVERSHOOT) {
                    return false;
                }

                if (x == VALIDATION_STEPS
                        || y == VALIDATION_STEPS) {
                    continue;
                }

                Point right = surfacePoint(boundary, u + step, v);
                Point down = surfacePoint(boundary, u, v + step);

                double duX = right.x - point.x;
                double duY = right.y - point.y;
                double dvX = down.x - point.x;
                double dvY = down.y - point.y;
                double jacobian = duX * dvY - duY * dvX;

                if (Math.abs(jacobian) < MIN_LOCAL_JACOBIAN
                        || Math.signum(jacobian) != expectedSign) {
                    return false;
                }
            }
        }

        return true;
    }

    public static Bitmap warp(Bitmap input, Boundary8 normalized) {
        if (!isValid(normalized)) {
            throw new IllegalArgumentException(
                    "Curved boundary folds or collapses"
            );
        }

        Boundary8 pixels = normalized.denormalized(
                input.getWidth(),
                input.getHeight()
        );

        int width = Math.max(
                1,
                (int) Math.round(Math.max(
                        edgeLength(
                                pixels,
                                Boundary8.TL,
                                Boundary8.TM,
                                Boundary8.TR
                        ),
                        edgeLength(
                                pixels,
                                Boundary8.BL,
                                Boundary8.BM,
                                Boundary8.BR
                        )
                ))
        );
        int height = Math.max(
                1,
                (int) Math.round(Math.max(
                        edgeLength(
                                pixels,
                                Boundary8.TL,
                                Boundary8.LM,
                                Boundary8.BL
                        ),
                        edgeLength(
                                pixels,
                                Boundary8.TR,
                                Boundary8.RM,
                                Boundary8.BR
                        )
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

    private static Point surfacePoint(
            Boundary8 boundary,
            double u,
            double v
    ) {
        Point tl = boundary.get(Boundary8.TL);
        Point tr = boundary.get(Boundary8.TR);
        Point br = boundary.get(Boundary8.BR);
        Point bl = boundary.get(Boundary8.BL);

        Point top = curvePoint(
                tl,
                boundary.get(Boundary8.TM),
                tr,
                u
        );
        Point bottom = curvePoint(
                bl,
                boundary.get(Boundary8.BM),
                br,
                u
        );
        Point left = curvePoint(
                tl,
                boundary.get(Boundary8.LM),
                bl,
                v
        );
        Point right = curvePoint(
                tr,
                boundary.get(Boundary8.RM),
                br,
                v
        );

        double oneMinusU = 1.0 - u;
        double oneMinusV = 1.0 - v;

        double bilinearX =
                oneMinusU * oneMinusV * tl.x
                        + u * oneMinusV * tr.x
                        + u * v * br.x
                        + oneMinusU * v * bl.x;
        double bilinearY =
                oneMinusU * oneMinusV * tl.y
                        + u * oneMinusV * tr.y
                        + u * v * br.y
                        + oneMinusU * v * bl.y;

        return new Point(
                oneMinusV * top.x
                        + v * bottom.x
                        + oneMinusU * left.x
                        + u * right.x
                        - bilinearX,
                oneMinusV * top.y
                        + v * bottom.y
                        + oneMinusU * left.y
                        + u * right.y
                        - bilinearY
        );
    }

    private static void fillCurve(
            Point start,
            Point midpointOnCurve,
            Point end,
            float[] x,
            float[] y
    ) {
        for (int i = 0; i < x.length; i++) {
            double t = x.length <= 1
                    ? 0.0
                    : i / (double) (x.length - 1);
            Point p = curvePoint(start, midpointOnCurve, end, t);
            x[i] = (float) p.x;
            y[i] = (float) p.y;
        }
    }

    private static Point curvePoint(
            Point start,
            Point midpointOnCurve,
            Point end,
            double t
    ) {
        return quadratic(
                start,
                controlFromMidpoint(start, midpointOnCurve, end),
                end,
                t
        );
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
