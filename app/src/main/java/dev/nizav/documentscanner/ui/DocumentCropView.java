package dev.nizav.documentscanner.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import dev.nizav.documentscanner.cv.Quad;

import org.opencv.core.Point;

public final class DocumentCropView extends View {
    private final Paint bitmapPaint = new Paint(
            Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG
    );
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path quadPath = new Path();
    private final float density;

    private final float[] normalized = {
            0.05f, 0.05f,
            0.95f, 0.05f,
            0.95f, 0.95f,
            0.05f, 0.95f
    };

    private Bitmap bitmap;
    private boolean editing;
    private int activeCorner = -1;

    private float drawLeft;
    private float drawTop;
    private float drawWidth;
    private float drawHeight;

    public DocumentCropView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;

        linePaint.setColor(Color.WHITE);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(2.5f * density);

        handlePaint.setColor(Color.WHITE);
        handlePaint.setStyle(Paint.Style.FILL);

        setBackgroundColor(Color.BLACK);
    }

    public void setDocument(Bitmap bitmap, @Nullable Quad quad, boolean editing) {
        this.bitmap = bitmap;
        this.editing = editing;

        if (quad != null) {
            float[] q = quad.toFloatArray();
            System.arraycopy(q, 0, normalized, 0, 8);
            normalizeOrder();
        } else if (!editing) {
            normalized[0] = 0f; normalized[1] = 0f;
            normalized[2] = 1f; normalized[3] = 0f;
            normalized[4] = 1f; normalized[5] = 1f;
            normalized[6] = 0f; normalized[7] = 1f;
        }

        activeCorner = -1;
        invalidate();
    }

    public Quad getNormalizedQuad() {
        Point[] points = new Point[4];
        for (int i = 0; i < 4; i++) {
            points[i] = new Point(normalized[i * 2], normalized[i * 2 + 1]);
        }
        return Quad.fromUnordered(points);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (bitmap == null || bitmap.isRecycled()) {
            return;
        }

        float scale = Math.min(
                getWidth() / (float) bitmap.getWidth(),
                getHeight() / (float) bitmap.getHeight()
        );
        drawWidth = bitmap.getWidth() * scale;
        drawHeight = bitmap.getHeight() * scale;
        drawLeft = (getWidth() - drawWidth) * 0.5f;
        drawTop = (getHeight() - drawHeight) * 0.5f;

        canvas.drawBitmap(
                bitmap,
                null,
                new android.graphics.RectF(
                        drawLeft,
                        drawTop,
                        drawLeft + drawWidth,
                        drawTop + drawHeight
                ),
                bitmapPaint
        );

        if (!editing) {
            return;
        }

        quadPath.reset();
        for (int i = 0; i < 4; i++) {
            float x = toViewX(normalized[i * 2]);
            float y = toViewY(normalized[i * 2 + 1]);
            if (i == 0) quadPath.moveTo(x, y);
            else quadPath.lineTo(x, y);
        }
        quadPath.close();
        canvas.drawPath(quadPath, linePaint);

        float radius = 9f * density;
        for (int i = 0; i < 4; i++) {
            canvas.drawCircle(
                    toViewX(normalized[i * 2]),
                    toViewY(normalized[i * 2 + 1]),
                    radius,
                    handlePaint
            );
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!editing || bitmap == null || drawWidth <= 0f || drawHeight <= 0f) {
            return false;
        }

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                activeCorner = nearestCorner(event.getX(), event.getY());
                if (activeCorner >= 0) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                }
                return false;

            case MotionEvent.ACTION_MOVE:
                if (activeCorner < 0) return false;
                normalized[activeCorner * 2] = clamp01(
                        (event.getX() - drawLeft) / drawWidth
                );
                normalized[activeCorner * 2 + 1] = clamp01(
                        (event.getY() - drawTop) / drawHeight
                );
                invalidate();
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (activeCorner >= 0) {
                    normalizeOrder();
                    activeCorner = -1;
                    getParent().requestDisallowInterceptTouchEvent(false);
                    invalidate();
                    return true;
                }
                return false;

            default:
                return false;
        }
    }

    private int nearestCorner(float x, float y) {
        float hitRadius = 52f * density;
        float bestDistance = hitRadius;
        int best = -1;

        for (int i = 0; i < 4; i++) {
            float dx = x - toViewX(normalized[i * 2]);
            float dy = y - toViewY(normalized[i * 2 + 1]);
            float distance = (float) Math.hypot(dx, dy);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return best;
    }

    private void normalizeOrder() {
        float[] ordered = getNormalizedQuad().toFloatArray();
        System.arraycopy(ordered, 0, normalized, 0, 8);
    }

    private float toViewX(float x) {
        return drawLeft + x * drawWidth;
    }

    private float toViewY(float y) {
        return drawTop + y * drawHeight;
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
