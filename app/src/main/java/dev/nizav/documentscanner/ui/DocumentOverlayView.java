package dev.nizav.documentscanner.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import dev.nizav.documentscanner.cv.Quad;

public final class DocumentOverlayView extends View {
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final float density;

    private final float[] normalized = new float[8];
    private boolean hasQuad;
    private boolean stable;

    public DocumentOverlayView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(2.5f * density);
        handlePaint.setStyle(Paint.Style.FILL);
    }

    public void setDocument(@Nullable Quad quad, boolean stable) {
        this.stable = stable;
        this.hasQuad = quad != null;

        if (quad != null) {
            float[] values = quad.toFloatArray();
            System.arraycopy(values, 0, normalized, 0, 8);
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!hasQuad) {
            return;
        }

        int color = stable ? Color.rgb(92, 220, 120) : Color.WHITE;
        linePaint.setColor(color);
        handlePaint.setColor(color);

        path.reset();
        for (int i = 0; i < 4; i++) {
            float x = normalized[i * 2] * getWidth();
            float y = normalized[i * 2 + 1] * getHeight();
            if (i == 0) {
                path.moveTo(x, y);
            } else {
                path.lineTo(x, y);
            }
        }
        path.close();
        canvas.drawPath(path, linePaint);

        float radius = 4.5f * density;
        for (int i = 0; i < 4; i++) {
            canvas.drawCircle(
                    normalized[i * 2] * getWidth(),
                    normalized[i * 2 + 1] * getHeight(),
                    radius,
                    handlePaint
            );
        }
    }
}
