package dev.milkywave;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.graphics.ImageDecoder;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;

/**
 * Promo GIF hidden above the settings list, behaviour close to iOS.
 *
 * <ul>
 *   <li><b>Closed</b> (rest): the list is at the top, the GIF is above the screen.</li>
 *   <li><b>Pull</b>: dragging down from the very top is <i>heavy</i>. The finger travel is not
 *       limited by the scroll range, the list follows it through a strong rubber-band curve:
 *       a long, deliberate pull is needed to show even a third of the GIF.</li>
 *   <li><b>Release</b>: pulled far enough - the GIF opens and stays, otherwise it goes back.
 *       Momentum (a fling) never reveals it.</li>
 *   <li><b>Open</b>: plain 1:1 platform scrolling, the GIF is ordinary content: no snapping, a
 *       fling carries it away together with the list.</li>
 *   <li><b>Restore</b>: as soon as the GIF has left the screen, everything is back to the closed
 *       state and the next pull is heavy again.</li>
 * </ul>
 *
 * The GIF is a real header inside the ScrollView. Pulling changes only the real scroll position;
 * neither the list nor the image is transformed. This is important because translating a scrolling
 * hardware layer can leave a stale, smaller-looking copy of the interface for a frame.
 */
final class PullDownPromo {
    /** The asset is 128x128 px; draw it three times larger. */
    private static final int GIF_SCALE = 3;
    private static final int GIF_PX = 128 * GIF_SCALE;
    /**
     * Rubber-band stiffness in header heights: shown = pull / (pull + RESISTANCE * header).
     * Bigger = heavier. 2.2 means the list moves about 45% of the finger at first, then less.
     */
    private static final float RESISTANCE = 2.2f;
    /** Release opens the promo when this share of the header is shown (needs a ~1 header pull). */
    private static final float OPEN_FRACTION = 0.30f;
    /** The GIF is fully opaque once this share of the header is shown. */
    private static final float ALPHA_FULL = 0.35f;
    /** Start only once a visible sliver is present; keep playing until fully hidden. */
    private static final float GIF_START_PROGRESS = 0.01f;
    private static final long OPEN_MS = 300L;
    private static final long CLOSE_MS = 220L;
    private static final long INERTIA_IDLE_MS = 150L;
    private static final String[] LINKS = {
        "tg://resolve?domain=arch1place",
    };

    private PullDownPromo() { }

