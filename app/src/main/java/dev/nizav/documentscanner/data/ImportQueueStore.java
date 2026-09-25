package dev.nizav.documentscanner.data;

import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public final class ImportQueueStore {
    private static final String DIR = "document_scanner_import_queue";

    private ImportQueueStore() {
    }

    public static synchronized int replaceWith(
            Context context,
            List<Uri> uris
    ) throws IOException {
        clear(context);

        File root = root(context);
        if (!root.exists() && !root.mkdirs()) {
            throw new IOException("Unable to create import queue");
        }

        byte[] buffer = new byte[64 * 1024];
        int written = 0;

        for (int i = 0; i < uris.size(); i++) {
            Uri uri = uris.get(i);
            File target = new File(
                    root,
                    String.format(
                            java.util.Locale.US,
                            "source_%04d.img",
                            i + 1
                    )
            );

            try (InputStream input =
                         context.getContentResolver().openInputStream(uri);
                 FileOutputStream output = new FileOutputStream(target)) {
                if (input == null) {
                    target.delete();
                    continue;
                }

                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
                written++;
            } catch (IOException e) {
                target.delete();
            }
        }

        return written;
    }

    public static synchronized File peek(Context context) {
        File[] files = root(context).listFiles(File::isFile);
        if (files == null || files.length == 0) {
            return null;
        }
        Arrays.sort(files, Comparator.comparing(File::getName));
        return files[0];
    }

    public static synchronized int count(Context context) {
        File[] files = root(context).listFiles(File::isFile);
        return files == null ? 0 : files.length;
    }

    public static synchronized void complete(File file) {
        if (file != null) {
            file.delete();
        }
    }

    public static synchronized void clear(Context context) {
        File root = root(context);
        File[] files = root.listFiles();
        if (files != null) {
            for (File file : files) {
                file.delete();
            }
        }
        root.delete();
    }

    private static File root(Context context) {
        return new File(context.getCacheDir(), DIR);
    }
}
