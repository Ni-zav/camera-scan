package dev.nizav.documentscanner.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import dev.nizav.documentscanner.R;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ThumbnailLoader implements AutoCloseable {
    private static final int MAX_EDGE = 420;

    private final ExecutorService worker = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> cache;

    public ThumbnailLoader() {
        int runtimeKb = (int) Math.min(
                Integer.MAX_VALUE,
                Runtime.getRuntime().maxMemory() / 1024L
        );
        int cacheKb = Math.min(16 * 1024, Math.max(4 * 1024, runtimeKb / 16));

        cache = new LruCache<>(cacheKb) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return Math.max(1, value.getByteCount() / 1024);
            }
        };
    }

    public void load(String path, ImageView view) {
        view.setTag(path);
        Bitmap cached = path == null ? null : cache.get(path);
        if (cached != null && !cached.isRecycled()) {
            view.setImageBitmap(cached);
            return;
        }

        view.setImageResource(R.drawable.ic_document);
        if (path == null) {
            return;
        }

        worker.execute(() -> {
            Bitmap bitmap = decodeThumbnail(new File(path));
            if (bitmap == null) {
                return;
            }
            cache.put(path, bitmap);
            main.post(() -> {
                Object tag = view.getTag();
                if (path.equals(tag)) {
                    view.setImageBitmap(bitmap);
                }
            });
        });
    }

    private static Bitmap decodeThumbnail(File file) {
        if (!file.exists()) {
            return null;
        }

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }

        int sample = 1;
        int largest = Math.max(bounds.outWidth, bounds.outHeight);
        while (largest / (sample * 2) >= MAX_EDGE) {
            sample *= 2;
        }

        BitmapFactory.Options decode = new BitmapFactory.Options();
        decode.inSampleSize = sample;
        decode.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeFile(file.getAbsolutePath(), decode);
    }

    @Override
    public void close() {
        worker.shutdownNow();
        cache.evictAll();
    }
}
