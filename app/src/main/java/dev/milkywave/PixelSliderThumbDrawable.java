package dev.milkywave;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/** Circular PixelPlayer slider thumb that morphs into a vertical pill while it is pressed. */
final class PixelSliderThumbDrawable extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int color;
    private final int densitySize;
    private boolean pressed;

    PixelSliderThumbDrawable(int color, float density) {
        this.color = color;
        densitySize = Math.round(40 * density);
        paint.setColor(color);
    }

    @Override public void draw(Canvas canvas) {
        Rect b = getBounds();
        float cx = b.exactCenterX(), cy = b.exactCenterY();
        float density = densitySize / 40f;
        float width = (pressed ? 8 : 16) * density;
        float height = (pressed ? 32 : 16) * density;
        canvas.drawRoundRect(cx - width / 2, cy - height / 2, cx + width / 2, cy + height / 2,
                width / 2, width / 2, paint);
    }

    @Override protected boolean onStateChange(int[] state) {
        boolean next = false;
        for (int value : state) if (value == android.R.attr.state_pressed) { next = true; break; }
        if (pressed == next) return false;
        pressed = next;
        invalidateSelf();
        return true;
    }

    @Override public boolean isStateful() { return true; }
    @Override public int getIntrinsicWidth() { return densitySize; }
    @Override public int getIntrinsicHeight() { return densitySize; }
    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
    @Override public void setColorFilter(android.graphics.ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
}
