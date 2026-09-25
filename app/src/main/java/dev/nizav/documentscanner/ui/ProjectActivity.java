package dev.nizav.documentscanner.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.transition.TransitionManager;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.transition.MaterialFadeThrough;

import dev.nizav.documentscanner.R;
import dev.nizav.documentscanner.data.ImportQueueStore;
import dev.nizav.documentscanner.data.ProjectRepository;
import dev.nizav.documentscanner.data.db.PageEntity;
import dev.nizav.documentscanner.data.db.ProjectEntity;
import dev.nizav.documentscanner.export.ProjectExporter;
import dev.nizav.documentscanner.export.ShareUtils;
import dev.nizav.documentscanner.ocr.OcrEngine;
import dev.nizav.documentscanner.ocr.OcrPipeline;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ProjectActivity extends MaterialMotionActivity {
    public static final String EXTRA_PROJECT_ID = "project_id";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService orderingWorker =
            Executors.newSingleThreadExecutor();

    private long projectId;
    private ProjectRepository repository;
    private ThumbnailLoader thumbnails;
    private PageAdapter adapter;
    private ProjectEntity currentProject;

    private ViewGroup root;
    private MaterialToolbar toolbar;
    private RecyclerView recycler;
    private View emptyState;
    private TextView indexStatus;
    private TextView indexPreview;
    private TextView reorderHint;
    private MaterialButton importButton;
    private MaterialButton ocrButton;
    private MaterialButton exportButton;
    private ExtendedFloatingActionButton scanButton;

    private boolean galleryMode = true;
    private boolean dragOrderDirty;
    private File activeImport;

    private final ActivityResultLauncher<PickVisualMediaRequest> galleryLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.PickMultipleVisualMedia(20),
                    this::onGalleryPicked
            );

    private final ActivityResultLauncher<Intent> cropLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    this::onCropResult
            );

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_project);

        projectId = getIntent().getLongExtra(EXTRA_PROJECT_ID, -1L);
        if (projectId <= 0L) {
            finish();
            return;
        }

        repository = new ProjectRepository(this);
        thumbnails = new ThumbnailLoader();

        root = findViewById(R.id.projectRoot);
        toolbar = findViewById(R.id.projectToolbar);
        recycler = findViewById(R.id.pageList);
        emptyState = findViewById(R.id.pageEmptyState);
        indexStatus = findViewById(R.id.indexStatus);
        indexPreview = findViewById(R.id.indexPreview);
        reorderHint = findViewById(R.id.pageReorderHint);
        importButton = findViewById(R.id.importButton);
        ocrButton = findViewById(R.id.ocrProjectButton);
        exportButton = findViewById(R.id.exportProjectButton);
        scanButton = findViewById(R.id.scanButton);

        adapter = new PageAdapter(
                thumbnails,
                page -> openPage(page.id)
        );
        recycler.setAdapter(adapter);
        recycler.setLayoutManager(new GridLayoutManager(this, 2));

        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.inflateMenu(R.menu.project_actions);
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.actionRenameProject) {
                showRenameDialog();
                return true;
            }
            if (item.getItemId() == R.id.actionDeleteProject) {
                confirmDeleteProject();
                return true;
            }
            return false;
        });

        MaterialButtonToggleGroup viewToggle =
                findViewById(R.id.pageViewToggle);
        viewToggle.check(R.id.galleryButton);
        viewToggle.addOnButtonCheckedListener(
                (group, checkedId, isChecked) -> {
                    if (!isChecked) return;
                    switchPageMode(checkedId == R.id.galleryButton);
                }
        );

        attachPageReordering();

        scanButton.setOnClickListener(v -> {
            Intent intent = new Intent(this, ScanActivity.class);
            intent.putExtra(ScanActivity.EXTRA_PROJECT_ID, projectId);
            startActivity(intent);
        });

        importButton.setOnClickListener(v -> launchGallery());

        ocrButton.setEnabled(false);
        exportButton.setEnabled(false);
        ocrButton.setOnClickListener(v -> indexProjectText());
        exportButton.setOnClickListener(v -> exportProject());

        launchNextImportIfNeeded();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (repository != null) {
            loadProject();
        }
    }

    private void attachPageReordering() {
        ItemTouchHelper.SimpleCallback callback =
                new ItemTouchHelper.SimpleCallback(
                        ItemTouchHelper.UP
                                | ItemTouchHelper.DOWN
                                | ItemTouchHelper.LEFT
                                | ItemTouchHelper.RIGHT,
                        0
                ) {
                    @Override
                    public int getMovementFlags(
                            @NonNull RecyclerView recyclerView,
                            @NonNull RecyclerView.ViewHolder viewHolder
                    ) {
                        int dragFlags = galleryMode
                                ? ItemTouchHelper.UP
                                    | ItemTouchHelper.DOWN
                                    | ItemTouchHelper.LEFT
                                    | ItemTouchHelper.RIGHT
                                : ItemTouchHelper.UP
                                    | ItemTouchHelper.DOWN;
                        return makeMovementFlags(dragFlags, 0);
                    }

                    @Override
                    public boolean onMove(
                            @NonNull RecyclerView recyclerView,
                            @NonNull RecyclerView.ViewHolder source,
                            @NonNull RecyclerView.ViewHolder target
                    ) {
                        int from = source.getBindingAdapterPosition();
                        int to = target.getBindingAdapterPosition();
                        boolean moved = adapter.moveItem(from, to);
                        dragOrderDirty |= moved;
                        return moved;
                    }

                    @Override
                    public void onSwiped(
                            @NonNull RecyclerView.ViewHolder viewHolder,
                            int direction
                    ) {
                        // No swipe action. Destructive page removal stays
                        // explicit in Page Details.
                    }

                    @Override
                    public void onSelectedChanged(
                            RecyclerView.ViewHolder viewHolder,
                            int actionState
                    ) {
                        super.onSelectedChanged(viewHolder, actionState);
                        if (viewHolder != null
                                && actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                            viewHolder.itemView.animate()
                                    .scaleX(1.025f)
                                    .scaleY(1.025f)
                                    .alpha(0.92f)
                                    .setDuration(120L)
                                    .start();
                        }
                    }

                    @Override
                    public void clearView(
                            @NonNull RecyclerView recyclerView,
                            @NonNull RecyclerView.ViewHolder viewHolder
                    ) {
                        super.clearView(recyclerView, viewHolder);
                        viewHolder.itemView.animate()
                                .scaleX(1f)
                                .scaleY(1f)
                                .alpha(1f)
                                .setDuration(120L)
                                .start();

                        if (!dragOrderDirty) {
                            return;
                        }
                        dragOrderDirty = false;

                        List<Long> order = adapter.pageIds();
                        orderingWorker.execute(
                                () -> repository.setPageOrder(projectId, order)
                        );
                    }
                };

        new ItemTouchHelper(callback).attachToRecyclerView(recycler);
    }

    private void loadProject() {
        worker.execute(() -> {
            ProjectEntity project = repository.getProject(projectId);
            List<PageEntity> pages = repository.listPages(projectId);

            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (project == null) {
                    finish();
                    return;
                }

                currentProject = project;
                toolbar.setTitle(project.name);
                renderPages(pages);
            });
        });
    }

    private void renderPages(List<PageEntity> pages) {
        TransitionManager.beginDelayedTransition(
                root,
                new MaterialFadeThrough()
        );

        adapter.submitList(pages);
        boolean empty = pages.isEmpty();
        emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        recycler.setVisibility(empty ? View.GONE : View.VISIBLE);
        reorderHint.setVisibility(
                pages.size() > 1 ? View.VISIBLE : View.GONE
        );
        ocrButton.setEnabled(!empty);
        exportButton.setEnabled(!empty);

        int indexed = 0;
        String preview = null;
        for (PageEntity page : pages) {
            if (page.ocrText != null && !page.ocrText.trim().isEmpty()) {
                indexed++;
                if (preview == null) {
                    preview = page.ocrText.trim();
                }
            }
        }

        indexStatus.setText(
                getString(
                        R.string.ocr_index_status,
                        indexed,
                        pages.size()
                )
        );

        if (preview == null) {
            indexPreview.setText(R.string.ocr_index_empty);
        } else {
            indexPreview.setText(
                    preview.length() > 240
                            ? preview.substring(0, 240) + "…"
                            : preview
            );
        }
    }

    private void switchPageMode(boolean gallery) {
        if (galleryMode == gallery) {
            return;
        }
        galleryMode = gallery;

        TransitionManager.beginDelayedTransition(
                root,
                new MaterialFadeThrough()
        );

        adapter.setMode(
                gallery ? PageAdapter.MODE_GALLERY : PageAdapter.MODE_LIST
        );
        recycler.setLayoutManager(
                gallery
                        ? new GridLayoutManager(this, 2)
                        : new LinearLayoutManager(this)
        );
    }

    private void showRenameDialog() {
        ProjectEntity project = currentProject;
        if (project == null) {
            return;
        }

        View content = getLayoutInflater().inflate(
                R.layout.dialog_new_project,
                null,
                false
        );
        TextInputEditText input = content.findViewById(R.id.projectNameInput);
        input.setText(project.name);
        input.selectAll();

        androidx.appcompat.app.AlertDialog dialog =
                new MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.rename_document)
                        .setView(content)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(R.string.save_text, null)
                        .create();

        dialog.setOnShowListener(ignored ->
                dialog.getButton(
                        androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE
                ).setOnClickListener(v -> {
                    String name = input.getText() == null
                            ? null
                            : input.getText().toString();

                    dialog.getButton(
                            androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE
                    ).setEnabled(false);

                    worker.execute(() -> {
                        repository.renameProject(projectId, name);
                        runOnUiThread(() -> {
                            if (!isFinishing() && !isDestroyed()) {
                                dialog.dismiss();
                                loadProject();
                            }
                        });
                    });
                })
        );
        dialog.show();
    }

    private void confirmDeleteProject() {
        ProjectEntity project = currentProject;
        if (project == null) {
            return;
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.delete_document)
                .setMessage(
                        getString(
                                R.string.delete_document_warning,
                                project.name
                        )
                )
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.delete_document, (dialog, which) ->
                        worker.execute(() -> {
                            ImportQueueStore.clear(this, projectId);
                            repository.deleteProject(projectId);
                            runOnUiThread(this::finish);
                        })
                )
                .show();
    }

    private void openPage(long pageId) {
        Intent intent = new Intent(this, PageDetailActivity.class);
        intent.putExtra(PageDetailActivity.EXTRA_PAGE_ID, pageId);
        startActivity(intent);
    }

    private void launchGallery() {
        PickVisualMediaRequest request =
                new PickVisualMediaRequest.Builder()
                        .setMediaType(
                                ActivityResultContracts.PickVisualMedia
                                        .ImageOnly.INSTANCE
                        )
                        .setMaxItems(20)
                        .build();
        galleryLauncher.launch(request);
    }

    private void onGalleryPicked(List<Uri> uris) {
        if (uris == null || uris.isEmpty()) {
            return;
        }

        importButton.setEnabled(false);
        worker.execute(() -> {
            try {
                int copied = ImportQueueStore.replaceWith(
                        this,
                        projectId,
                        uris
                );
                runOnUiThread(() -> {
                    importButton.setEnabled(true);
                    if (copied == 0) {
                        Toast.makeText(
                                this,
                                R.string.import_failed,
                                Toast.LENGTH_LONG
                        ).show();
                        return;
                    }
                    launchNextImportIfNeeded();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    importButton.setEnabled(true);
                    Toast.makeText(
                            this,
                            getString(
                                    R.string.import_failed_detail,
                                    e.getMessage()
                            ),
                            Toast.LENGTH_LONG
                    ).show();
                });
            }
        });
    }

    private void launchNextImportIfNeeded() {
        if (activeImport != null) {
            return;
        }

        File next = ImportQueueStore.peek(this, projectId);
        if (next == null) {
            return;
        }

        activeImport = next;
        Intent intent = new Intent(this, CropActivity.class);
        intent.putExtra(CropActivity.EXTRA_IMAGE_PATH, next.getAbsolutePath());
        intent.putExtra(CropActivity.EXTRA_PROJECT_ID, projectId);
        cropLauncher.launch(intent);
    }

    private void onCropResult(ActivityResult result) {
        File completed = activeImport;
        activeImport = null;
        if (completed != null) {
            ImportQueueStore.complete(completed);
        }
        loadProject();
        launchNextImportIfNeeded();
    }

    private void indexProjectText() {
        ocrButton.setEnabled(false);
        exportButton.setEnabled(false);
        ocrButton.setText(R.string.reading_text);

        worker.execute(() -> {
            List<PageEntity> pages = repository.listPages(projectId);
            try (OcrEngine engine = new OcrEngine()) {
                int completed = 0;
                for (PageEntity page : pages) {
                    OcrPipeline.Result result = OcrPipeline.recognizeFile(
                            new File(page.filePath),
                            engine
                    );
                    repository.updateOcr(
                            page.id,
                            result.text,
                            result.dataJson
                    );
                    completed++;
                    int done = completed;
                    runOnUiThread(() -> {
                        if (!isFinishing() && !isDestroyed()) {
                            indexStatus.setText(
                                    getString(
                                            R.string.ocr_index_progress,
                                            done,
                                            pages.size()
                                    )
                            );
                        }
                    });
                }

                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        ocrButton.setText(R.string.extract_text);
                        loadProject();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    ocrButton.setText(R.string.extract_text);
                    ocrButton.setEnabled(true);
                    exportButton.setEnabled(true);
                    Toast.makeText(
                            this,
                            getString(R.string.ocr_failed, e.getMessage()),
                            Toast.LENGTH_LONG
                    ).show();
                });
            }
        });
    }

    private void exportProject() {
        ocrButton.setEnabled(false);
        exportButton.setEnabled(false);
        exportButton.setText(R.string.exporting);

        worker.execute(() -> {
            try {
                ProjectExporter.ExportResult result =
                        ProjectExporter.export(this, projectId, true);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    exportButton.setText(R.string.export_searchable_pdf);
                    loadProject();
                    ShareUtils.sharePdf(this, result.pdf);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    exportButton.setText(R.string.export_searchable_pdf);
                    ocrButton.setEnabled(true);
                    exportButton.setEnabled(true);
                    Toast.makeText(
                            this,
                            getString(R.string.export_failed, e.getMessage()),
                            Toast.LENGTH_LONG
                    ).show();
                });
            }
        });
    }

    @Override
    protected void onDestroy() {
        thumbnails.close();
        worker.shutdownNow();
        orderingWorker.shutdown();
        super.onDestroy();
    }
}
