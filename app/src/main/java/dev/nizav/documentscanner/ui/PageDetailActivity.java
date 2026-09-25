package dev.nizav.documentscanner.ui;

import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;

import dev.nizav.documentscanner.R;
import dev.nizav.documentscanner.data.ProjectRepository;
import dev.nizav.documentscanner.data.db.PageEntity;
import dev.nizav.documentscanner.ocr.DocumentDataExtractor;
import dev.nizav.documentscanner.ocr.OcrEngine;
import dev.nizav.documentscanner.ocr.OcrPipeline;
import dev.nizav.documentscanner.util.BitmapUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class PageDetailActivity extends MaterialMotionActivity {
    public static final String EXTRA_PAGE_ID = "page_id";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private long pageId;
    private ProjectRepository repository;
    private ImageView imageView;
    private TextInputEditText textInput;
    private TextView dataView;
    private TextView statusView;
    private MaterialButton runOcrButton;
    private MaterialButton saveTextButton;
    private Bitmap displayedBitmap;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_page_detail);

        pageId = getIntent().getLongExtra(EXTRA_PAGE_ID, -1L);
        if (pageId <= 0L) {
            finish();
            return;
        }

        repository = new ProjectRepository(this);
        imageView = findViewById(R.id.pageImage);
        textInput = findViewById(R.id.pageOcrText);
        dataView = findViewById(R.id.pageData);
        statusView = findViewById(R.id.pageTextStatus);
        runOcrButton = findViewById(R.id.runPageOcrButton);
        saveTextButton = findViewById(R.id.savePageTextButton);

        MaterialToolbar toolbar = findViewById(R.id.pageDetailToolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        runOcrButton.setOnClickListener(v -> runOcr());
        saveTextButton.setOnClickListener(v -> saveEditedText());
        findViewById(R.id.deletePageButton).setOnClickListener(
                v -> confirmDelete()
        );

        loadPage();
    }

    private void loadPage() {
        worker.execute(() -> {
            PageEntity page = repository.getPage(pageId);
            if (page == null) {
                runOnUiThread(this::finish);
                return;
            }

            Bitmap bitmap = null;
            try {
                bitmap = BitmapUtils.decodeOriented(
                        new File(page.filePath),
                        2400
                );
            } catch (Exception ignored) {
            }

            Bitmap finalBitmap = bitmap;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    if (finalBitmap != null) finalBitmap.recycle();
                    return;
                }

                if (displayedBitmap != null
                        && displayedBitmap != finalBitmap
                        && !displayedBitmap.isRecycled()) {
                    displayedBitmap.recycle();
                }
                displayedBitmap = finalBitmap;
                if (finalBitmap != null) {
                    imageView.setImageBitmap(finalBitmap);
                }

                textInput.setText(page.ocrText == null ? "" : page.ocrText);
                renderData(page.ocrDataJson);
                statusView.setText(
                        page.ocrUpdatedAt > 0
                                ? R.string.text_ready
                                : R.string.text_not_indexed
                );
            });
        });
    }

    private void runOcr() {
        setBusy(true);
        worker.execute(() -> {
            PageEntity page = repository.getPage(pageId);
            if (page == null) {
                runOnUiThread(this::finish);
                return;
            }

            try (OcrEngine engine = new OcrEngine()) {
                OcrPipeline.Result result = OcrPipeline.recognizeFile(
                        new File(page.filePath),
                        engine
                );
                repository.updateOcr(
                        page.id,
                        result.text,
                        result.dataJson
                );

                runOnUiThread(() -> {
                    setBusy(false);
                    textInput.setText(result.text);
                    renderData(result.dataJson);
                    statusView.setText(R.string.text_ready);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    setBusy(false);
                    Toast.makeText(
                            this,
                            getString(R.string.ocr_failed, e.getMessage()),
                            Toast.LENGTH_LONG
                    ).show();
                });
            }
        });
    }

    private void saveEditedText() {
        String text = textInput.getText() == null
                ? ""
                : textInput.getText().toString().trim();

        setBusy(true);
        worker.execute(() -> {
            String data = DocumentDataExtractor.extractJson(text);
            repository.updateOcr(pageId, text, data);
            runOnUiThread(() -> {
                setBusy(false);
                renderData(data);
                statusView.setText(
                        text.isEmpty()
                                ? R.string.text_not_indexed
                                : R.string.text_ready
                );
            });
        });
    }

    private void confirmDelete() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.delete_page)
                .setMessage(R.string.delete_page_warning)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.delete_page, (dialog, which) ->
                        worker.execute(() -> {
                            repository.deletePage(pageId);
                            runOnUiThread(this::finish);
                        })
                )
                .show();
    }

    private void renderData(String json) {
        if (json == null || json.trim().isEmpty()) {
            dataView.setText(R.string.no_detected_data);
            return;
        }

        try {
            JSONObject root = new JSONObject(json);
            StringBuilder out = new StringBuilder();
            appendArray(out, R.string.detected_dates, root.optJSONArray("dates"));
            appendArray(out, R.string.detected_amounts, root.optJSONArray("amounts"));
            appendArray(out, R.string.detected_emails, root.optJSONArray("emails"));
            appendArray(out, R.string.detected_phones, root.optJSONArray("phones"));

            dataView.setText(
                    out.length() == 0
                            ? getString(R.string.no_detected_data)
                            : out.toString().trim()
            );
        } catch (Exception e) {
            dataView.setText(R.string.no_detected_data);
        }
    }

    private void appendArray(
            StringBuilder out,
            int labelRes,
            JSONArray array
    ) {
        if (array == null || array.length() == 0) {
            return;
        }
        if (out.length() > 0) out.append("\n\n");
        out.append(getString(labelRes)).append("\n");
        for (int i = 0; i < array.length(); i++) {
            out.append("• ").append(array.optString(i)).append("\n");
        }
    }

    private void setBusy(boolean busy) {
        runOcrButton.setEnabled(!busy);
        saveTextButton.setEnabled(!busy);
        runOcrButton.setText(
                busy ? R.string.reading_text : R.string.extract_text
        );
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        if (displayedBitmap != null && !displayedBitmap.isRecycled()) {
            displayedBitmap.recycle();
        }
        super.onDestroy();
    }
}
