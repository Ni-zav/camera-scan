package dev.nizav.documentscanner.data;

import android.content.Context;
import android.graphics.Bitmap;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public final class ScanSessionStore {
    private static final String SESSION_DIR = "document_scanner_session";
    private static final String PAGES_DIR = "pages";

    private ScanSessionStore() {
    }

    public static synchronized File addPage(
            Context context,
            Bitmap bitmap
    ) throws IOException {
        File dir = pagesDir(context);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Unable to create session directory");
        }

        int index = listPages(context).size() + 1;
        File output = new File(dir, pageName(index));
        try (FileOutputStream stream = new FileOutputStream(output)) {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)) {
                throw new IOException("Unable to encode session page");
            }
        }
        return output;
    }

    public static synchronized List<File> listPages(Context context) {
        File[] files = pagesDir(context).listFiles(
                file -> file.isFile()
                        && file.getName().startsWith("page_")
                        && file.getName().endsWith(".jpg")
        );
        if (files == null || files.length == 0) {
            return new ArrayList<>();
        }

        Arrays.sort(files, Comparator.comparing(File::getName));
        return new ArrayList<>(Arrays.asList(files));
    }

    public static synchronized int pageCount(Context context) {
        return listPages(context).size();
    }

    public static synchronized void deletePage(
            Context context,
            int index
    ) throws IOException {
        List<File> pages = listPages(context);
        if (index < 0 || index >= pages.size()) {
            return;
        }
        if (!pages.get(index).delete()) {
            throw new IOException("Unable to delete page");
        }
        normalizeNames(context);
    }

    public static synchronized void movePage(
            Context context,
            int from,
            int to
    ) throws IOException {
        List<File> pages = listPages(context);
        if (from < 0 || from >= pages.size()
                || to < 0 || to >= pages.size()
                || from == to) {
            return;
        }

        File moved = pages.remove(from);
        pages.add(to, moved);
        rewriteOrder(pages);
    }

    public static synchronized void clear(Context context) {
        deleteRecursively(sessionRoot(context));
    }

    public static File sessionRoot(Context context) {
        return new File(context.getCacheDir(), SESSION_DIR);
    }

    private static File pagesDir(Context context) {
        return new File(sessionRoot(context), PAGES_DIR);
    }

    private static void normalizeNames(Context context) throws IOException {
        rewriteOrder(listPages(context));
    }

    private static void rewriteOrder(List<File> ordered) throws IOException {
        List<File> temporary = new ArrayList<>(ordered.size());

        for (int i = 0; i < ordered.size(); i++) {
            File source = ordered.get(i);
            File temp = new File(
                    source.getParentFile(),
                    "tmp_" + System.nanoTime() + "_" + i + ".jpg"
            );
            if (!source.renameTo(temp)) {
                throw new IOException("Unable to stage page reorder");
            }
            temporary.add(temp);
        }

        for (int i = 0; i < temporary.size(); i++) {
            File target = new File(
                    temporary.get(i).getParentFile(),
                    pageName(i + 1)
            );
            if (!temporary.get(i).renameTo(target)) {
                throw new IOException("Unable to finalize page reorder");
            }
        }
    }

    private static String pageName(int index) {
        return String.format(java.util.Locale.US, "page_%04d.jpg", index);
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
