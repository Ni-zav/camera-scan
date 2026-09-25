package dev.nizav.documentscanner.ocr;

import android.graphics.Rect;

public final class OcrLine {
    public final String text;
    public final Rect bounds;

    public OcrLine(String text, Rect bounds) {
        this.text = text;
        this.bounds = new Rect(bounds);
    }
}
