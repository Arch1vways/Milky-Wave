package dev.milkywave;

import android.annotation.SuppressLint;
import android.app.KeyguardManager;
import android.app.WallpaperManager;
import android.os.Handler;
import android.os.Looper;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.media.AudioManager;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.View;

/**
 * Lock screen music visualizer, Material You styled.
 *
 * Live mode (inside System UI): draws only while the keyguard is locked, the screen is interactive and music is
 * active; the data comes from {@link AudioTap}. Demo mode (settings app preview): synthetic data, never shown on the
 * lock screen. The view is not clickable, so touches go through to the lock screen.
 *
 * A fallback copy (System UI shade window) only draws while no lock screen copy is visible. While active, the poll
 * restarts the frame loop if a frame was skipped (no size yet, lost invalidate), so the visualizer cannot freeze.
 */
@SuppressLint("ViewConstructor") // created in code only (settings preview and the System UI hook)
final class WaveView extends View {
    private static final int N = AudioTap.BANDS;

    private final boolean demo;
    private final float density;
    private final AudioManager audio;
    private final KeyguardManager keyguard;
    private final PowerManager power;
    private final AudioTap tap = AudioTap.get();

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path crest = new Path();
    private final Path fill = new Path();
    private final RectF rect = new RectF();

    private final float[] raw = new float[N];
    private final float[] lvl = new float[N];
    private final float[] lvlSlow = new float[N];

    private Config cfg = new Config();
    private boolean active;
    /** Shade-window copy: draws only while no lock screen copy is visible (see HookEntry). */
    private boolean fallback;
    private long drawnAt;
    private static int primaryAttached;          // main thread only
    private static long primaryHiddenSince;      // main thread only
    private float presence;
    private long lastFrame;
    private int lastSeed;

    // colours: current (animated) and target
    private int cp, cs, ct, tp, ts, tt;
    private boolean colorsInit;
    private boolean colorsMoving;
    private WallpaperManager wallpaperManager;
    /** Settings-app preview: wallpaper average reported back by System UI (0 = not known yet). */
    private int demoSystemColor;
    private final Runnable onWallpaperAverage = this::onWallpaperAverageChanged;

    private void onWallpaperAverageChanged() {
        if (cfg.colorSource == Config.COLOR_SYSTEM ||
                (cfg.colorSource == Config.COLOR_ALBUM && (demo || MediaColors.seed() == 0))) {
            rebuildScheme();
            invalidate();
        }
    }
    private final WallpaperManager.OnColorsChangedListener wallpaperListener = this::onWallpaperColorsChanged;

    private void onWallpaperColorsChanged(android.app.WallpaperColors colors, int which) {
        if (demo) {
            onWallpaperAverage.run();
        } else {
            WallpaperAverage.invalidate(getContext(), onWallpaperAverage);
        }
    }

