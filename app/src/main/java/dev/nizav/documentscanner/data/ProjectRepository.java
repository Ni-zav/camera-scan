package dev.nizav.documentscanner.data;

import android.content.Context;
import android.graphics.Bitmap;

import dev.nizav.documentscanner.data.db.AppDatabase;
import dev.nizav.documentscanner.data.db.PageEntity;
import dev.nizav.documentscanner.data.db.ProjectEntity;
import dev.nizav.documentscanner.data.db.ProjectRow;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

public final class ProjectRepository {
    private final Context context;
    private final AppDatabase db;

    public ProjectRepository(Context context) {
        this.context = context.getApplicationContext();
        this.db = AppDatabase.get(this.context);
    }

    public long createProject(String requestedName) {
        long now = System.currentTimeMillis();
        ProjectEntity project = new ProjectEntity();
        project.name = normalizeProjectName(requestedName);
        project.createdAt = now;
        project.updatedAt = now;
        return db.projectDao().insert(project);
    }

    public ProjectEntity getProject(long projectId) {
        return db.projectDao().get(projectId);
    }

    public List<ProjectRow> listProjects() {
        return db.projectDao().listRows();
    }

    public List<PageEntity> listPages(long projectId) {
        return db.pageDao().listForProject(projectId);
    }

    public PageEntity getPage(long pageId) {
        return db.pageDao().get(pageId);
    }

    public File addPage(long projectId, Bitmap bitmap) throws IOException {
        ProjectEntity project = requireProject(projectId);
        File dir = pageDirectory(projectId);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Unable to create project page directory");
        }

        File output = new File(
                dir,
                "page_" + System.currentTimeMillis() + "_"
                        + UUID.randomUUID().toString().substring(0, 8) + ".jpg"
        );

        try (FileOutputStream stream = new FileOutputStream(output)) {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)) {
                throw new IOException("Unable to encode project page");
            }
        }

        try {
            long now = System.currentTimeMillis();
            PageEntity page = new PageEntity();
            page.projectId = projectId;
            page.position = db.pageDao().maxPosition(projectId) + 1;
            page.filePath = output.getAbsolutePath();
            page.ocrText = null;
            page.ocrDataJson = null;
            page.ocrUpdatedAt = 0L;
            page.createdAt = now;
            page.updatedAt = now;
            db.pageDao().insert(page);

            project.updatedAt = now;
            db.projectDao().update(project);
            return output;
        } catch (RuntimeException e) {
            output.delete();
            throw e;
        }
    }

    public void updateOcr(
            long pageId,
            String text,
            String dataJson
    ) {
        PageEntity page = db.pageDao().get(pageId);
        if (page == null) {
            return;
        }

        long now = System.currentTimeMillis();
        db.pageDao().updateOcr(pageId, text, dataJson, now);

        ProjectEntity project = db.projectDao().get(page.projectId);
        if (project != null) {
            project.updatedAt = now;
            db.projectDao().update(project);
        }
    }

    public void renameProject(long projectId, String requestedName) {
        ProjectEntity project = db.projectDao().get(projectId);
        if (project == null) {
            return;
        }
        project.name = normalizeProjectName(requestedName);
        project.updatedAt = System.currentTimeMillis();
        db.projectDao().update(project);
    }

    public void deletePage(long pageId) {
        PageEntity page = db.pageDao().get(pageId);
        if (page == null) {
            return;
        }

        db.runInTransaction(() -> {
            db.pageDao().delete(pageId);
            normalizePositions(page.projectId);
            ProjectEntity project = db.projectDao().get(page.projectId);
            if (project != null) {
                project.updatedAt = System.currentTimeMillis();
                db.projectDao().update(project);
            }
        });

        if (page.filePath != null) {
            new File(page.filePath).delete();
        }
    }

    public void movePage(long projectId, int from, int to) {
        List<PageEntity> pages = db.pageDao().listForProject(projectId);
        if (from < 0 || to < 0
                || from >= pages.size() || to >= pages.size()
                || from == to) {
            return;
        }

        PageEntity moving = pages.remove(from);
        pages.add(to, moving);
        long now = System.currentTimeMillis();

        db.runInTransaction(() -> {
            // Temporarily move rows out of the unique project/position range.
            for (PageEntity page : pages) {
                db.pageDao().setPosition(
                        page.id,
                        page.position + 100000,
                        now
                );
            }
            for (int index = 0; index < pages.size(); index++) {
                db.pageDao().setPosition(
                        pages.get(index).id,
                        index,
                        now
                );
            }

            ProjectEntity project = db.projectDao().get(projectId);
            if (project != null) {
                project.updatedAt = now;
                db.projectDao().update(project);
            }
        });
    }

    public void deleteProject(long projectId) {
        db.projectDao().delete(projectId);
        deleteRecursively(projectDirectory(projectId));
    }

    public File projectDirectory(long projectId) {
        return new File(
                new File(context.getFilesDir(), "projects"),
                Long.toString(projectId)
        );
    }

    private File pageDirectory(long projectId) {
        return new File(projectDirectory(projectId), "pages");
    }

    private ProjectEntity requireProject(long projectId) throws IOException {
        ProjectEntity project = db.projectDao().get(projectId);
        if (project == null) {
            throw new IOException("Project no longer exists");
        }
        return project;
    }

    private void normalizePositions(long projectId) {
        List<PageEntity> pages = db.pageDao().listForProject(projectId);
        long now = System.currentTimeMillis();
        for (int i = 0; i < pages.size(); i++) {
            db.pageDao().setPosition(pages.get(i).id, i, now);
        }
    }

    private static String normalizeProjectName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "Untitled document";
        }
        String trimmed = name.trim();
        return trimmed.length() <= 80
                ? trimmed
                : trimmed.substring(0, 80);
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        file.delete();
    }
}
