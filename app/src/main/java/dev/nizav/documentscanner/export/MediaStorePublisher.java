package dev.nizav.documentscanner.export;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;

public final class MediaStorePublisher {
    private MediaStorePublisher() {
    }

    public static Uri publishPdf(Context context, File source)
            throws IOException {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return null;
        }
        return publish(
                context,
                source,
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                "application/pdf",
                Environment.DIRECTORY_DOWNLOADS + "/PaperScanner"
        );
    }

    public static Uri publishImage(Context context, File source)
            throws IOException {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return null;
        }
        return publish(
                context,
                source,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                "image/jpeg",
                Environment.DIRECTORY_PICTURES + "/PaperScanner"
        );
    }

    private static Uri publish(
            Context context,
            File source,
            Uri collection,
            String mimeType,
            String relativePath
    ) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, source.getName());
        values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);

        Uri uri = resolver.insert(collection, values);
        if (uri == null) {
            throw new IOException("MediaStore insert failed");
        }

        try {
            try (FileInputStream input = new FileInputStream(source);
                 OutputStream output = resolver.openOutputStream(uri, "w")) {
                if (output == null) {
                    throw new IOException("Unable to open MediaStore output");
                }
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
            }

            ContentValues published = new ContentValues();
            published.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(uri, published, null, null);
            return uri;
        } catch (Exception e) {
            resolver.delete(uri, null, null);
            if (e instanceof IOException) {
                throw (IOException) e;
            }
            throw new IOException("MediaStore publish failed", e);
        }
    }
}