    // cached shaders
    private Shader barShader, frontShader, backShader;
    private float sW = -1, sH = -1, sA = -1;
    private int sCp, sCs, sCt;

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!isAttachedToWindow()) return;
            boolean was = active;
            if (demo) {
                active = cfg.enabled && cfg.alphaPct > 0;
            } else {
                Config c = ConfigStore.get();
                if (c != cfg) {
                    cfg = c;
                    rebuildScheme();
                    invalidate();
                }
                boolean music = audio != null && audio.isMusicActive();
                boolean locked = keyguard != null && keyguard.isKeyguardLocked()
                        && power != null && power.isInteractive();
                boolean shown = isShown() && getWindowVisibility() == VISIBLE;
                long now = SystemClock.uptimeMillis();
                if (!fallback) {
                    if (!locked || shown) primaryHiddenSince = 0;
                    else if (primaryHiddenSince == 0) primaryHiddenSince = now;
                }
                boolean slot = !fallback || primaryAttached == 0
                        || (primaryHiddenSince != 0 && now - primaryHiddenSince > 2000);
                active = c.enabled && c.alphaPct > 0 && music && shown && locked && slot;
                tap.request(WaveView.this, active, getContext());
                // a skipped frame (no size yet, lost invalidate) stops the frame loop: start it again
                if (active && now - drawnAt > 300) invalidate();
            }
            if (active && !was && cfg.colorSource == Config.COLOR_SYSTEM) rebuildScheme(); // refreshes a stale average
            if (active != was) invalidate();
            postDelayed(this, 500);
        }
    };

    WaveView(Context c, boolean demo) {
        super(c);
        this.demo = demo;
        density = c.getResources().getDisplayMetrics().density;
        audio = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        keyguard = (KeyguardManager) c.getSystemService(Context.KEYGUARD_SERVICE);
        power = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        setClickable(false);
        setFocusable(false);
        setWillNotDraw(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);
        strokePaint.setStrokeWidth(2.5f * density);
        rebuildScheme();
    }

    /** System UI shade-window copy (HookEntry fallback host); call before the view is attached. */
    void setFallback(boolean f) {
        fallback = f;
    }

    /** Settings-app preview only. */
    void setDemoConfig(Config c) {
        cfg = c;
        rebuildScheme();
        invalidate();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!demo && !fallback) primaryAttached++;
        if (!demo) ConfigStore.ensure(getContext());
        try {
            wallpaperManager = WallpaperManager.getInstance(getContext());
            wallpaperManager.addOnColorsChangedListener(wallpaperListener, new Handler(Looper.getMainLooper()));
        } catch (RuntimeException ignored) {
            wallpaperManager = null;
        }
        rebuildScheme();
        removeCallbacks(poll);
        post(poll);
    }

    @Override protected void onDetachedFromWindow() {
        removeCallbacks(poll);
        if (wallpaperManager != null) {
            wallpaperManager.removeOnColorsChangedListener(wallpaperListener);
            wallpaperManager = null;
        }
        if (!demo && !fallback && primaryAttached > 0) primaryAttached--;
        if (!demo) tap.request(this, false, null);
        active = false;
        presence = 0f;
        lastFrame = 0;
        super.onDetachedFromWindow();
    }

    @Override protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        rebuildScheme();
        invalidate();
    }

    // ------------------------------------------------------------------ colours

    /** Settings-app preview only: the wallpaper average that System UI computed. */
    void setDemoSystemColor(int color) {
        if (color == demoSystemColor) return;
        demoSystemColor = color;
        if (cfg.colorSource == Config.COLOR_SYSTEM) {
            rebuildScheme();
            invalidate();
        }
    }

    /**
     * Album art keeps the tonal palette (primary to tertiary gradient). "System" and "Custom" draw one solid
     * colour: the wallpaper average (lightness lifted for legibility) or exactly the chosen hex value.
     */
    private void rebuildScheme() {
        if (cfg.colorSource == Config.COLOR_CUSTOM) {
            setTargets(0xFF000000 | cfg.customColor);
        } else if (cfg.colorSource == Config.COLOR_ALBUM && !demo && MediaColors.seed() != 0) {
            MaterialYou.Scheme s = MaterialYou.fromSeed(MediaColors.seed(), true);
            setTargets(s.primary, s.secondary, s.tertiary);
        } else {
            setTargets(MaterialYou.forLockScreen(systemColor()));
        }
    }

    private int systemColor() {
        Context c = getContext();
        int avg = demo ? demoSystemColor : WallpaperAverage.get(c, onWallpaperAverage);
        if (avg == 0) avg = WallpaperAverage.cached();
        if (avg == 0) avg = WallpaperAverage.fromWallpaperColors(c);
        return avg != 0 ? avg : 0xFFA6A6A6;
    }

    private void setTargets(int solid) {
        setTargets(solid, solid, solid);
    }

    private void setTargets(int primary, int secondary, int tertiary) {
        tp = primary;
        ts = secondary;
        tt = tertiary;
        if (!colorsInit) {
            cp = tp;
            cs = ts;
            ct = tt;
            colorsInit = true;
        }
    }

    private void animateColors(float dt) {
        if (cp == tp && cs == ts && ct == tt) {
            colorsMoving = false;
            return;
        }
        float k = 1f - (float) Math.exp(-dt / 260f);
        cp = step(cp, tp, k);
        cs = step(cs, ts, k);
        ct = step(ct, tt, k);
        colorsMoving = true;
    }

    private static int step(int cur, int target, float k) {
        int next = MaterialYou.lerp(cur, target, k);
        // lerp rounds, so make sure we eventually land on the target
        return next == cur ? target : next;
    }

    // ------------------------------------------------------------------ drawing

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        long now = SystemClock.uptimeMillis();
        float dt = lastFrame == 0 ? 16f : Math.min(50f, (float) (now - lastFrame));
        lastFrame = now;
        drawnAt = now;

        if (!demo) {
            Config c = ConfigStore.get();
            if (c != cfg) {
                cfg = c;
                rebuildScheme();
            }
            if (cfg.colorSource == Config.COLOR_ALBUM) {
                int seed = MediaColors.seed();
                if (seed != lastSeed) {
                    lastSeed = seed;
                    rebuildScheme();
                }
            }
        }

        presence += ((active ? 1f : 0f) - presence) * (1f - (float) Math.exp(-dt / 160f));
        if (!active && presence < 0.01f) presence = 0f;

        fillSource(now);
        smooth(dt);
        animateColors(dt);

        if (presence > 0f) {
            float alpha = presence * cfg.alphaPct / 100f;
            if (cfg.style == Config.STYLE_WAVE) drawWave(canvas, w, h, alpha);
            else drawBars(canvas, w, h, alpha);
        }

        if (active || presence > 0f || colorsMoving) postInvalidateOnAnimation();
        else lastFrame = 0;
    }

    private void fillSource(long now) {
        if (demo) {
            synthetic(raw, now, 0.9f);
            return;
        }
        tap.copyBands(raw);
    }

    private void smooth(float dt) {
        float gain = cfg.gainPct / 100f;
        float aFast = 1f - (float) Math.exp(-dt / 40f), dFast = 1f - (float) Math.exp(-dt / 220f);
        float aSlow = 1f - (float) Math.exp(-dt / 110f), dSlow = 1f - (float) Math.exp(-dt / 420f);
        for (int i = 0; i < N; i++) {
            float target = Math.min(1f, raw[i] * gain);
            lvl[i] += (target - lvl[i]) * (target > lvl[i] ? aFast : dFast);
            lvlSlow[i] += (target - lvlSlow[i]) * (target > lvlSlow[i] ? aSlow : dSlow);
        }
    }

    private void ensureShaders(float w, float h, float areaH, float margin) {
        if (w == sW && h == sH && areaH == sA && cp == sCp && cs == sCs && ct == sCt
                && barShader != null) return;
        sW = w;
        sH = h;
        sA = areaH;
        sCp = cp;
        sCs = cs;
        sCt = ct;
        barShader = new LinearGradient(margin, 0, w - margin, 0, cp, ct, Shader.TileMode.CLAMP);
        float top = h - areaH - 16f * density;
        frontShader = new LinearGradient(0, top, 0, h,
                new int[]{cp, MaterialYou.withAlpha(cs, 0x70), MaterialYou.withAlpha(cs, 0)},
                new float[]{0f, 0.6f, 1f}, Shader.TileMode.CLAMP);
        backShader = new LinearGradient(0, top, 0, h,
                new int[]{ct, MaterialYou.withAlpha(ct, 0)}, null, Shader.TileMode.CLAMP);
    }

    /** Rounded "pill" bars, idle state is a row of dots (Material 3 style). */
    private void drawBars(Canvas c, float w, float h, float alpha) {
        int n = cfg.bars;
        float margin = 20f * density;
        float bottom = h - 28f * density;
        float areaH = h * cfg.heightPct / 100f;
        float slot = (w - 2f * margin) / n;
        float barW = Math.min(slot * 0.62f, 16f * density);
        float minH = barW;
        ensureShaders(w, h, areaH, margin);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(barShader);
        paint.setAlpha(Math.round(255f * Math.min(1f, alpha)));
        for (int i = 0; i < n; i++) {
            float pos = n == 1 ? 0f : i * (N - 1f) / (n - 1f);
            float bh = minH + (areaH - minH) * sample(lvl, pos);
            float x = margin + slot * i + (slot - barW) / 2f;
            rect.set(x, bottom - bh, x + barW, bottom);
            c.drawRoundRect(rect, barW / 2f, barW / 2f, paint);
        }
        paint.setShader(null);
    }

    /** Two layered, smoothly filled waves with a crest line. */
    private void drawWave(Canvas c, float w, float h, float alpha) {
        float margin = 20f * density;
        float base = h - 16f * density;
        float areaH = h * cfg.heightPct / 100f;
        ensureShaders(w, h, areaH, margin);
        paint.setStyle(Paint.Style.FILL);

        buildCrest(crest, lvlSlow, w, base, areaH * 0.85f);
        closeFill(w, h);
        paint.setShader(backShader);
        paint.setAlpha(Math.round(255f * Math.min(1f, alpha * 0.6f)));
        c.drawPath(fill, paint);

        buildCrest(crest, lvl, w, base, areaH);
        closeFill(w, h);
        paint.setShader(frontShader);
        paint.setAlpha(Math.round(255f * Math.min(1f, alpha)));
        c.drawPath(fill, paint);
        paint.setShader(null);

        strokePaint.setColor(cp);
        strokePaint.setAlpha(Math.round(255f * Math.min(1f, alpha)));
        c.drawPath(crest, strokePaint);
    }

    private void closeFill(float w, float h) {
        fill.set(crest);
        fill.lineTo(w, h);
        fill.lineTo(0, h);
        fill.close();
    }

    private static void buildCrest(Path p, float[] src, float w, float base, float amp) {
        p.reset();
        float px = 0f, py = 0f;
        for (int i = 0; i < N; i++) {
            float x = w * i / (N - 1f);
            float env = 0.45f + 0.55f * (float) Math.sin(Math.PI * i / (N - 1f));
            float y = base - src[i] * amp * env;
            if (i == 0) {
                p.moveTo(x, y);
            } else {
                p.quadTo(px, py, (px + x) / 2f, (py + y) / 2f);
            }
            px = x;
            py = y;
        }
        p.lineTo(px, py);
    }

    private static float sample(float[] a, float pos) {
        int i = Math.max(0, Math.min(a.length - 1, (int) pos));
        int j = Math.min(a.length - 1, i + 1);
        float f = pos - i;
        return a[i] + (a[j] - a[i]) * f;
    }

    /** Procedural motion, used only by the settings preview. */
    private static void synthetic(float[] out, long ms, float amp) {
        float t = ms / 1000f;
        for (int i = 0; i < out.length; i++) {
            float x = i / (out.length - 1f);
            float a = 0.5f + 0.5f * (float) Math.sin(t * 1.9f + x * 6.5f);
            float b = 0.5f + 0.5f * (float) Math.sin(t * 3.1f - x * 11.0f + 1.3f);
            float c = 0.5f + 0.5f * (float) Math.sin(t * 0.7f + x * 2.3f + 2.0f);
            float v = (0.45f * a + 0.35f * b + 0.2f * c) * (1f - 0.35f * x);
            out[i] = Math.max(0f, Math.min(1f, v * amp));
        }
    }
}
