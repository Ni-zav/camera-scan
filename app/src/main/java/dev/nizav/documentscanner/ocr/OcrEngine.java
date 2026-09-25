package dev.nizav.documentscanner.ocr;

import android.graphics.Bitmap;
import android.graphics.Rect;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class OcrEngine implements AutoCloseable {
    private final TextRecognizer recognizer = TextRecognition.getClient(
            TextRecognizerOptions.DEFAULT_OPTIONS
    );

    public List<OcrLine> recognize(Bitmap bitmap) throws Exception {
        Text result = Tasks.await(
                recognizer.process(InputImage.fromBitmap(bitmap, 0)),
                30,
                TimeUnit.SECONDS
        );

        List<OcrLine> lines = new ArrayList<>();
        for (Text.TextBlock block : result.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                Rect box = line.getBoundingBox();
                String value = line.getText();
                if (box == null || value == null || value.trim().isEmpty()) {
                    continue;
                }
                lines.add(new OcrLine(value, box));
            }
        }
        return lines;
    }

    @Override
    public void close() {
        recognizer.close();
    }
}
