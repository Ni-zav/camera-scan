package dev.nizav.documentscanner.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import dev.nizav.documentscanner.cv.Boundary8;

import org.opencv.core.Point;

public final class DocumentCropView extends View {
    private final Paint bitmapPaint = new Paint(
            Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG
    );
    private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cornerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint midpointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path boundaryPath = new Path();
    private final float density;

    private final float[] normalized = Boundary8.fullFrame().toFloatArray();

    private Bitmap bitmap;
    private boolean editing;
    private int activeHandle = -1;

    private float drawLeft;
    private float drawTop;
    private float drawWidth;
    private float drawHeight;

    public DocumentCropView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;

        edgePaint.setColor(Color.WHITE);
        edgePaint.setStyle(Paint.Style.STROKE);
        edgePaint.setStrokeWidth(2.5f * density);

        cornerPaint.setColor(Color.WHITE);
        cornerPaint.setStyle(Paint.Style.FILL);

        midpointPaint.setColor(Color.argb(235, 210, 235, 255));
        midpointPaint.setStyle(Paint.Style.FILL);

        setBackgroundColor(Color.BLACK);
    }

    public void setDocument(
            Bitmap bitmap,
            @Nullable Boundary8 boundary,
            boolean editing
    ) {
        this.bitmap = bitmap;
        this.editing = editing;

        if (boundary != null) {
            float[] values = boundary.toFloatArray();
            System.arraycopy(values, 0, normalized, 0, 16);
        } else if (!editing) {
            float[] full = Boundary8.fullFrame().toFloatArray();
            System.arraycopy(full, 0, normalized, 0, 16);
        }

        activeHandle = -1;
        invalidate();
    }

    public Boundary8 getNormalizedBoundary() {
        Point[] points = new Point[8];
        for (int i = 0; i < 8; i++) {
            points[i] = new Point(
                    normalized[i * 2],
                    normalized[i * 2 + 1]
            );
        }
        return new Boundary8(points);
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
                new RectF(
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

        boundaryPath.reset();
        boundaryPath.moveTo(viewX(Boundary8.TL), viewY(Boundary8.TL));
        appendQuadratic(
                boundaryPath,
                Boundary8.TL,
                Boundary8.TM,
                Boundary8.TR
        );
        appendQuadratic(
                boundaryPath,
                Boundary8.TR,
                Boundary8.RM,
                Boundary8.BR
        );
        appendQuadratic(
                boundaryPath,
                Boundary8.BR,
                Boundary8.BM,
                Boundary8.BL
        );
        appendQuadratic(
                boundaryPath,
                Boundary8.BL,
                Boundary8.LM,
                Boundary8.TL
        );
        boundaryPath.close();
        canvas.drawPath(boundaryPath, edgePaint);

        for (int i = 0; i < 8; i++) {
            boolean corner = i % 2 == 0;
            canvas.drawCircle(
                    viewX(i),
                    viewY(i),
                    (corner ? 9f : 6.5f) * density,
                    corner ? cornerPaint : midpointPaint
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
                activeHandle = nearestHandle(event.getX(), event.getY());
                if (activeHandle >= 0) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                }
                return false;

            case MotionEvent.ACTION_MOVE:
                if (activeHandle < 0) return false;

                float oldX = normalized[activeHandle * 2];
                float oldY = normalized[activeHandle * 2 + 1];

                float newX = clamp01(
                        (event.getX() - drawLeft) / drawWidth
                );
                float newY = clamp01(
                        (event.getY() - drawTop) / drawHeight
                );

                normalized[activeHandle * 2] = newX;
                normalized[activeHandle * 2 + 1] = newY;

                if (activeHandle % 2 == 0) {
                    float dx = (newX - oldX) * 0.5f;
                    float dy = (newY - oldY) * 0.5f;
                    moveAdjacentMidpoint(
                            (activeHandle + 7) % 8,
                            dx,
                            dy
                    );
                    moveAdjacentMidpoint(
                            (activeHandle + 1) % 8,
                            dx,
                            dy
                    );
                }

                invalidate();
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (activeHandle >= 0) {
                    activeHandle = -1;
                    getParent().requestDisallowInterceptTouchEvent(false);
                    invalidate();
                    return true;
                }
                return false;

            default:
                return false;
        }
    }

    private void appendQuadratic(
            Path path,
            int start,
            int midpoint,
            int end
    ) {
        float sx = viewX(start);
        float sy = viewY(start);
        float mx = viewX(midpoint);
        float my = viewY(midpoint);
        float ex = viewX(end);
        float ey = viewY(end);

        float cx = 2f * mx - 0.5f * (sx + ex);
        float cy = 2f * my - 0.5f * (sy + ey);
        path.quadTo(cx, cy, ex, ey);
    }

    private int nearestHandle(float x, float y) {
        float hitRadius = 48f * density;
        float bestDistance = hitRadius;
        int best = -1;

        for (int i = 0; i < 8; i++) {
            float dx = x - viewX(i);
            float dy = y - viewY(i);
            float distance = (float) Math.hypot(dx, dy);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return best;
    }

    private void moveAdjacentMidpoint(
            int midpointIndex,
            float dx,
            float dy
    ) {
        normalized[midpointIndex * 2] = clamp01(
                normalized[midpointIndex * 2] + dx
        );
        normalized[midpointIndex * 2 + 1] = clamp01(
                normalized[midpointIndex * 2 + 1] + dy
        );
    }

    private float viewX(int index) {
        return drawLeft + normalized[index * 2] * drawWidth;
    }

    private float viewY(int index) {
        return drawTop + normalized[index * 2 + 1] * drawHeight;
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
