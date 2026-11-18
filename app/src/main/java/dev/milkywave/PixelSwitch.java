package dev.milkywave;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.AttributeSet;
import android.view.View;
import android.view.HapticFeedbackConstants;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.DecelerateInterpolator;

/** PixelPlayer-style Material switch: compact track and a check/close icon inside the morphing thumb. */
final class PixelSwitch extends View {
    interface Listener { void onCheckedChanged(boolean checked); }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path icon = new Path();
    private final RectF track = new RectF();
    private int primary, onPrimary, onSurface, surfaceVariant, outline;
    private boolean checked;
    private float progress;
    private Listener listener;
    private ValueAnimator animator;

    PixelSwitch(Context context) { this(context, null); }

    PixelSwitch(Context context, AttributeSet attrs) {
        super(context, attrs);
        setClickable(true);
        setFocusable(true);
        setMinimumWidth(dp(52));
        setMinimumHeight(dp(48));
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.OVAL);
        mask.setColor(0xFFFFFFFF);
        setBackground(new RippleDrawable(ColorStateList.valueOf(0x22000000), null, mask));
    }

    void setColors(MaterialYou.Scheme scheme) {
        primary = scheme.primary;
        onPrimary = scheme.onPrimary;
        onSurface = scheme.onSurface;
        surfaceVariant = MaterialYou.lerp(scheme.surfaceContainer, scheme.onSurface, 0.10f);
        outline = scheme.outline;
        invalidate();
    }

    void setChecked(boolean value, boolean animate) {
        if (checked == value && progress == (value ? 1f : 0f)) return;
        checked = value;
        float target = value ? 1f : 0f;
        if (!animate || !isLaidOut()) {
            if (animator != null) animator.cancel();
            progress = target;
            invalidate();
            return;
        }
        if (animator != null) animator.cancel();
        animator = ValueAnimator.ofFloat(progress, target);
        animator.setDuration(220);
        animator.setInterpolator(new DecelerateInterpolator(1.7f));
        animator.addUpdateListener(a -> {
            progress = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    boolean isChecked() { return checked; }

    void toggle() {
        setChecked(!checked, true);
        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
        if (listener != null) listener.onCheckedChanged(checked);
    }

    void setOnCheckedChangeListener(Listener value) { listener = value; }

    @Override public boolean performClick() {
        toggle();
        super.performClick();
        return true;
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(resolveSize(dp(52), widthMeasureSpec), resolveSize(dp(48), heightMeasureSpec));
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float left = (getWidth() - dp(52)) / 2f;
        float top = (getHeight() - dp(32)) / 2f;
        track.set(left, top, left + dp(52), top + dp(32));

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(MaterialYou.lerp(surfaceVariant, primary, progress));
        canvas.drawRoundRect(track, dp(16), dp(16), paint);
        if (progress < 0.5f) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            paint.setColor(outline);
            RectF border = new RectF(track);
            border.inset(dp(1), dp(1));
            canvas.drawRoundRect(border, dp(15), dp(15), paint);
        }

        float cx = left + dp(16) + dp(20) * progress;
        float cy = track.centerY();
        float radius = dp(8) + dp(4) * progress;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(MaterialYou.lerp(onSurface, onPrimary, progress));
        canvas.drawCircle(cx, cy, radius, paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.8f));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(MaterialYou.lerp(surfaceVariant, primary, progress));
        icon.reset();
        if (checked) {
            icon.moveTo(cx - dp(4.5f), cy);
            icon.lineTo(cx - dp(1.2f), cy + dp(3.2f));
            icon.lineTo(cx + dp(5.2f), cy - dp(3.8f));
        } else {
            icon.moveTo(cx - dp(3), cy - dp(3));
            icon.lineTo(cx + dp(3), cy + dp(3));
            icon.moveTo(cx + dp(3), cy - dp(3));
            icon.lineTo(cx - dp(3), cy + dp(3));
        }
        canvas.drawPath(icon, paint);
    }

    @Override public CharSequence getAccessibilityClassName() { return "android.widget.Switch"; }

    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName("android.widget.Switch");
        info.setCheckable(true);
        info.setChecked(checked);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
