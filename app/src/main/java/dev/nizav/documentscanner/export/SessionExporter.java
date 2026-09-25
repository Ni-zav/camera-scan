package dev.nizav.documentscanner.export;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.Environment;

import dev.nizav.documentscanner.data.ScanSessionStore;
import dev.nizav.documentscanner.ocr.OcrEngine;
import dev.nizav.documentscanner.ocr.OcrLine;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class SessionExporter {
    private static final int A4_SHORT = 595;
    private static final int A4_LONG = 842;
    private static final int MAX_EXPORT_EDGE = 2200;

    private SessionExporter() {
    }

    public static ExportResult export(
            Context context,
            boolean publishToMediaStore
    ) throws Exception {
        List<File> sourcePages = ScanSessionStore.listPages(context);
        if (sourcePages.isEmpty()) {
            throw new IOException("The scan session has no pages");
        }

        File root = context.getExternalFilesDir(
                Environment.DIRECTORY_DOCUMENTS
        );
        if (root == null) {
            root = new File(context.getFilesDir(), "documents");
        }

        File outputDir = new File(
                new File(root, "PaperScanner"),
                "scan_" + System.currentTimeMillis()
        );
        if (!outputDir.mkdirs()) {
            throw new IOException("Unable to create export directory");
        }

        List<File> exportedPages = new ArrayList<>(sourcePages.size());
        for (int i = 0; i < sourcePages.size(); i++) {
            File target = new File(
                    outputDir,
                    String.format(
                            java.util.Locale.US,
                            "page_%04d.jpg",
                            i + 1
                    )
            );
            copyFile(sourcePages.get(i), target);
            exportedPages.add(target);
        }

        File pdf = new File(outputDir, "document_searchable.pdf");
        writeSearchablePdf(exportedPages, pdf);

        Uri publishedPdf = null;
        List<Uri> publishedImages = new ArrayList<>();
        if (publishToMediaStore) {
            publishedPdf = MediaStorePublisher.publishPdf(context, pdf);
            for (File page : exportedPages) {
                Uri uri = MediaStorePublisher.publishImage(context, page);
                if (uri != null) {
                    publishedImages.add(uri);
                }
            }
        }

        return new ExportResult(
                outputDir,
                pdf,
                exportedPages,
                publishedPdf,
                publishedImages
        );
    }

    private static void writeSearchablePdf(
            List<File> pages,
            File output
    ) throws Exception {
        PdfDocument document = new PdfDocument();
        try (OcrEngine ocr = new OcrEngine()) {
            int pageNumber = 1;

            for (File file : pages) {
                Bitmap bitmap = decodeSampled(file, MAX_EXPORT_EDGE);
                if (bitmap == null) {
                    continue;
                }

                try {
                    List<OcrLine> lines;
                    try {
                        lines = ocr.recognize(bitmap);
                    } catch (Exception ignored) {
                        // OCR failure must not make the scan unexportable.
                        lines = new ArrayList<>();
                    }

                    boolean landscape =
                            bitmap.getWidth() > bitmap.getHeight();
                    int pageWidth = landscape ? A4_LONG : A4_SHORT;
                    int pageHeight = landscape ? A4_SHORT : A4_LONG;

                    PdfDocument.PageInfo info =
                            new PdfDocument.PageInfo.Builder(
                                    pageWidth,
                                    pageHeight,
                                    pageNumber++
                            ).create();

                    PdfDocument.Page page = document.startPage(info);
                    Canvas canvas = page.getCanvas();
                    canvas.drawColor(Color.WHITE);

                    float scale = Math.min(
                            pageWidth / (float) bitmap.getWidth(),
                            pageHeight / (float) bitmap.getHeight()
                    );
                    float drawWidth = bitmap.getWidth() * scale;
                    float drawHeight = bitmap.getHeight() * scale;
                    float left = (pageWidth - drawWidth) * 0.5f;
                    float top = (pageHeight - drawHeight) * 0.5f;
                    RectF target = new RectF(
                            left,
                            top,
                            left + drawWidth,
                            top + drawHeight
                    );

                    // Draw selectable OCR text first, then fully cover it with
                    // the page raster. The visual PDF stays identical to the
                    // scan while text extraction/search still sees text ops.
                    Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                    textPaint.setColor(Color.BLACK);

                    for (OcrLine line : lines) {
                        float textSize = Math.max(
                                4f,
                                line.bounds.height() * scale * 0.82f
                        );
                        textPaint.setTextSize(textSize);
                        float x = left + line.bounds.left * scale;
                        float baseline =
                                top + line.bounds.bottom * scale;
                        canvas.drawText(
                                line.text,
                                x,
                                baseline,
                                textPaint
                        );
                    }

                    Paint bitmapPaint = new Paint(
                            Paint.ANTI_ALIAS_FLAG
                                    | Paint.FILTER_BITMAP_FLAG
                                    | Paint.DITHER_FLAG
                    );
                    canvas.drawBitmap(bitmap, null, target, bitmapPaint);
                    document.finishPage(page);
                } finally {
                    bitmap.recycle();
                }
            }

            try (FileOutputStream stream = new FileOutputStream(output)) {
                document.writeTo(stream);
            }
        } finally {
            document.close();
        }
    }

    private static Bitmap decodeSampled(File file, int maxEdge) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);

        int largest = Math.max(bounds.outWidth, bounds.outHeight);
        int sample = 1;
        while (largest / (sample * 2) >= maxEdge) {
            sample *= 2;
        }

        BitmapFactory.Options decode = new BitmapFactory.Options();
        decode.inSampleSize = sample;
        decode.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeFile(file.getAbsolutePath(), decode);
    }

    private static void copyFile(File source, File target)
            throws IOException {
        try (FileInputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        }
    }

    public static final class ExportResult {
        public final File directory;
        public final File pdf;
        public final List<File> pages;
        public final Uri publishedPdf;
        public final List<Uri> publishedImages;

        ExportResult(
                File directory,
                File pdf,
                List<File> pages,
                Uri publishedPdf,
                List<Uri> publishedImages
        ) {
            this.directory = directory;
            this.pdf = pdf;
            this.pages = pages;
            this.publishedPdf = publishedPdf;
            this.publishedImages = publishedImages;
        }
    }
}
