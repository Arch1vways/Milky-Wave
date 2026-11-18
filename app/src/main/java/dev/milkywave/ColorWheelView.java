package dev.milkywave;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.view.MotionEvent;
import android.view.View;

/**
 * HSV colour wheel for the colour picker dialog: hue is the angle, saturation grows from the centre to the rim.
 * Brightness (value) is set from outside with {@link #setValue}; the wheel darkens accordingly so it always shows
 * the colours that can actually be picked.
 */
final class ColorWheelView extends View {
    interface Listener {
        void onColor(int argb);
    }

    private final float density;
    private final Paint sweepPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float hue;          // 0..360
    private float sat;          // 0..1
    private float value = 1f;   // 0..1
    private float cx, cy, radius;
    private Listener listener;

    ColorWheelView(Context c) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        thumbRing.setStyle(Paint.Style.STROKE);
        thumbRing.setStrokeWidth(4f * density);
        thumbRing.setColor(Color.WHITE);
        thumbRing.setShadowLayer(4f * density, 0f, 1f * density, 0x66000000);
        shadePaint.setColor(Color.BLACK);
        setLayerType(LAYER_TYPE_SOFTWARE, null);   // needed for the thumb shadow
        setContentDescription(c.getString(R.string.color_wheel));
    }

    void setListener(Listener l) {
        listener = l;
    }

    void setColor(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hue = hsv[0];
        sat = hsv[1];
        value = hsv[2];
        invalidate();
    }

    void setValue(float v) {
        value = Math.max(0f, Math.min(1f, v));
        invalidate();
        if (listener != null) listener.onColor(getColor());
    }

    float getValue() {
        return value;
    }

    int getColor() {
        return Color.HSVToColor(new float[]{hue, sat, value});
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        int size = Math.min(w, Math.round(300f * density));
        setMeasuredDimension(size, size);
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        cx = w / 2f;
        cy = h / 2f;
        radius = Math.min(w, h) / 2f - 16f * density;
        int[] hues = new int[7];
        for (int i = 0; i < 7; i++) hues[i] = Color.HSVToColor(new float[]{i * 60f, 1f, 1f});
        sweepPaint.setShader(new SweepGradient(cx, cy, hues, null));
        fadePaint.setShader(new RadialGradient(cx, cy, Math.max(1f, radius), Color.WHITE, 0x00FFFFFF, Shader.TileMode.CLAMP));
    }

    @Override protected void onDraw(Canvas canvas) {
        if (radius <= 0f) return;
        canvas.drawCircle(cx, cy, radius, sweepPaint);
        canvas.drawCircle(cx, cy, radius, fadePaint);
        shadePaint.setAlpha(Math.round(255 * (1f - value)));
        canvas.drawCircle(cx, cy, radius, shadePaint);
        double a = Math.toRadians(hue);
        float tx = cx + (float) Math.cos(a) * sat * radius;
        float ty = cy + (float) Math.sin(a) * sat * radius;
        thumbFill.setColor(getColor());
        canvas.drawCircle(tx, ty, 14f * density, thumbFill);
        canvas.drawCircle(tx, ty, 14f * density, thumbRing);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                float dx = e.getX() - cx, dy = e.getY() - cy;
                float deg = (float) Math.toDegrees(Math.atan2(dy, dx));
                hue = (deg + 360f) % 360f;
                sat = Math.min(1f, (float) Math.hypot(dx, dy) / radius);
                invalidate();
                if (listener != null) listener.onColor(getColor());
                return true;
            case MotionEvent.ACTION_UP:
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }

    @Override public boolean performClick() {
        return super.performClick();
    }
}
