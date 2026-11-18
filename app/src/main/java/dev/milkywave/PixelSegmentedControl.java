package dev.milkywave;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.widget.LinearLayout;
import android.view.animation.DecelerateInterpolator;

/** PixelPlayer-style segmented control with a visible sliding tonal indicator. */
final class PixelSegmentedControl extends LinearLayout {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private int containerColor, indicatorColor;
    private float position;
    private ValueAnimator animator;

    PixelSegmentedControl(Context context) { this(context, null); }

    PixelSegmentedControl(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOrientation(HORIZONTAL);
        setWillNotDraw(false);
        int inset = dp(4);
        setPadding(inset, inset, inset, inset);
    }

    void setColors(MaterialYou.Scheme scheme) {
        containerColor = MaterialYou.lerp(scheme.surfaceContainer, scheme.onSurface, 0.10f);
        indicatorColor = scheme.secondaryContainer;
        invalidate();
    }

    void select(int index, boolean animate) {
        float target = Math.max(0, index);
        if (!animate || !isLaidOut()) {
            if (animator != null) animator.cancel();
            position = target;
            invalidate();
            return;
        }
        if (animator != null) animator.cancel();
        animator = ValueAnimator.ofFloat(position, target);
        animator.setDuration(280);
        animator.setInterpolator(new DecelerateInterpolator(1.7f));
        animator.addUpdateListener(value -> {
            position = (float) value.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float radius = getHeight() / 2f;
        paint.setColor(containerColor);
        rect.set(0, 0, getWidth(), getHeight());
        canvas.drawRoundRect(rect, radius, radius, paint);
        int count = getChildCount();
        if (count == 0) return;
        float contentWidth = getWidth() - getPaddingLeft() - getPaddingRight();
        float slot = contentWidth / count;
        float left = getPaddingLeft() + slot * position;
        rect.set(left, getPaddingTop(), left + slot, getHeight() - getPaddingBottom());
        paint.setColor(indicatorColor);
        canvas.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, paint);
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
