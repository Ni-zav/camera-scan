package dev.nizav.documentscanner.cv;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class DocumentDetector implements AutoCloseable {
    private static final int LIVE_MAX_EDGE = 900;
    private static final int ACCURATE_MAX_EDGE = 1600;
    private static final double MIN_AREA_RATIO = 0.10;

    private final Mat scaled = new Mat();
    private final Mat blurred = new Mat();
    private final Mat edges = new Mat();
    private final Mat morphed = new Mat();
    private final Mat thresholded = new Mat();
    private final Mat houghLines = new Mat();
    private final Mat kernel = Imgproc.getStructuringElement(
            Imgproc.MORPH_RECT, new Size(5, 5)
    );

    public DetectionResult detect(Mat gray, boolean accurate) {
        if (gray == null || gray.empty() || gray.type() != CvType.CV_8UC1) {
            return null;
        }

        int maxEdge = accurate ? ACCURATE_MAX_EDGE : LIVE_MAX_EDGE;
        double resizeScale = Math.min(
                1.0,
                maxEdge / (double) Math.max(gray.cols(), gray.rows())
        );

        if (resizeScale < 1.0) {
            Imgproc.resize(
                    gray,
                    scaled,
                    new Size(
                            Math.max(1, Math.round(gray.cols() * resizeScale)),
                            Math.max(1, Math.round(gray.rows() * resizeScale))
                    ),
                    0,
                    0,
                    Imgproc.INTER_AREA
            );
        } else {
            gray.copyTo(scaled);
        }

        Imgproc.GaussianBlur(scaled, blurred, new Size(5, 5), 0.0);

        Candidate best = detectFromCanny(50.0, 150.0);
        if (accurate) {
            best = better(best, detectFromCanny(30.0, 100.0));
            best = better(best, detectFromAdaptiveThreshold());
            if (best == null || best.score < 0.48) {
                best = better(best, detectFromHough());
            }
            if (best == null || best.score < 0.38) {
                best = better(best, detectFromForegroundSegmentation());
            }
        }

        if (best == null) {
            return null;
        }

        Quad normalized = best.quad.normalized(scaled.cols(), scaled.rows());
        return new DetectionResult(normalized, (float) best.score);
    }

    private Candidate detectFromCanny(double low, double high) {
        Imgproc.Canny(blurred, edges, low, high, 3, true);
        Imgproc.morphologyEx(edges, morphed, Imgproc.MORPH_CLOSE, kernel);
        return bestContour(morphed);
    }

    private Candidate detectFromAdaptiveThreshold() {
        Imgproc.adaptiveThreshold(
                blurred,
                thresholded,
                255.0,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                Imgproc.THRESH_BINARY_INV,
                31,
                9.0
        );
        Imgproc.morphologyEx(
                thresholded,
                morphed,
                Imgproc.MORPH_CLOSE,
                kernel
        );
        return bestContour(morphed);
    }

    private Candidate detectFromHough() {
        Imgproc.Canny(blurred, edges, 40.0, 130.0, 3, true);
        double minLength = Math.min(edges.cols(), edges.rows()) * 0.28;
        Imgproc.HoughLinesP(
                edges,
                houghLines,
                1.0,
                Math.PI / 180.0,
                70,
                minLength,
                Math.max(12.0, minLength * 0.08)
        );

        List<Line> lines = new ArrayList<>();
        for (int row = 0; row < houghLines.rows(); row++) {
            double[] v = houghLines.get(row, 0);
            if (v == null || v.length < 4) continue;
            Line line = Line.fromSegment(v[0], v[1], v[2], v[3]);
            if (line.length >= minLength) {
                lines.add(line);
            }
        }

        if (lines.size() < 4) {
            return null;
        }

        lines.sort(Comparator.comparingDouble((Line l) -> l.length).reversed());
        if (lines.size() > 24) {
            lines = new ArrayList<>(lines.subList(0, 24));
        }

        Line seed = lines.get(0);
        List<Line> familyA = new ArrayList<>();
        List<Line> familyB = new ArrayList<>();

        for (Line line : lines) {
            double diff = angleDifference(seed.angle, line.angle);
            if (diff <= Math.toRadians(24.0)) {
                familyA.add(line);
            } else if (Math.abs(diff - Math.PI / 2.0)
                    <= Math.toRadians(28.0)) {
                familyB.add(line);
            }
        }

        if (familyA.size() < 2 || familyB.size() < 2) {
            return null;
        }

        Line[] aPair = extremePair(familyA, edges.cols(), edges.rows());
        Line[] bPair = extremePair(familyB, edges.cols(), edges.rows());
        if (aPair == null || bPair == null) {
            return null;
        }

        Point[] intersections = new Point[]{
                intersection(aPair[0], bPair[0]),
                intersection(aPair[0], bPair[1]),
                intersection(aPair[1], bPair[1]),
                intersection(aPair[1], bPair[0])
        };

        for (Point p : intersections) {
            if (p == null || !insideExpanded(p, edges.cols(), edges.rows())) {
                return null;
            }
        }

        Quad quad = Quad.fromUnordered(intersections);
        double frameArea = (double) edges.cols() * edges.rows();
        double areaRatio = quad.area() / frameArea;
        if (areaRatio < MIN_AREA_RATIO) {
            return null;
        }

        double angle = rightAngleScore(quad);
        double score = clamp01(areaRatio) * 0.68 + angle * 0.32;
        return score >= 0.26 ? new Candidate(quad, score) : null;
    }

    private Candidate detectFromForegroundSegmentation() {
        Mat color = new Mat();
        Mat mask = new Mat();
        Mat backgroundModel = new Mat();
        Mat foregroundModel = new Mat();
        Mat foreground = new Mat();
        Mat probableForeground = new Mat();
        Mat combined = new Mat();
        Mat clean = new Mat();

        try {
            Imgproc.cvtColor(blurred, color, Imgproc.COLOR_GRAY2BGR);

            int insetX = Math.max(2, color.cols() / 35);
            int insetY = Math.max(2, color.rows() / 35);
            int width = color.cols() - insetX * 2;
            int height = color.rows() - insetY * 2;
            if (width < 20 || height < 20) {
                return null;
            }

            Rect foregroundRect = new Rect(
                    insetX,
                    insetY,
                    width,
                    height
            );

            Imgproc.grabCut(
                    color,
                    mask,
                    foregroundRect,
                    backgroundModel,
                    foregroundModel,
                    2,
                    Imgproc.GC_INIT_WITH_RECT
            );

            Core.inRange(
                    mask,
                    new Scalar(Imgproc.GC_FGD),
                    new Scalar(Imgproc.GC_FGD),
                    foreground
            );
            Core.inRange(
                    mask,
                    new Scalar(Imgproc.GC_PR_FGD),
                    new Scalar(Imgproc.GC_PR_FGD),
                    probableForeground
            );
            Core.bitwise_or(
                    foreground,
                    probableForeground,
                    combined
            );

            Imgproc.morphologyEx(
                    combined,
                    clean,
                    Imgproc.MORPH_CLOSE,
                    kernel
            );

            Candidate candidate = bestContour(clean);
            if (candidate == null) {
                return null;
            }

            // GrabCut is a last-resort proposal. Penalize it slightly so a
            // geometric contour/Hough result wins whenever both are plausible.
            return new Candidate(
                    candidate.quad,
                    candidate.score * 0.90
            );
        } catch (RuntimeException ignored) {
            return null;
        } finally {
            color.release();
            mask.release();
            backgroundModel.release();
            foregroundModel.release();
            foreground.release();
            probableForeground.release();
            combined.release();
            clean.release();
        }
    }

    private static Line[] extremePair(
            List<Line> family,
            int width,
            int height
    ) {
        double cx = width * 0.5;
        double cy = height * 0.5;
        Line minLine = null;
        Line maxLine = null;
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;

        for (Line line : family) {
            double signed = line.a * cx + line.b * cy + line.c;
            if (signed < min) {
                min = signed;
                minLine = line;
            }
            if (signed > max) {
                max = signed;
                maxLine = line;
            }
        }

        if (minLine == null || maxLine == null || minLine == maxLine) {
            return null;
        }
        double separation = Math.abs(max - min);
        if (separation < Math.min(width, height) * 0.18) {
            return null;
        }
        return new Line[]{minLine, maxLine};
    }

    private Candidate bestContour(Mat binary) {
        Mat scratch = binary.clone();
        Mat hierarchy = new Mat();
        List<MatOfPoint> contours = new ArrayList<>();
        try {
            Imgproc.findContours(
                    scratch,
                    contours,
                    hierarchy,
                    Imgproc.RETR_LIST,
                    Imgproc.CHAIN_APPROX_SIMPLE
            );

            double frameArea = (double) binary.cols() * binary.rows();
            Candidate best = null;

            for (MatOfPoint contour : contours) {
                double contourArea = Imgproc.contourArea(contour);
                double areaRatio = contourArea / frameArea;
                if (areaRatio < MIN_AREA_RATIO) {
                    continue;
                }

                MatOfPoint2f curve = new MatOfPoint2f(contour.toArray());
                try {
                    double perimeter = Imgproc.arcLength(curve, true);
                    double[] epsilons = {0.015, 0.020, 0.030};

                    for (double epsilonFactor : epsilons) {
                        MatOfPoint2f approx = new MatOfPoint2f();
                        try {
                            Imgproc.approxPolyDP(
                                    curve,
                                    approx,
                                    epsilonFactor * perimeter,
                                    true
                            );
                            Point[] corners = approx.toArray();
                            if (corners.length != 4) {
                                continue;
                            }

                            MatOfPoint polygon = new MatOfPoint(corners);
                            boolean convex;
                            try {
                                convex = Imgproc.isContourConvex(polygon);
                            } finally {
                                polygon.release();
                            }
                            if (!convex) {
                                continue;
                            }

                            Quad quad = Quad.fromUnordered(corners);
                            double quadArea = quad.area();
                            if (quadArea <= 1.0) {
                                continue;
                            }

                            double minSide = minimumSide(quad);
                            double minRequired = Math.min(
                                    binary.cols(),
                                    binary.rows()
                            ) * 0.10;
                            if (minSide < minRequired) {
                                continue;
                            }

                            double angle = rightAngleScore(quad);
                            double rectangularity =
                                    clamp01(contourArea / quadArea);
                            double visibleArea =
                                    clamp01(quadArea / frameArea);

                            double score =
                                    visibleArea * 0.58 +
                                    angle * 0.27 +
                                    rectangularity * 0.15;

                            if (best == null || score > best.score) {
                                best = new Candidate(quad, score);
                            }
                        } finally {
                            approx.release();
                        }
                    }
                } finally {
                    curve.release();
                }
            }

            return best != null && best.score >= 0.24 ? best : null;
        } finally {
            for (MatOfPoint contour : contours) {
                contour.release();
            }
            hierarchy.release();
            scratch.release();
        }
    }

    private static Candidate better(Candidate a, Candidate b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.score >= b.score ? a : b;
    }

    private static double rightAngleScore(Quad quad) {
        Point[] p = quad.toArray();
        double totalAbsCos = 0.0;

        for (int i = 0; i < 4; i++) {
            Point prev = p[(i + 3) % 4];
            Point cur = p[i];
            Point next = p[(i + 1) % 4];

            double ax = prev.x - cur.x;
            double ay = prev.y - cur.y;
            double bx = next.x - cur.x;
            double by = next.y - cur.y;

            double denom = Math.hypot(ax, ay) * Math.hypot(bx, by);
            if (denom < 1e-6) {
                return 0.0;
            }

            totalAbsCos += Math.abs((ax * bx + ay * by) / denom);
        }

        return clamp01(1.0 - totalAbsCos / 4.0);
    }

    private static double minimumSide(Quad quad) {
        Point[] p = quad.toArray();
        double min = Double.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            Point a = p[i];
            Point b = p[(i + 1) % 4];
            min = Math.min(min, Math.hypot(a.x - b.x, a.y - b.y));
        }
        return min;
    }

    private static Point intersection(Line first, Line second) {
        double determinant = first.a * second.b - second.a * first.b;
        if (Math.abs(determinant) < 1e-6) {
            return null;
        }
        double x = (first.b * second.c - second.b * first.c)
                / determinant;
        double y = (second.a * first.c - first.a * second.c)
                / determinant;
        return new Point(x, y);
    }

    private static boolean insideExpanded(Point p, int width, int height) {
        double mx = width * 0.12;
        double my = height * 0.12;
        return p.x >= -mx
                && p.x <= width + mx
                && p.y >= -my
                && p.y <= height + my;
    }

    private static double angleDifference(double a, double b) {
        double diff = Math.abs(a - b) % Math.PI;
        return Math.min(diff, Math.PI - diff);
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    @Override
    public void close() {
        scaled.release();
        blurred.release();
        edges.release();
        morphed.release();
        thresholded.release();
        houghLines.release();
        kernel.release();
    }

    private static final class Candidate {
        final Quad quad;
        final double score;

        Candidate(Quad quad, double score) {
            this.quad = quad;
            this.score = score;
        }
    }

    private static final class Line {
        final double a;
        final double b;
        final double c;
        final double angle;
        final double length;

        private Line(
                double a,
                double b,
                double c,
                double angle,
                double length
        ) {
            this.a = a;
            this.b = b;
            this.c = c;
            this.angle = angle;
            this.length = length;
        }

        static Line fromSegment(
                double x1,
                double y1,
                double x2,
                double y2
        ) {
            double dx = x2 - x1;
            double dy = y2 - y1;
            double length = Math.hypot(dx, dy);
            double a = dy / Math.max(length, 1e-6);
            double b = -dx / Math.max(length, 1e-6);
            double c = -(a * x1 + b * y1);
            double angle = Math.atan2(dy, dx);
            if (angle < 0.0) angle += Math.PI;
            return new Line(a, b, c, angle, length);
        }
    }
}
