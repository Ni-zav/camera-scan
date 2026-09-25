package dev.nizav.documentscanner.ocr;

import android.graphics.Bitmap;

import dev.nizav.documentscanner.util.BitmapUtils;

import java.io.File;
import java.util.List;

public final class OcrPipeline {
    private static final int MAX_OCR_EDGE = 2200;

    private OcrPipeline() {
    }

    public static Result recognizeFile(
            File file,
            OcrEngine engine
    ) throws Exception {
        Bitmap bitmap = BitmapUtils.decodeOriented(file, MAX_OCR_EDGE);
        try {
            List<OcrLine> lines = engine.recognize(bitmap);
            StringBuilder text = new StringBuilder();

            for (OcrLine line : lines) {
                if (text.length() > 0) {
                    text.append('\n');
                }
                text.append(line.text);
            }

            String value = text.toString().trim();
            return new Result(
                    value,
                    DocumentDataExtractor.extractJson(value)
            );
        } finally {
            bitmap.recycle();
        }
    }

    public static final class Result {
        public final String text;
        public final String dataJson;

        Result(String text, String dataJson) {
            this.text = text;
            this.dataJson = dataJson;
        }
    }
}
