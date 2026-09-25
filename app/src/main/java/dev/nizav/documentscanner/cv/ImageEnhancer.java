package dev.nizav.documentscanner.cv;

import android.graphics.Bitmap;

import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.CLAHE;
import org.opencv.imgproc.Imgproc;
import org.opencv.photo.Photo;

import java.util.ArrayList;
import java.util.List;

public final class ImageEnhancer {
    public enum Filter {
        ORIGINAL,
        CLEAN_COLOR,
        CLEAN_PAPER,
        GRAYSCALE,
        BLACK_WHITE,
        FINGER_FIX
    }

    private ImageEnhancer() {
    }

    public static Bitmap apply(Bitmap input, Filter filter) {
        if (filter == Filter.ORIGINAL) {
            return input.copy(Bitmap.Config.ARGB_8888, false);
        }

        Mat rgba = new Mat();
        Mat work = new Mat();
        try {
            Utils.bitmapToMat(input, rgba);

            switch (filter) {
                case CLEAN_COLOR:
                    cleanColor(rgba, work);
                    break;
                case CLEAN_PAPER:
                    cleanPaper(rgba, work);
                    break;
                case GRAYSCALE:
                    grayscale(rgba, work);
                    break;
                case BLACK_WHITE:
                    blackWhite(rgba, work);
                    break;
                case FINGER_FIX:
                    conservativeFingerFix(rgba, work);
                    break;
                default:
                    rgba.copyTo(work);
                    break;
            }

            Bitmap output = Bitmap.createBitmap(
                    work.cols(),
                    work.rows(),
                    Bitmap.Config.ARGB_8888
            );
            Utils.matToBitmap(work, output);
            return output;
        } finally {
            rgba.release();
            work.release();
        }
    }

