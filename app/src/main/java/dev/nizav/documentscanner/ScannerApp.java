package dev.nizav.documentscanner;

import android.app.Application;
import android.util.Log;

import com.google.android.material.color.DynamicColors;

import org.opencv.android.OpenCVLoader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class ScannerApp extends Application {
    private static final String TAG = "ScannerApp";
    private static volatile boolean openCvReady;
    private static File crashDirectory;

    @Override
    public void onCreate() {
        super.onCreate();

        crashDirectory = new File(getFilesDir(), "diagnostics");
        if (!crashDirectory.exists()) {
            crashDirectory.mkdirs();
        }

        installCrashRecorder();
        DynamicColors.applyToActivitiesIfAvailable(this);

        openCvReady = OpenCVLoader.initLocal();
        if (!openCvReady) {
            Log.e(TAG, "OpenCV initialization failed");
        } else {
            Log.i(TAG, "OpenCV initialized");
        }
    }

    public static boolean isOpenCvReady() {
        return openCvReady;
    }

    public static void recordHandledFailure(
            String operation,
            Throwable error
    ) {
        writeFailure("handled-" + operation, error);
    }

    private void installCrashRecorder() {
        Thread.UncaughtExceptionHandler previous =
                Thread.getDefaultUncaughtExceptionHandler();

        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            writeFailure(
                    "uncaught-" + thread.getName(),
                    error
            );

            if (previous != null) {
                previous.uncaughtException(thread, error);
            }
        });
    }

    private static synchronized void writeFailure(
            String label,
            Throwable error
    ) {
        File root = crashDirectory;
        if (root == null) {
            return;
        }

        String timestamp = new SimpleDateFormat(
                "yyyyMMdd_HHmmss_SSS",
                Locale.US
        ).format(new Date());

        File output = new File(
                root,
                timestamp + "_" + safeName(label) + ".txt"
        );

        try (FileOutputStream stream = new FileOutputStream(output);
             PrintWriter writer = new PrintWriter(stream)) {
            writer.println("label=" + label);
            writer.println("thread=" + Thread.currentThread().getName());
            writer.println("time=" + System.currentTimeMillis());
            error.printStackTrace(writer);
        } catch (Exception ignored) {
            Log.e(TAG, "Unable to persist crash diagnostic", ignored);
        }
    }

    private static String safeName(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]+", "_");
    }
}
