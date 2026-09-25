package dev.nizav.documentscanner.cv;

import android.graphics.Bitmap;

import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

public final class PerspectiveCorrector {
    private static final int MAX_OUTPUT_EDGE = 4096;

    private PerspectiveCorrector() {
    }

    public static Bitmap warp(Bitmap input, Quad normalizedQuad) {
        Quad pixelQuad = normalizedQuad.denormalized(input.getWidth(), input.getHeight());
        Point[] p = pixelQuad.toArray();

        double top = distance(p[0], p[1]);
        double bottom = distance(p[3], p[2]);
        double left = distance(p[0], p[3]);
        double right = distance(p[1], p[2]);

        int width = Math.max(1, (int) Math.round(Math.max(top, bottom)));
        int height = Math.max(1, (int) Math.round(Math.max(left, right)));

        double downscale = Math.min(
                1.0,
                MAX_OUTPUT_EDGE / (double) Math.max(width, height)
        );
        width = Math.max(1, (int) Math.round(width * downscale));
        height = Math.max(1, (int) Math.round(height * downscale));

        Mat src = new Mat();
        Mat dst = new Mat();
        Mat transform = null;
        MatOfPoint2f srcPts = new MatOfPoint2f(p);
        MatOfPoint2f dstPts = new MatOfPoint2f(
                new Point(0, 0),
                new Point(width - 1.0, 0),
                new Point(width - 1.0, height - 1.0),
                new Point(0, height - 1.0)
        );

        try {
            Utils.bitmapToMat(input, src);
            transform = Imgproc.getPerspectiveTransform(srcPts, dstPts);
            Imgproc.warpPerspective(
                    src,
                    dst,
                    transform,
                    new Size(width, height),
                    Imgproc.INTER_CUBIC,
                    Core.BORDER_REPLICATE
            );

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
            if (transform != null) transform.release();
            srcPts.release();
            dstPts.release();
        }
    }

    private static double distance(Point a, Point b) {
        return Math.hypot(a.x - b.x, a.y - b.y);
    }
}