    private static void cleanColor(Mat rgba, Mat output) {
        Mat rgb = new Mat();
        Mat lab = new Mat();
        Mat sharp = new Mat();
        Mat blur = new Mat();
        List<Mat> channels = new ArrayList<>(3);

        try {
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB);
            Imgproc.cvtColor(rgb, lab, Imgproc.COLOR_RGB2Lab);
            Core.split(lab, channels);

            CLAHE clahe = Imgproc.createCLAHE(2.0, new Size(8, 8));
            try {
                clahe.apply(channels.get(0), channels.get(0));
            } finally {
                clahe.collectGarbage();
            }

            Core.merge(channels, lab);
            Imgproc.cvtColor(lab, rgb, Imgproc.COLOR_Lab2RGB);

            Imgproc.GaussianBlur(rgb, blur, new Size(0, 0), 1.0);
            Core.addWeighted(rgb, 1.28, blur, -0.28, 0.0, sharp);
            Imgproc.cvtColor(sharp, output, Imgproc.COLOR_RGB2RGBA);
        } finally {
            for (Mat channel : channels) {
                channel.release();
            }
            rgb.release();
            lab.release();
            sharp.release();
            blur.release();
        }
    }

    private static void cleanPaper(Mat rgba, Mat output) {
        Mat rgb = new Mat();
        Mat lab = new Mat();
        Mat background = new Mat();
        Mat normalized = new Mat();
        List<Mat> channels = new ArrayList<>(3);

        try {
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB);
            Imgproc.cvtColor(rgb, lab, Imgproc.COLOR_RGB2Lab);
            Core.split(lab, channels);

            double sigma = Math.max(
                    12.0,
                    Math.min(rgba.cols(), rgba.rows()) / 35.0
            );
            Imgproc.GaussianBlur(
                    channels.get(0),
                    background,
                    new Size(0, 0),
                    sigma
            );

            Core.max(background, new Scalar(1.0), background);
            Core.divide(
                    channels.get(0),
                    background,
                    normalized,
                    238.0
            );

            CLAHE clahe = Imgproc.createCLAHE(1.6, new Size(8, 8));
            try {
                clahe.apply(normalized, channels.get(0));
            } finally {
                clahe.collectGarbage();
            }

            // Pull weak chroma toward neutral paper without destroying
            // strong colored ink/photos.
            channels.get(1).convertTo(
                    channels.get(1),
                    -1,
                    0.82,
                    23.0
            );
            channels.get(2).convertTo(
                    channels.get(2),
                    -1,
                    0.82,
                    23.0
            );

            Core.merge(channels, lab);
            Imgproc.cvtColor(lab, rgb, Imgproc.COLOR_Lab2RGB);
            Imgproc.cvtColor(rgb, output, Imgproc.COLOR_RGB2RGBA);
        } finally {
            for (Mat channel : channels) {
                channel.release();
            }
            rgb.release();
            lab.release();
            background.release();
            normalized.release();
        }
    }

    private static void grayscale(Mat rgba, Mat output) {
        Mat gray = new Mat();
        try {
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY);
            Imgproc.cvtColor(gray, output, Imgproc.COLOR_GRAY2RGBA);
        } finally {
            gray.release();
        }
    }

    private static void blackWhite(Mat rgba, Mat output) {
        Mat gray = new Mat();
        Mat local = new Mat();
        try {
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY);
            Imgproc.GaussianBlur(gray, gray, new Size(3, 3), 0.0);
            Imgproc.adaptiveThreshold(
                    gray,
                    local,
                    255.0,
                    Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                    Imgproc.THRESH_BINARY,
                    31,
                    11.0
            );
            Imgproc.cvtColor(local, output, Imgproc.COLOR_GRAY2RGBA);
        } finally {
            gray.release();
            local.release();
        }
    }

    private static void conservativeFingerFix(Mat rgba, Mat output) {
        Mat rgb = new Mat();
        Mat ycrcb = new Mat();
        Mat skin = new Mat();
        Mat border = null;
        Mat mask = new Mat();
        Mat kernel = null;
        Mat repaired = new Mat();

        try {
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB);
            Imgproc.cvtColor(rgb, ycrcb, Imgproc.COLOR_RGB2YCrCb);

            Core.inRange(
                    ycrcb,
                    new Scalar(0, 133, 77),
                    new Scalar(255, 173, 127),
                    skin
            );

            border = Mat.zeros(
                    rgba.rows(),
                    rgba.cols(),
                    org.opencv.core.CvType.CV_8UC1
            );
            int thickness = Math.max(
                    12,
                    (int) Math.round(
                            Math.min(rgba.cols(), rgba.rows()) * 0.10
                    )
            );
            Imgproc.rectangle(
                    border,
                    new Point(0, 0),
                    new Point(rgba.cols() - 1, rgba.rows() - 1),
                    new Scalar(255),
                    thickness
            );

            Core.bitwise_and(skin, border, mask);

            kernel = Imgproc.getStructuringElement(
                    Imgproc.MORPH_ELLIPSE,
                    new Size(9, 9)
            );
            Imgproc.morphologyEx(
                    mask,
                    mask,
                    Imgproc.MORPH_CLOSE,
                    kernel
            );
            Imgproc.dilate(mask, mask, kernel);

            double ratio = Core.countNonZero(mask)
                    / (double) (rgba.rows() * rgba.cols());

            if (ratio >= 0.0004 && ratio <= 0.065) {
                double radius = Math.max(
                        3.0,
                        Math.min(rgba.cols(), rgba.rows()) * 0.006
                );
                Photo.inpaint(
                        rgb,
                        mask,
                        repaired,
                        radius,
                        Photo.INPAINT_TELEA
                );
                Imgproc.cvtColor(
                        repaired,
                        output,
                        Imgproc.COLOR_RGB2RGBA
                );
            } else {
                rgba.copyTo(output);
            }
        } finally {
            rgb.release();
            ycrcb.release();
            skin.release();
            if (border != null) border.release();
            mask.release();
            if (kernel != null) kernel.release();
            repaired.release();
        }
    }
}
