package com.rawviewergo;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;

import androidx.appcompat.widget.AppCompatImageView;

/**
 * ImageView with pinch-to-zoom and drag-to-pan, implemented directly with Matrix and
 * ScaleGestureDetector rather than pulling in a third-party image-viewer library.
 *
 * Zoom/pan resets automatically whenever the displayed image's pixel dimensions change (a new
 * photo, or the view being recreated), but is preserved across same-size image swaps (toggling
 * Auto Enhance) so the user's zoom/pan isn't lost when the enhanced bitmap replaces the base
 * one. Since ViewerActivity is a fresh instance every time it's opened, zoom naturally resets
 * when the screen is left and reopened - no explicit reset-on-exit code needed.
 */
public class ZoomableImageView extends AppCompatImageView {

    private static final float MIN_SCALE = 1f;
    private static final float MAX_SCALE = 6f;

    private final Matrix matrix = new Matrix();
    private final Matrix baseMatrix = new Matrix();
    private float currentScale = 1f;
    private int lastDrawableWidth = -1;
    private int lastDrawableHeight = -1;

    private final ScaleGestureDetector scaleDetector;
    private float lastTouchX;
    private float lastTouchY;
    private int activePointerId = MotionEvent.INVALID_POINTER_ID;

    public ZoomableImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setScaleType(ScaleType.MATRIX);
        scaleDetector = new ScaleGestureDetector(context, new ScaleListener());
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        resetToBaseMatrix();
    }

    @Override
    public void setImageDrawable(Drawable drawable) {
        int newWidth = drawable != null ? drawable.getIntrinsicWidth() : -1;
        int newHeight = drawable != null ? drawable.getIntrinsicHeight() : -1;
        boolean sameSize = newWidth == lastDrawableWidth && newHeight == lastDrawableHeight;
        super.setImageDrawable(drawable);
        lastDrawableWidth = newWidth;
        lastDrawableHeight = newHeight;
        if (sameSize) {
            setImageMatrix(matrix);
        } else {
            resetToBaseMatrix();
        }
    }

    private void resetToBaseMatrix() {
        currentScale = 1f;
        computeBaseMatrix();
        matrix.set(baseMatrix);
        setImageMatrix(matrix);
    }

    /** Reproduces scaleType="fitCenter" as an explicit matrix, so pinch/pan can build on top of it. */
    private void computeBaseMatrix() {
        baseMatrix.reset();
        Drawable drawable = getDrawable();
        int viewWidth = getWidth();
        int viewHeight = getHeight();
        if (drawable == null || viewWidth == 0 || viewHeight == 0) {
            return;
        }
        int drawableWidth = drawable.getIntrinsicWidth();
        int drawableHeight = drawable.getIntrinsicHeight();
        if (drawableWidth <= 0 || drawableHeight <= 0) {
            return;
        }
        float scale = Math.min((float) viewWidth / drawableWidth, (float) viewHeight / drawableHeight);
        float dx = (viewWidth - drawableWidth * scale) / 2f;
        float dy = (viewHeight - drawableHeight * scale) / 2f;
        baseMatrix.setScale(scale, scale);
        baseMatrix.postTranslate(dx, dy);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastTouchX = event.getX();
                lastTouchY = event.getY();
                activePointerId = event.getPointerId(0);
                break;
            case MotionEvent.ACTION_MOVE:
                if (!scaleDetector.isInProgress()) {
                    int pointerIndex = event.findPointerIndex(activePointerId);
                    if (pointerIndex != -1) {
                        float x = event.getX(pointerIndex);
                        float y = event.getY(pointerIndex);
                        matrix.postTranslate(x - lastTouchX, y - lastTouchY);
                        clampTranslation();
                        setImageMatrix(matrix);
                        lastTouchX = x;
                        lastTouchY = y;
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                activePointerId = MotionEvent.INVALID_POINTER_ID;
                break;
            case MotionEvent.ACTION_POINTER_UP: {
                int pointerIndex = event.getActionIndex();
                if (event.getPointerId(pointerIndex) == activePointerId) {
                    int newPointerIndex = pointerIndex == 0 ? 1 : 0;
                    activePointerId = event.getPointerId(newPointerIndex);
                    lastTouchX = event.getX(newPointerIndex);
                    lastTouchY = event.getY(newPointerIndex);
                }
                break;
            }
            default:
                break;
        }
        return true;
    }

    /** Keeps the image covering the view with no empty gaps, without allowing it to drift off-screen. */
    private void clampTranslation() {
        Drawable drawable = getDrawable();
        if (drawable == null) {
            return;
        }
        RectF rect = new RectF(0, 0, drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight());
        matrix.mapRect(rect);

        int viewWidth = getWidth();
        int viewHeight = getHeight();
        float dx = 0;
        float dy = 0;

        if (rect.width() <= viewWidth) {
            dx = (viewWidth - rect.width()) / 2f - rect.left;
        } else if (rect.left > 0) {
            dx = -rect.left;
        } else if (rect.right < viewWidth) {
            dx = viewWidth - rect.right;
        }

        if (rect.height() <= viewHeight) {
            dy = (viewHeight - rect.height()) / 2f - rect.top;
        } else if (rect.top > 0) {
            dy = -rect.top;
        } else if (rect.bottom < viewHeight) {
            dy = viewHeight - rect.bottom;
        }

        matrix.postTranslate(dx, dy);
    }

    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            float newScale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, currentScale * detector.getScaleFactor()));
            float appliedFactor = newScale / currentScale;
            currentScale = newScale;
            matrix.postScale(appliedFactor, appliedFactor, detector.getFocusX(), detector.getFocusY());
            clampTranslation();
            setImageMatrix(matrix);
            return true;
        }
    }
}
