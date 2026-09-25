package dev.nizav.documentscanner;

import android.app.Application;
import android.util.Log;

import org.opencv.android.OpenCVLoader;

public final class ScannerApp extends Application {
    private static final String TAG = "ScannerApp";
    private static volatile boolean openCvReady;

    @Override
    public void onCreate() {
        super.onCreate();
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
}