    static void install(Activity activity, ScrollView scroll, View content) {
        ImageView image = new ImageView(activity);
        // CENTER_INSIDE never upscales a drawable smaller than the view, which is why the
        // 128 px GIF stayed tiny. FIT_CENTER stretches it to the full view bounds.
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setAlpha(0f);
        image.setScaleX(1f);
        image.setScaleY(1f);
        image.setContentDescription("@arch1place");
        image.setOnClickListener(v -> openChannel(activity));

        // The GIF has to sit in the middle of the empty band that opens above the first card:
        // from the bottom of the status bar to the top of the card. The list's top padding
        // (status bar inset + 8 dp) belongs to that band below the header, so the GIF's top
        // margin is the same amount plus the inset. It is set while measuring, i.e. after the
        // insets have been applied and before the first frame, so nothing is laid out twice.
        FrameLayout header = new FrameLayout(activity) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                ViewGroup.LayoutParams lp = image.getLayoutParams();
                if (lp instanceof FrameLayout.LayoutParams) {
                    ((FrameLayout.LayoutParams) lp).topMargin = gifTopMargin(activity, content);
                }
                super.onMeasure(widthSpec, heightSpec);
            }
        };
        FrameLayout.LayoutParams imageParams = new FrameLayout.LayoutParams(
                GIF_PX, GIF_PX, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        imageParams.topMargin = gifTopMargin(activity, content);
        header.addView(image, imageParams);

        scroll.removeAllViews();
        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        column.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        column.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // Keep at least one header height of scroll range so the promo can go fully off-screen.
        // The real height is refined once the viewport and the settings content are measured.
        View bottomSpace = new View(activity);
        column.addView(bottomSpace, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, GIF_PX + dp(activity, 130)));
        scroll.addView(column, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        activity.setContentView(scroll);

        Controller controller = new Controller(activity, scroll, column, content, header, image, bottomSpace);
        controller.bind();
        controller.loadGif();
    }

    /** Distance from the top of the header to the GIF so it is centred in its visible band. */
    private static int gifTopMargin(Activity activity, View content) {
        int topPadding = content.getPaddingTop();               // status bar inset + 8 dp
        int inset = Math.max(0, topPadding - dp(activity, 8));
        return Math.max(dp(activity, 22), inset + topPadding);
    }

    /** Telegram app first; the web link opens in a browser when Telegram is not installed. */
    private static void openChannel(Activity activity) {
        for (String link : LINKS) {
            try {
                activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(link)));
                return;
            } catch (RuntimeException ignored) { }
        }
    }

    private static final class Controller {
        private final Activity activity;
        private final ScrollView scroll;
        private final LinearLayout column;
        private final View content;
        private final FrameLayout header;
        private final ImageView image;
        private final View bottomSpace;
        private final int touchSlop;

        /** A finger is on the list (as seen by the scroll view's touch listener). */
        private boolean tracking;
        /** The finger is pulling the closed promo open; we own the gesture. */
        private boolean pulling;
        /** Momentum after a finger lifted while closed: it must not reveal the promo. */
        private boolean inertia;
        /** Closed state (or being pulled open). Turns false once fully open, true again once gone. */
        private boolean heavy = true;
        private boolean initialized, gifRunning;
        /** Share of the header currently shown by a pull, 0..1 (0 = closed). */
        private float pullShown;
        /** Finger travel of the pull in px, before the rubber-band curve. */
        private float pullTravel;
        private float lastY, minY;
        /** Direction of the latest finger move; only a downward drag may reveal the promo. */
        private boolean fingerMovingDown;
        private ValueAnimator animator;

        private final Runnable inertiaEnd = () -> inertia = false;

        Controller(Activity activity, ScrollView scroll, LinearLayout column, View content,
                FrameLayout header, ImageView image, View bottomSpace) {
            this.activity = activity;
            this.scroll = scroll;
            this.column = column;
            this.content = content;
            this.header = header;
            this.image = image;
            this.bottomSpace = bottomSpace;
            this.touchSlop = ViewConfiguration.get(activity).getScaledTouchSlop();
        }

        void bind() {
            // The platform stretch/glow effect flashes the whole list when a fling reaches an
            // edge, and the heavy pull already provides the rubber band.
            scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
            scroll.setOnScrollChangeListener((v, x, y, oldX, oldY) -> onScrolled(y));
            scroll.setOnTouchListener((v, event) -> onTouch(event));
            scroll.getViewTreeObserver().addOnGlobalLayoutListener(this::onLayout);
            scroll.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                @Override public boolean onPreDraw() {
                    tryInitialize();
                    if (initialized) {
                        scroll.getViewTreeObserver().removeOnPreDrawListener(this);
                    }
                    return true;
                }
            });
        }

        private void onLayout() {
            updateBottomSpace();
            tryInitialize();
        }

        /** Grows the trailing spacer only when the page is too short to scroll one full header up. */
        private void updateBottomSpace() {
            int viewport = scroll.getHeight() - scroll.getPaddingTop() - scroll.getPaddingBottom();
            int contentHeight = content.getHeight();
            if (viewport <= 0 || contentHeight <= 0) return;
            int needed = Math.max(0, viewport - contentHeight);
            ViewGroup.LayoutParams params = bottomSpace.getLayoutParams();
            if (params.height != needed) {
                params.height = needed;
                bottomSpace.setLayoutParams(params);
            }
        }

        private void tryInitialize() {
            if (initialized) return;
            int height = header.getHeight();
            int viewport = scroll.getHeight() - scroll.getPaddingTop() - scroll.getPaddingBottom();
            if (height <= 0 || viewport <= 0) return;
            // Wait until the spacer has actually been laid out; otherwise scrollTo is clamped and
            // the promo would stay half-open forever.
            if (column.getHeight() - viewport < height) return;
            initialized = true;
            heavy = true;
            scroll.scrollTo(0, height);
            updateProgress();
        }

        // ------------------------------------------------------------------ touch

        /**
         * True only when the list is exactly at its top with the promo closed, so a pull may
         * start. Anywhere deeper in the list a downward finger move is ordinary scrolling; the
         * pull then begins when scrolling reaches the closed edge (see {@link #onScrolled}).
         */
        private boolean canPull() {
            int height = header.getHeight();
            return initialized && heavy && height > 0
                    && Math.abs(scroll.getScrollY() - height) <= 1;
        }

        private boolean onTouch(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    return beginGesture(e);
                case MotionEvent.ACTION_MOVE:
                    // A child may have taken the DOWN; the first MOVE we see starts the gesture.
                    if (!tracking && beginGesture(e)) return true;
                    return onMove(e);
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    return onRelease();
                default:
                    return pulling;
            }
        }

        /** Returns true when the finger grabbed a promo that is still animating. */
        private boolean beginGesture(MotionEvent e) {
            tracking = true;
            inertia = false;
            scroll.removeCallbacks(inertiaEnd);
            lastY = minY = e.getY();
            fingerMovingDown = false;
            if (animator != null) {
                animator.cancel();
                animator = null;
                if (initialized && heavy && scroll.getScrollY() < header.getHeight()) {
                    // Catch the promo in mid-animation at its real scroll position.
                    pullShown = visibleFraction();
                    pulling = true;
                    pullTravel = travelFor(pullShown);
                    return true;
                }
            }
            return false;
        }

        private boolean onMove(MotionEvent e) {
            float y = e.getY();
            if (y != lastY) fingerMovingDown = y > lastY;
            if (pulling) {
                pullTravel += y - lastY;
                lastY = y;
                if (pullTravel <= 0f) {
                    // Finger came back to where the pull started: give the gesture to the list.
                    pulling = false;
                    pullTravel = 0f;
                    applyPull(0f);
                    MotionEvent down = MotionEvent.obtain(e.getDownTime(), e.getEventTime(),
                            MotionEvent.ACTION_DOWN, e.getX(), y, e.getMetaState());
                    scroll.onTouchEvent(down);
                    down.recycle();
                    return false;
                }
                applyPull(shownFor(pullTravel));
                return true;
            }
            if (canPull()) {
                minY = Math.min(minY, y);
                if (y - minY > touchSlop) {
                    pulling = true;
                    pullTravel = y - minY - touchSlop;
                    lastY = y;
                    applyPull(shownFor(pullTravel));
                    return true;
                }
            } else {
                minY = y;
            }
            lastY = y;
            return false;
        }

        private boolean onRelease() {
            tracking = false;
            if (pulling) {
                pulling = false;
                float shown = pullShown;
                animatePull(shown, shown >= OPEN_FRACTION ? 1f : 0f);
                return true;
            }
            if (initialized && heavy && scroll.getScrollY() >= header.getHeight()) {
                // Lifted while closed (e.g. a flick down the list): momentum must not reveal it.
                inertia = true;
                scroll.removeCallbacks(inertiaEnd);
                scroll.postDelayed(inertiaEnd, INERTIA_IDLE_MS);
            }
            return false;
        }

        private void onScrolled(int y) {
            int height = header.getHeight();
            if (initialized && heavy && height > 0 && y < height && !pulling) {
                if (inertia && !tracking) {
                    // Momentum reached the promo: stop exactly at the closed edge.
                    scroll.scrollTo(0, height);
                    scroll.smoothScrollBy(0, 0); // replaces the running fling
                    return;
                }
                if (tracking && fingerMovingDown) {
                    // Native scrolling crossed the closed edge. Put it back at the edge and let
                    // the next finger movement enter the resistant path. Keeping the native
                    // offset here would make the first pull step jump open without resistance.
                    pulling = true;
                    scroll.scrollTo(0, height);
                    pullShown = 0f;
                    pullTravel = 0f;
                    updateProgress();
                    return;
                }
                if (!tracking && !inertia && animator == null && y <= 0) {
                    // Programmatic opening (also used by accessibility and tests).
                    heavy = false;
                }
            }
            updateProgress();
            if (inertia) {
                scroll.removeCallbacks(inertiaEnd);
                scroll.postDelayed(inertiaEnd, INERTIA_IDLE_MS);
            }
        }

        // ------------------------------------------------------------------ pull maths

        private float resistancePx() {
            return RESISTANCE * Math.max(1, header.getHeight());
        }

        /** Finger travel -> share of the header shown (rubber band, never reaches 1). */
        private float shownFor(float travel) {
            return travel / (travel + resistancePx());
        }

        /** Inverse of {@link #shownFor}. */
        private float travelFor(float shown) {
            float s = Math.min(shown, 0.999f);
            return resistancePx() * s / (1f - s);
        }

        private void applyPull(float shown) {
            pullShown = Math.max(0f, Math.min(1f, shown));
            int height = header.getHeight();
            if (height > 0) {
                scroll.scrollTo(0, Math.round(height * (1f - pullShown)));
            }
            updateProgress();
        }

        private void animatePull(float from, float to) {
            if (animator != null) animator.cancel();
            if (from == to) {
                finishPull(to >= 1f);
                return;
            }
            final boolean open = to >= 1f;
            ValueAnimator a = ValueAnimator.ofFloat(from, to);
            a.setDuration(open ? OPEN_MS : CLOSE_MS);
            a.setInterpolator(new DecelerateInterpolator(1.6f));
            a.addUpdateListener(v -> applyPull((float) v.getAnimatedValue()));
            animator = a;
            a.addListener(new android.animation.AnimatorListenerAdapter() {
                private boolean canceled;
                @Override public void onAnimationCancel(android.animation.Animator animation) {
                    canceled = true;
                }
                @Override public void onAnimationEnd(android.animation.Animator animation) {
                    if (canceled) return;
                    animator = null;
                    finishPull(open);
                }
            });
            a.start();
        }

        /** Ends a pull: closed again, or converted into a real open scroll position. */
        private void finishPull(boolean open) {
            pullTravel = 0f;
            if (open) {
                heavy = false;
                pullShown = 1f;
                scroll.scrollTo(0, 0);
            } else {
                heavy = true;
                pullShown = 0f;
                scroll.scrollTo(0, header.getHeight());
            }
            updateProgress();
        }

        // ------------------------------------------------------------------ visuals

        private void updateProgress() {
            if (!initialized) {
                image.setAlpha(0f);
                return;
            }
            int height = header.getHeight();
            if (height <= 0) return;
            int y = Math.max(0, Math.min(height, scroll.getScrollY()));
            float visible = 1f - y / (float) height;

            // Never transform the ScrollView child or the animated image. Combining a child
            // translation with ScrollView's hardware clipping can briefly draw an old display
            // list over the current one; scaling the GIF makes that stale copy look smaller.

            if (heavy) {
                pullShown = visible;
                if (y >= height) pullShown = 0f;
            } else if (y >= height) {
                // The open promo has completely left the viewport. Arm heavy pulling again.
                heavy = true;
                pullShown = 0f;
            }

            image.setAlpha(Math.min(1f, visible / ALPHA_FULL));
            updateGifState(visible);
        }

        private float visibleFraction() {
            int height = header.getHeight();
            if (height <= 0) return 0f;
            return Math.max(0f, Math.min(1f,
                    1f - scroll.getScrollY() / (float) height));
        }

        private void updateGifState(float progress) {
            if (progress > GIF_START_PROGRESS) {
                if (!gifRunning) {
                    gifRunning = true;
                    startGif();
                }
            } else if (progress <= 0f && gifRunning) {
                // Keep the current frame through tiny scroll fluctuations at the edge. Repeated
                // stop/start calls reset an animated drawable to its first frame and look like a
                // flash while the header is just entering or leaving the viewport.
                gifRunning = false;
                stopGif();
            }
        }

        void loadGif() {
            Thread loader = new Thread(() -> {
                try {
                    ImageDecoder.Source source = ImageDecoder.createSource(activity.getAssets(), "car.gif");
                    Drawable drawable = ImageDecoder.decodeDrawable(source);
                    image.post(() -> {
                        image.setImageDrawable(drawable);
                        gifRunning = false;
                        updateProgress();
                    });
                } catch (Throwable ignored) { }
            }, "Module promo GIF");
            loader.setDaemon(true);
            loader.start();
        }

        private void startGif() {
            Drawable drawable = image.getDrawable();
            if (drawable instanceof Animatable) ((Animatable) drawable).start();
        }

        private void stopGif() {
            Drawable drawable = image.getDrawable();
            if (drawable instanceof Animatable) ((Animatable) drawable).stop();
        }
    }

    private static int dp(Activity activity, float value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
