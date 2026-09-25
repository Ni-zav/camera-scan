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
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.transition.TransitionManager;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.transition.MaterialFadeThrough;

import dev.nizav.documentscanner.R;
import dev.nizav.documentscanner.data.ImportQueueStore;
import dev.nizav.documentscanner.data.ProjectRepository;
import dev.nizav.documentscanner.data.db.PageEntity;
import dev.nizav.documentscanner.data.db.ProjectEntity;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ProjectActivity extends MaterialMotionActivity {
    public static final String EXTRA_PROJECT_ID = "project_id";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private long projectId;
    private ProjectRepository repository;
    private ThumbnailLoader thumbnails;
    private PageAdapter adapter;

    private ViewGroup root;
    private MaterialToolbar toolbar;
    private RecyclerView recycler;
    private View emptyState;
    private TextView indexStatus;
    private TextView indexPreview;
    private MaterialButton galleryButton;
    private MaterialButton listButton;
    private MaterialButton importButton;
    private MaterialButton ocrButton;
    private MaterialButton exportButton;
    private ExtendedFloatingActionButton scanButton;

    private boolean galleryMode = true;
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
        galleryButton = findViewById(R.id.galleryButton);
        listButton = findViewById(R.id.listButton);
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

        MaterialButtonToggleGroup viewToggle =
                findViewById(R.id.pageViewToggle);
        viewToggle.check(R.id.galleryButton);
        viewToggle.addOnButtonCheckedListener(
                (group, checkedId, isChecked) -> {
                    if (!isChecked) return;
                    switchPageMode(checkedId == R.id.galleryButton);
                }
        );

        scanButton.setOnClickListener(v -> {
            Intent intent = new Intent(this, ScanActivity.class);
            intent.putExtra(ScanActivity.EXTRA_PROJECT_ID, projectId);
            startActivity(intent);
        });

        importButton.setOnClickListener(v -> launchGallery());

        // Wired to full behavior by OCR/export layer; intentionally disabled
        // until project/page state has loaded.
        ocrButton.setEnabled(false);
        exportButton.setEnabled(false);

        loadProject();
        launchNextImportIfNeeded();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (repository != null) {
            loadProject();
        }
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
                int copied = ImportQueueStore.replaceWith(this, uris);
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
                            getString(R.string.import_failed_detail, e.getMessage()),
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

        File next = ImportQueueStore.peek(this);
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

    @Override
    protected void onDestroy() {
        thumbnails.close();
        worker.shutdownNow();
        super.onDestroy();
    }
}
