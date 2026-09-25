package dev.nizav.documentscanner.ui;

import android.app.AlertDialog;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import dev.nizav.documentscanner.R;
import dev.nizav.documentscanner.data.ScanSessionStore;
import dev.nizav.documentscanner.export.SessionExporter;
import dev.nizav.documentscanner.export.ShareUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class SessionActivity extends AppCompatActivity {
    private final ExecutorService worker =
            Executors.newSingleThreadExecutor();

    private ListView pageList;
    private ArrayAdapter<String> adapter;
    private final List<String> labels = new ArrayList<>();
    private Button exportButton;
    private int selected = -1;
    private boolean exporting;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_session);

        pageList = findViewById(R.id.pageList);
        exportButton = findViewById(R.id.exportSessionButton);

        adapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_list_item_single_choice,
                labels
        );
        pageList.setAdapter(adapter);
        pageList.setOnItemClickListener(
                (parent, view, position, id) -> selected = position
        );

        findViewById(R.id.newSessionButton).setOnClickListener(
                v -> confirmNewSession()
        );
        findViewById(R.id.backToScanButton).setOnClickListener(v -> finish());
        findViewById(R.id.moveUpButton).setOnClickListener(v -> move(-1));
        findViewById(R.id.moveDownButton).setOnClickListener(v -> move(1));
        findViewById(R.id.deletePageButton).setOnClickListener(v -> delete());
        exportButton.setOnClickListener(v -> export());

        refresh();
    }

    private void confirmNewSession() {
        if (exporting || ScanSessionStore.pageCount(this) == 0) {
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.new_session)
                .setMessage(R.string.new_session_warning)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.clear_pages, (dialog, which) -> {
                    ScanSessionStore.clear(this);
                    selected = -1;
                    pageList.clearChoices();
                    refresh();
                })
                .show();
    }

    private void move(int delta) {
        if (exporting) return;

        int target = selected + delta;
        int count = ScanSessionStore.pageCount(this);
        if (selected < 0 || target < 0 || target >= count) {
            return;
        }

        try {
            ScanSessionStore.movePage(this, selected, target);
            selected = target;
            refresh();
            pageList.setItemChecked(selected, true);
        } catch (Exception e) {
            Toast.makeText(
                    this,
                    "Unable to reorder: " + e.getMessage(),
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private void delete() {
        if (exporting || selected < 0) {
            return;
        }
        try {
            ScanSessionStore.deletePage(this, selected);
            selected = Math.min(
                    selected,
                    ScanSessionStore.pageCount(this) - 1
            );
            refresh();
            if (selected >= 0) {
                pageList.setItemChecked(selected, true);
            }
        } catch (Exception e) {
            Toast.makeText(
                    this,
                    "Unable to delete: " + e.getMessage(),
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private void export() {
        if (exporting || ScanSessionStore.pageCount(this) == 0) {
            return;
        }

        exporting = true;
        exportButton.setEnabled(false);
        exportButton.setText(R.string.exporting);

        worker.execute(() -> {
            try {
                SessionExporter.ExportResult result =
                        SessionExporter.export(this, true);

                runOnUiThread(() -> {
                    exporting = false;
                    exportButton.setEnabled(true);
                    exportButton.setText(R.string.export_searchable_pdf);

                    Toast.makeText(
                            this,
                            getString(
                                    R.string.exported_to,
                                    result.pdf.getAbsolutePath()
                            ),
                            Toast.LENGTH_LONG
                    ).show();

                    ShareUtils.sharePdf(this, result.pdf);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    exporting = false;
                    exportButton.setEnabled(true);
                    exportButton.setText(R.string.export_searchable_pdf);
                    Toast.makeText(
                            this,
                            "Export failed: " + e.getMessage(),
                            Toast.LENGTH_LONG
                    ).show();
                });
            }
        });
    }

    private void refresh() {
        labels.clear();
        int count = ScanSessionStore.pageCount(this);
        for (int i = 0; i < count; i++) {
            labels.add("Page " + (i + 1));
        }
        adapter.notifyDataSetChanged();
        exportButton.setEnabled(count > 0 && !exporting);
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }
}
