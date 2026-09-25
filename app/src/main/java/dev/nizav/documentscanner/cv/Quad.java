package dev.nizav.documentscanner.cv;

import org.opencv.core.Point;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public final class Quad {
    private final Point[] points;

    public Quad(Point topLeft, Point topRight, Point bottomRight, Point bottomLeft) {
        this.points = new Point[]{
                copy(topLeft), copy(topRight), copy(bottomRight), copy(bottomLeft)
        };
    }

    public static Quad fromUnordered(Point[] input) {
        if (input == null || input.length != 4) {
            throw new IllegalArgumentException("A quad needs exactly four points");
        }

        double cx = 0.0;
        double cy = 0.0;
        for (Point p : input) {
            cx += p.x;
            cy += p.y;
        }
        cx /= 4.0;
        cy /= 4.0;

        final double centerX = cx;
        final double centerY = cy;
        List<Point> ordered = new ArrayList<>(Arrays.asList(
                copy(input[0]), copy(input[1]), copy(input[2]), copy(input[3])
        ));
        ordered.sort(Comparator.comparingDouble(
                p -> Math.atan2(p.y - centerY, p.x - centerX)
        ));

        if (signedArea(ordered) < 0.0) {
            Point p1 = ordered.get(1);
            ordered.set(1, ordered.get(3));
            ordered.set(3, p1);
        }

        int start = 0;
        double best = Double.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            Point p = ordered.get(i);
            double sum = p.x + p.y;
            if (sum < best) {
                best = sum;
                start = i;
            }
        }

        Point[] rotated = new Point[4];
        for (int i = 0; i < 4; i++) {
            rotated[i] = ordered.get((start + i) % 4);
        }

        if (rotated[1].x < rotated[3].x) {
            Point temp = rotated[1];
            rotated[1] = rotated[3];
            rotated[3] = temp;
        }

        return new Quad(rotated[0], rotated[1], rotated[2], rotated[3]);
    }

    private static double signedArea(List<Point> pts) {
        double total = 0.0;
        for (int i = 0; i < 4; i++) {
            Point a = pts.get(i);
            Point b = pts.get((i + 1) % 4);
            total += a.x * b.y - b.x * a.y;
        }
        return total * 0.5;
    }

    public Quad normalized(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Invalid image size");
        }
        return new Quad(
                new Point(points[0].x / width, points[0].y / height),
                new Point(points[1].x / width, points[1].y / height),
                new Point(points[2].x / width, points[2].y / height),
                new Point(points[3].x / width, points[3].y / height)
        );
    }

    public Quad denormalized(int width, int height) {
        return new Quad(
                new Point(points[0].x * width, points[0].y * height),
                new Point(points[1].x * width, points[1].y * height),
                new Point(points[2].x * width, points[2].y * height),
                new Point(points[3].x * width, points[3].y * height)
        );
    }

    public Point get(int index) {
        return copy(points[index]);
    }

    public Point[] toArray() {
        return new Point[]{copy(points[0]), copy(points[1]), copy(points[2]), copy(points[3])};
    }

    public double area() {
        double total = 0.0;
        for (int i = 0; i < 4; i++) {
            Point a = points[i];
            Point b = points[(i + 1) % 4];
            total += a.x * b.y - b.x * a.y;
        }
        return Math.abs(total) * 0.5;
    }

    public float[] toFloatArray() {
        return new float[]{
                (float) points[0].x, (float) points[0].y,
                (float) points[1].x, (float) points[1].y,
                (float) points[2].x, (float) points[2].y,
                (float) points[3].x, (float) points[3].y
        };
    }

    public static Quad fromFloatArray(float[] values) {
        if (values == null || values.length != 8) {
            throw new IllegalArgumentException("Expected eight values");
        }
        return new Quad(
                new Point(values[0], values[1]),
                new Point(values[2], values[3]),
                new Point(values[4], values[5]),
                new Point(values[6], values[7])
        );
    }

    private static Point copy(Point p) {
        return new Point(p.x, p.y);
    }
}
