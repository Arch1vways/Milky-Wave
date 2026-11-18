package dev.milkywave;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.audiofx.Visualizer;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Process-wide audio spectrum source.
 *
 * Reads the global output mix with {@code Visualizer(0)} on its own thread (so no binder calls happen on the
 * System UI main thread), turns the FFT into {@link #BANDS} log-spaced bands in 0..1 and keeps them for the views.
 * When the output mix is refused (or stays silent) it attaches to the audio session of the player that is playing.
 * Never invents data: when capture is unavailable the bands stay at zero.
 *
 * Self-healing: every activation (screen on, music start) begins with a fresh retry budget, failed attempts are
 * retried within seconds (never minutes), a capture without callbacks is reopened after 1.5 s and a capture that
 * stays silent while music plays is attached again (after a route or offload change the effect can stay on an
 * output the music has left).
 */
final class AudioTap implements Visualizer.OnDataCaptureListener {
    static final int BANDS = 48;
    private static final String TAG = "MilkyWave";
    private static final long RETRY_FIRST = 400L, RETRY_MAX = 8000L;
    private static final long STALL_MS = 1500L;
    private static final long SILENCE_FIRST = 2500L, SILENCE_MAX = 20000L;
    private static final long COLORS_MS = 2000L;
    /** AudioPlaybackConfiguration.PLAYER_STATE_STARTED (system API). */
    private static final int PLAYER_STATE_STARTED = 2;
    private static final AudioTap INSTANCE = new AudioTap();

    static AudioTap get() {
        return INSTANCE;
    }

    private final Object lock = new Object();
    private final float[] bands = new float[BANDS];          // guarded by lock
    private final float[] scratch = new float[BANDS];        // capture thread only
    private final Set<Object> owners = Collections.newSetFromMap(new WeakHashMap<Object, Boolean>());
    private Handler handler;                                  // guarded by this
    private Context appContext;                               // guarded by this
    private volatile boolean wanted;

    // capture-thread state
    private Visualizer visualizer;
    private int session = -1;
    private boolean playersFirst;
    private long retryAt;
    private long backoff = RETRY_FIRST;
    private long openedAt;
    private long silenceLimit = SILENCE_FIRST;
    private long colorsAt;
    private String lastError;
    private float agc = 24f;
    private float[] mags = new float[512];

    private volatile long lastCallback;
    private volatile long lastSound;

    private AudioTap() { }

    /** Views call this from the main thread; capture runs while at least one view wants it. */
    synchronized void request(Object owner, boolean want, Context ctx) {
        if (want) {
            owners.add(owner);
            if (appContext == null && ctx != null) {
                appContext = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
            }
        } else {
            owners.remove(owner);
        }
        boolean should = !owners.isEmpty();
        if (should == wanted) return;
        wanted = should;
        if (should) {
            if (handler == null) {
                HandlerThread thread = new HandlerThread("OriginWave-audio");
                thread.start();
                handler = new Handler(thread.getLooper());
            }
            handler.removeCallbacks(tick);
            handler.post(fresh);
            handler.post(tick);
        } else if (handler != null) {
            handler.removeCallbacks(tick);
            handler.post(closeTask);
        }
    }

    /** Copies the current bands (zeros when no fresh frame has arrived). */
    void copyBands(float[] dst) {
        long now = SystemClock.uptimeMillis();
        synchronized (lock) {
            if (now - lastCallback > 600) Arrays.fill(dst, 0, BANDS, 0f);
            else System.arraycopy(bands, 0, dst, 0, BANDS);
        }
    }

    // ------------------------------------------------------------------ capture thread

    /** A new activation never inherits the waiting time of an old failure. */
    private final Runnable fresh = new Runnable() {
        @Override public void run() {
            retryAt = 0;
            backoff = RETRY_FIRST;
            silenceLimit = SILENCE_FIRST;
            colorsAt = 0;
        }
    };

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!wanted) return;
            long now = SystemClock.uptimeMillis();
            if (visualizer == null) {
                open(now);
            } else if (now - lastCallback > STALL_MS) {
                reopen(now, false);   // no callbacks: audio server restarted or the effect was dropped
            } else if (now - Math.max(lastSound, openedAt) > silenceLimit) {
                // views only ask while music plays: a long silence means the effect sits on the wrong output
                silenceLimit = Math.min(silenceLimit * 2, SILENCE_MAX);
                reopen(now, true);
            }
            Context c;
            synchronized (AudioTap.this) { c = appContext; }
            if (c != null && ConfigStore.get().colorSource == Config.COLOR_ALBUM && now - colorsAt >= COLORS_MS) {
                colorsAt = now;
                MediaColors.refresh(c);
            }
            long delay = visualizer != null ? 500L : Math.max(250L, retryAt - SystemClock.uptimeMillis());
            if (wanted && handler != null) handler.postDelayed(this, delay);
        }
    };

    private final Runnable closeTask = new Runnable() {
        @Override public void run() {
            closeVisualizer();
            synchronized (lock) { Arrays.fill(bands, 0f); }
        }
    };

    private void reopen(long now, boolean rotate) {
        if (rotate) playersFirst = session == 0;   // the output mix stayed silent: try the player's own session first
        closeVisualizer();
        retryAt = 0;
        open(now);
    }

    private void open(long now) {
        if (now < retryAt) return;
        Throwable failure = null;
        for (int s : sessions()) {
            Visualizer v = null;
            try {
                // Session 0 is the global output mix; a player session only carries that player.
                v = new Visualizer(s);
                int size = Visualizer.getCaptureSizeRange()[1];
                check(v.setCaptureSize(size));
                try {
                    v.setScalingMode(Visualizer.SCALING_MODE_NORMALIZED);
                } catch (Throwable ignored) {
                    // optional
                }
                check(v.setDataCaptureListener(this, Visualizer.getMaxCaptureRate(), false, true));
                check(v.setEnabled(true));
                visualizer = v;
                session = s;
                long t = SystemClock.uptimeMillis();
                lastCallback = t;
                openedAt = t;
                backoff = RETRY_FIRST;
                lastError = null;
                return;
            } catch (Throwable t) {
                failure = t;
                if (v != null) {
                    try { v.release(); } catch (Throwable ignored) { }
                }
            }
        }
        retryAt = SystemClock.uptimeMillis() + backoff;
        backoff = Math.min(backoff * 2, RETRY_MAX);
        String msg = String.valueOf(failure);
        if (!msg.equals(lastError)) {
            lastError = msg;
            Log.w(TAG, "audio capture unavailable: " + msg);
        }
    }

    /** Output mix first, then the sessions of players that are playing media (order flips after a silent mix). */
    private int[] sessions() {
        int[] players = playerSessions();
        int[] out = new int[players.length + 1];
        if (playersFirst) {
            System.arraycopy(players, 0, out, 0, players.length);
            out[players.length] = 0;
        } else {
            out[0] = 0;
            System.arraycopy(players, 0, out, 1, players.length);
        }
        return out;
    }

    /** System UI may read real session ids (MODIFY_AUDIO_ROUTING); anonymised entries report 0 and are skipped. */
    private int[] playerSessions() {
        Context c;
        synchronized (this) { c = appContext; }
        if (c == null) return new int[0];
        try {
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            List<AudioPlaybackConfiguration> list = am != null ? am.getActivePlaybackConfigurations() : null;
            if (list == null || list.isEmpty()) return new int[0];
            Method state = AudioPlaybackConfiguration.class.getMethod("getPlayerState");
            Method id = AudioPlaybackConfiguration.class.getMethod("getSessionId");
            int[] out = new int[list.size()];
            int n = 0;
            for (AudioPlaybackConfiguration p : list) {
                AudioAttributes a = p.getAudioAttributes();
                int usage = a != null ? a.getUsage() : AudioAttributes.USAGE_UNKNOWN;
                if (usage != AudioAttributes.USAGE_MEDIA && usage != AudioAttributes.USAGE_GAME
                        && usage != AudioAttributes.USAGE_UNKNOWN) continue;
                if ((Integer) state.invoke(p) != PLAYER_STATE_STARTED) continue;
                int s = (Integer) id.invoke(p);
                boolean dup = false;
                for (int i = 0; i < n; i++) dup |= out[i] == s;
                if (s > 0 && !dup) out[n++] = s;
            }
            return Arrays.copyOf(out, n);
        } catch (Throwable t) {
            return new int[0];
        }
    }

    private static void check(int result) {
        if (result != Visualizer.SUCCESS) throw new IllegalStateException("visualizer error " + result);
    }

    private void closeVisualizer() {
        Visualizer old = visualizer;
        visualizer = null;
        session = -1;
        if (old != null) {
            try { old.setEnabled(false); } catch (Throwable ignored) { }
            try { old.release(); } catch (Throwable ignored) { }
        }
    }

    @Override public void onWaveFormDataCapture(Visualizer v, byte[] waveform, int samplingRate) {
        // waveform capture is not requested
    }

    /**
     * FFT layout (Visualizer): [Re0, Re(n/2), Re1, Im1, Re2, Im2, ...], signed bytes.
     * Bin k has frequency k * samplingRate / captureSize.
     */
    @Override public void onFftDataCapture(Visualizer v, byte[] fft, int samplingRateMilliHz) {
        int n = fft.length;
        if (n < 16) return;
        int bins = n / 2;
        if (mags.length < bins) mags = new float[bins];
        float sr = samplingRateMilliHz > 0 ? samplingRateMilliHz / 1000f : 44100f;
        float binHz = sr / n;
        for (int k = 1; k < bins; k++) {
            float re = fft[2 * k];
            float im = fft[2 * k + 1];
            mags[k] = (float) Math.sqrt(re * re + im * im);
        }

        float fMin = 45f;
        float fMax = Math.min(14000f, sr / 2f * 0.95f);
        float ratio = fMax / fMin;
        float frameMax = 0f;
        for (int j = 0; j < BANDS; j++) {
            float f0 = fMin * (float) Math.pow(ratio, j / (float) BANDS);
            float f1 = fMin * (float) Math.pow(ratio, (j + 1f) / BANDS);
            float b0 = f0 / binHz, b1 = f1 / binHz;
            float m;
            if (b1 - b0 < 1f) {
                m = interpolate(mags, (b0 + b1) / 2f, bins);
            } else {
                int s = Math.max(1, (int) Math.ceil(b0));
                int e = Math.min(bins - 1, (int) Math.floor(b1));
                float sum = 0f;
                int cnt = 0;
                for (int k = s; k <= e; k++) { sum += mags[k]; cnt++; }
                m = cnt > 0 ? sum / cnt : 0f;
            }
            // gentle spectral tilt: music carries far less energy in the treble
            m *= 0.7f + 1.1f * j / (BANDS - 1f);
            scratch[j] = m;
            if (m > frameMax) frameMax = m;
        }

        long now = SystemClock.uptimeMillis();
        lastCallback = now;
        if (frameMax < 1.5f) {
            synchronized (lock) { Arrays.fill(bands, 0f); }
            return;
        }
        lastSound = now;
        silenceLimit = SILENCE_FIRST;
        agc = Math.max(frameMax, agc * 0.985f);
        float norm = Math.max(agc, 24f);
        synchronized (lock) {
            for (int j = 0; j < BANDS; j++) {
                float x = Math.min(1f, scratch[j] / norm);
                bands[j] = (float) Math.pow(x, 0.75);
            }
        }
    }

    private static float interpolate(float[] a, float pos, int bins) {
        int i = (int) pos;
        float f = pos - i;
        int i0 = Math.max(1, Math.min(bins - 1, i));
        int i1 = Math.max(1, Math.min(bins - 1, i + 1));
        return a[i0] + (a[i1] - a[i0]) * f;
    }
}
