package dev.nizav.documentscanner.export;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.Environment;

import dev.nizav.documentscanner.data.ProjectRepository;
import dev.nizav.documentscanner.data.db.PageEntity;
import dev.nizav.documentscanner.data.db.ProjectEntity;
import dev.nizav.documentscanner.ocr.DocumentDataExtractor;
import dev.nizav.documentscanner.ocr.OcrEngine;
import dev.nizav.documentscanner.ocr.OcrLine;
import dev.nizav.documentscanner.util.BitmapUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class ProjectExporter {
    private static final int A4_SHORT = 595;
    private static final int A4_LONG = 842;
    private static final int MAX_EXPORT_EDGE = 2200;

    private ProjectExporter() {
    }

    public static ExportResult export(
            Context context,
            long projectId,
            boolean publishToMediaStore
    ) throws Exception {
        ProjectRepository repository = new ProjectRepository(context);
        ProjectEntity project = repository.getProject(projectId);
        List<PageEntity> pages = repository.listPages(projectId);

        if (project == null) {
            throw new IOException("Document no longer exists");
        }
        if (pages.isEmpty()) {
            throw new IOException("Document has no pages");
        }

        File root = context.getExternalFilesDir(
                Environment.DIRECTORY_DOCUMENTS
        );
        if (root == null) {
            root = new File(context.getFilesDir(), "documents");
        }

        File outputDir = new File(
                new File(root, "CameraScan"),
                safeName(project.name) + "_" + System.currentTimeMillis()
        );
        if (!outputDir.mkdirs()) {
            throw new IOException("Unable to create export directory");
        }

        List<File> exportedPages = new ArrayList<>(pages.size());
        for (int i = 0; i < pages.size(); i++) {
            File target = new File(
                    outputDir,
                    String.format(
                            java.util.Locale.US,
                            "page_%04d.jpg",
                            i + 1
                    )
            );
            copyFile(new File(pages.get(i).filePath), target);
            exportedPages.add(target);
        }

        File pdf = new File(outputDir, "document_searchable.pdf");
        writeSearchablePdf(
                pages,
                exportedPages,
                pdf,
                repository
        );

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
            List<PageEntity> pageEntities,
            List<File> pageFiles,
            File output,
            ProjectRepository repository
    ) throws Exception {
        PdfDocument document = new PdfDocument();

        try (OcrEngine ocr = new OcrEngine()) {
            int pageNumber = 1;

            for (int index = 0; index < pageFiles.size(); index++) {
                PageEntity entity = pageEntities.get(index);
                File file = pageFiles.get(index);
                Bitmap bitmap = BitmapUtils.decodeOriented(
                        file,
                        MAX_EXPORT_EDGE
                );

                try {
                    List<OcrLine> lines;
                    try {
                        lines = ocr.recognize(bitmap);

                        StringBuilder recognized = new StringBuilder();
                        for (OcrLine line : lines) {
                            if (recognized.length() > 0) {
                                recognized.append('\n');
                            }
                            recognized.append(line.text);
                        }

                        String text = recognized.toString().trim();
                        repository.updateOcr(
                                entity.id,
                                text,
                                DocumentDataExtractor.extractJson(text)
                        );
                    } catch (Exception ignored) {
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

                    Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                    textPaint.setColor(Color.BLACK);

                    for (OcrLine line : lines) {
                        float textSize = Math.max(
                                4f,
                                line.bounds.height() * scale * 0.82f
                        );
                        textPaint.setTextSize(textSize);
                        canvas.drawText(
                                line.text,
                                left + line.bounds.left * scale,
                                top + line.bounds.bottom * scale,
                                textPaint
                        );
                    }

                    Paint bitmapPaint = new Paint(
                            Paint.ANTI_ALIAS_FLAG
                                    | Paint.FILTER_BITMAP_FLAG
                                    | Paint.DITHER_FLAG
                    );
                    canvas.drawBitmap(
                            bitmap,
                            null,
                            new RectF(
                                    left,
                                    top,
                                    left + drawWidth,
                                    top + drawHeight
                            ),
                            bitmapPaint
                    );
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

    private static void copyFile(File source, File target) throws IOException {
        try (FileInputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        }
    }

    private static String safeName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "document";
        }
        String cleaned = name.trim().replaceAll("[^A-Za-z0-9._-]+", "_");
        return cleaned.length() > 48
                ? cleaned.substring(0, 48)
                : cleaned;
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
