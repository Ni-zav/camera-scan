package dev.nizav.documentscanner.cv;

import org.opencv.core.Point;

public final class Boundary8 {
    public static final int TL = 0;
    public static final int TM = 1;
    public static final int TR = 2;
    public static final int RM = 3;
    public static final int BR = 4;
    public static final int BM = 5;
    public static final int BL = 6;
    public static final int LM = 7;

    private final Point[] points;

    public Boundary8(Point[] points) {
        if (points == null || points.length != 8) {
            throw new IllegalArgumentException("Boundary8 needs eight points");
        }
        this.points = new Point[8];
        for (int i = 0; i < 8; i++) {
            this.points[i] = new Point(points[i].x, points[i].y);
        }
    }

    public static Boundary8 fromQuad(Quad quad) {
        Point tl = quad.get(0);
        Point tr = quad.get(1);
        Point br = quad.get(2);
        Point bl = quad.get(3);
        return new Boundary8(new Point[]{
                tl,
                midpoint(tl, tr),
                tr,
                midpoint(tr, br),
                br,
                midpoint(br, bl),
                bl,
                midpoint(bl, tl)
        });
    }

    public static Boundary8 fullFrame() {
        return new Boundary8(new Point[]{
                new Point(0, 0),
                new Point(0.5, 0),
                new Point(1, 0),
                new Point(1, 0.5),
                new Point(1, 1),
                new Point(0.5, 1),
                new Point(0, 1),
                new Point(0, 0.5)
        });
    }

    public Point get(int index) {
        Point p = points[index];
        return new Point(p.x, p.y);
    }

    public float[] toFloatArray() {
        float[] out = new float[16];
        for (int i = 0; i < 8; i++) {
            out[i * 2] = (float) points[i].x;
            out[i * 2 + 1] = (float) points[i].y;
        }
        return out;
    }

    public Boundary8 denormalized(int width, int height) {
        Point[] out = new Point[8];
        for (int i = 0; i < 8; i++) {
            out[i] = new Point(
                    points[i].x * width,
                    points[i].y * height
            );
        }
        return new Boundary8(out);
    }

    private static Point midpoint(Point a, Point b) {
        return new Point(
                (a.x + b.x) * 0.5,
                (a.y + b.y) * 0.5
        );
    }
}
