package dev.nizav.documentscanner.util;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;

import androidx.exifinterface.media.ExifInterface;

import dev.nizav.documentscanner.cv.DetectionResult;
import dev.nizav.documentscanner.cv.DocumentDetector;
import dev.nizav.documentscanner.cv.Quad;

import org.opencv.android.Utils;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;

import java.io.File;
import java.io.IOException;

public final class BitmapUtils {
    private BitmapUtils() {
    }

    public static Bitmap decodeOriented(File file, int maxEdge) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IOException("Unable to decode image bounds");
        }

        int sample = 1;
        int largest = Math.max(bounds.outWidth, bounds.outHeight);
        while (largest / (sample * 2) >= maxEdge) {
            sample *= 2;
        }

        BitmapFactory.Options decode = new BitmapFactory.Options();
        decode.inSampleSize = sample;
        decode.inPreferredConfig = Bitmap.Config.ARGB_8888;

        Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), decode);
        if (bitmap == null) {
            throw new IOException("Unable to decode image");
        }

        ExifInterface exif = new ExifInterface(file);
        int orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
        );

        Matrix matrix = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_ROTATE_90:
                matrix.postRotate(90f);
                break;
            case ExifInterface.ORIENTATION_ROTATE_180:
                matrix.postRotate(180f);
                break;
            case ExifInterface.ORIENTATION_ROTATE_270:
                matrix.postRotate(270f);
                break;
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                matrix.postScale(-1f, 1f);
                break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                matrix.postScale(1f, -1f);
                break;
            default:
                break;
        }

        if (matrix.isIdentity()) {
            return bitmap;
        }

        Bitmap oriented = Bitmap.createBitmap(
                bitmap,
                0,
                0,
                bitmap.getWidth(),
                bitmap.getHeight(),
                matrix,
                true
        );
        if (oriented != bitmap) {
            bitmap.recycle();
        }
        return oriented;
    }

    public static Quad detectDocument(Bitmap bitmap) {
        Mat rgba = new Mat();
        Mat gray = new Mat();
        try (DocumentDetector detector = new DocumentDetector()) {
            Utils.bitmapToMat(bitmap, rgba);
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY);
            DetectionResult result = detector.detect(gray, true);
            if (result != null) {
                return result.quad;
            }
        } finally {
            rgba.release();
            gray.release();
        }

        return Quad.fromFloatArray(new float[]{
                0.04f, 0.04f,
                0.96f, 0.04f,
                0.96f, 0.96f,
                0.04f, 0.96f
        });
    }
}
