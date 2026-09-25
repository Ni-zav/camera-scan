package dev.nizav.documentscanner.export;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.core.content.FileProvider;

import dev.nizav.documentscanner.R;

import java.io.File;

public final class ShareUtils {
    private ShareUtils() {
    }

    public static void sharePdf(Context context, File pdf) {
        Uri uri = FileProvider.getUriForFile(
                context,
                context.getPackageName() + ".files",
                pdf
        );

        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("application/pdf");
        intent.putExtra(Intent.EXTRA_STREAM, uri);
        intent.setClipData(ClipData.newRawUri("scan", uri));
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        context.startActivity(
                Intent.createChooser(
                        intent,
                        context.getString(R.string.share_pdf)
                )
        );
    }
}
