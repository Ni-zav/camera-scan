package dev.nizav.documentscanner;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.transition.TransitionManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.transition.MaterialFadeThrough;

import dev.nizav.documentscanner.data.ProjectRepository;
import dev.nizav.documentscanner.data.db.ProjectRow;
import dev.nizav.documentscanner.ui.MaterialMotionActivity;
import dev.nizav.documentscanner.ui.ProjectActivity;
import dev.nizav.documentscanner.ui.ProjectAdapter;
import dev.nizav.documentscanner.ui.ThumbnailLoader;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends MaterialMotionActivity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private ViewGroup root;
    private RecyclerView recycler;
    private View emptyState;
    private ExtendedFloatingActionButton newDocumentButton;

    private ProjectRepository repository;
    private ThumbnailLoader thumbnails;
    private ProjectAdapter adapter;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        root = findViewById(R.id.homeRoot);
        recycler = findViewById(R.id.projectList);
        emptyState = findViewById(R.id.emptyState);
        newDocumentButton = findViewById(R.id.newDocumentButton);

        repository = new ProjectRepository(this);
        thumbnails = new ThumbnailLoader();
        adapter = new ProjectAdapter(
                thumbnails,
                row -> openProject(row.project.id)
        );

        recycler.setLayoutManager(new LinearLayoutManager(this));
        recycler.setAdapter(adapter);
        recycler.setHasFixedSize(true);

        newDocumentButton.setOnClickListener(v -> showNewDocumentDialog());
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadProjects();
    }

    private void loadProjects() {
        worker.execute(() -> {
            List<ProjectRow> rows = repository.listProjects();
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }

                TransitionManager.beginDelayedTransition(
                        root,
                        new MaterialFadeThrough()
                );
                adapter.submitList(rows);

                boolean empty = rows.isEmpty();
                emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
                recycler.setVisibility(empty ? View.GONE : View.VISIBLE);
            });
        });
    }

    private void showNewDocumentDialog() {
        View content = getLayoutInflater().inflate(
                R.layout.dialog_new_project,
                null,
                false
        );
        TextInputEditText input = content.findViewById(R.id.projectNameInput);

        MaterialAlertDialogBuilder builder =
                new MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.new_document)
                        .setMessage(R.string.new_document_hint)
                        .setView(content)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(R.string.create_document, null);

        androidx.appcompat.app.AlertDialog dialog = builder.create();
        dialog.setOnShowListener(ignored ->
                dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                        .setOnClickListener(v -> {
                            String name = input.getText() == null
                                    ? null
                                    : input.getText().toString();
                            dialog.getButton(
                                    androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE
                            ).setEnabled(false);

                            worker.execute(() -> {
                                long projectId = repository.createProject(name);
                                runOnUiThread(() -> {
                                    dialog.dismiss();
                                    openProject(projectId);
                                });
                            });
                        })
        );
        dialog.show();
    }

    private void openProject(long projectId) {
        Intent intent = new Intent(this, ProjectActivity.class);
        intent.putExtra(ProjectActivity.EXTRA_PROJECT_ID, projectId);
        startActivity(intent);
    }

    @Override
    protected void onDestroy() {
        thumbnails.close();
        worker.shutdownNow();
        super.onDestroy();
    }
}
