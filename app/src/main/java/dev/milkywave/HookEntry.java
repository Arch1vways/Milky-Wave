package dev.milkywave;

import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedInterface.Hooker;
import io.github.libxposed.api.XposedModule;

/**
 * LSPosed entry point (libxposed API 102): puts the {@link WaveView} into the OriginOS lock screen.
 *
 * Primary host: the vivo keyguard root ({@code mMainContents}, or the root itself when that container is missing or
 * would push its children around). Fallback host: System UI's shade window, right above the behind-scrim. The
 * fallback copy stays idle while a lock screen copy is visible, so exactly one visualizer is ever drawn.
 */
public final class HookEntry extends XposedModule {
    private static final String SYSTEMUI = "com.android.systemui";
    private static final String PLUGIN_PKG = "com.vivo.systemuiplugin";
    /** OriginOS lock screen root (loaded lazily from the vivo System UI plugin). Class and field names come from OriginCeiler. */
    private static final String KEYGUARD = "com.vivo.systemuiplugin.keyguard.legacy.keyguard.views.keyguardstyleos5.KeyguardOsView";
    /** Window root of the lock screen and the shade (Android 13+ / Android 12). */
    private static final String[] SHADE_WINDOWS = {
            "com.android.systemui.shade.NotificationShadeWindowView",
            "com.android.systemui.statusbar.phone.NotificationShadeWindowView"};
    /** Attempts that wait for mMainContents before the keyguard root itself is used. */
    private static final int WAIT_FOR_MAIN = 3;

    private static final AtomicBoolean WATCHING = new AtomicBoolean();
    private static final Set<Class<?>> INSTALLED = Collections.newSetFromMap(new WeakHashMap<Class<?>, Boolean>());
    private static final WeakHashMap<View, WaveView> VIEWS = new WeakHashMap<>();
    private static final WeakHashMap<View, WaveView> FALLBACK = new WeakHashMap<>();

    @Override public void onModuleLoaded(ModuleLoadedParam param) {
        xposed = this;
    }

    @Override public void onPackageReady(PackageReadyParam param) {
        String pkg = param.getPackageName();
        if (!SYSTEMUI.equals(pkg) && !PLUGIN_PKG.equals(pkg)) return;
        ClassLoader cl = param.getClassLoader();
        try {
            install(Class.forName(KEYGUARD, false, cl), false);
        } catch (Throwable ignored) {
            // not loaded in this class loader yet; the loadClass watcher below catches it later
        }
        if (SYSTEMUI.equals(pkg)) {
            for (String name : SHADE_WINDOWS) {
                try {
                    install(Class.forName(name, false, cl), true);
                } catch (Throwable ignored) {
                    // other Android version
                }
            }
        }
        if (WATCHING.compareAndSet(false, true)) {
            try {
                interceptMethods(ClassLoader.class, "loadClass", chain -> {
                    Object result = chain.proceed();
                    if (result instanceof Class && KEYGUARD.equals(((Class<?>) result).getName())) {
                        try {
                            install((Class<?>) result, false);
                        } catch (Throwable t) {
                            Log.e(HOOK_TAG, "class watcher", t);
                        }
                    }
                    return result;
                });
            } catch (Throwable ignored) {
                // without the watcher only classes already loaded are hooked
            }
        }
    }

    private static void install(Class<?> type, final boolean shade) {
        synchronized (INSTALLED) {
            if (!INSTALLED.add(type)) return;
        }
        Hooker onView = chain -> {
            Object result = chain.proceed();
            Object o = chain.getThisObject();
            if (o instanceof View) schedule((View) o, shade);
            return result;
        };
        try {
            interceptConstructors(type, onView);
        } catch (Throwable ignored) {
            // the lifecycle hooks below still cover attachment
        }
        for (String method : new String[]{"onFinishInflate", "onAttachedToWindow", "onWindowVisibilityChanged"}) {
            try {
                interceptMethods(type, method, onView);
            } catch (Throwable ignored) {
                // skip this entry point
            }
        }
    }

    /** View.post() on a not-yet-attached view runs once it is attached, so this also covers the constructor hook. */
    private static void schedule(final View root, final boolean shade) {
        root.post(new Runnable() {
            @Override public void run() {
                if (shade) attachFallback(root);
                else attach(root, 0);
            }
        });
    }

    private static void attach(final View root, final int attempt) {
        try {
            ConfigStore.ensure(root.getContext());
            ViewGroup host = keyguardHost(root, attempt >= WAIT_FOR_MAIN);
            if (host == null) {
                if (attempt < 8) {
                    root.postDelayed(new Runnable() {
                        @Override public void run() {
                            attach(root, attempt + 1);
                        }
                    }, 250L * (attempt + 1));
                } else {
                    Log.w(HOOK_TAG, "no lock screen host in " + root.getClass().getName());
                }
                return;
            }
            synchronized (VIEWS) {
                WaveView wave = VIEWS.get(root);
                if (wave != null && wave.getParent() == host) return;
                if (wave != null && wave.getParent() instanceof ViewGroup) {
                    ((ViewGroup) wave.getParent()).removeView(wave);
                }
                wave = new WaveView(root.getContext(), false);
                host.addView(wave, 0, new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                VIEWS.put(root, wave);
            }
        } catch (Throwable t) {
            Log.e(HOOK_TAG, "attach", t);   // never break the lock screen because of the visualizer
        }
    }

    /** Shade window copy: placed right above the behind-scrim, below the panel with the clock and notifications. */
    private static void attachFallback(View root) {
        try {
            if (!(root instanceof ViewGroup)) return;
            ViewGroup host = (ViewGroup) root;
            ConfigStore.ensure(host.getContext());
            synchronized (VIEWS) {
                WaveView wave = FALLBACK.get(host);
                if (wave != null && wave.getParent() == host) return;
                if (wave != null && wave.getParent() instanceof ViewGroup) {
                    ((ViewGroup) wave.getParent()).removeView(wave);
                }
                wave = new WaveView(host.getContext(), false);
                wave.setFallback(true);
                host.addView(wave, fallbackIndex(host), new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                FALLBACK.put(host, wave);
            }
        } catch (Throwable t) {
            Log.e(HOOK_TAG, "fallback attach", t);
        }
    }

    private static int fallbackIndex(ViewGroup host) {
        try {
            int id = host.getResources().getIdentifier("scrim_behind", "id", SYSTEMUI);
            View scrim = id != 0 ? host.findViewById(id) : null;
            if (scrim != null && scrim.getParent() == host) return host.indexOfChild(scrim) + 1;
        } catch (Throwable ignored) {
            // no scrim id: bottom of the window
        }
        return 0;
    }

    /**
     * mMainContents when it can hold a full-size layer; otherwise the keyguard root (right away when the field does
     * not exist on this firmware, after a short wait when it is still empty).
     */
    private static ViewGroup keyguardHost(View root, boolean allowRoot) {
        ViewGroup main = null;
        boolean hasField = true;
        try {
            Object o = field(root, "mMainContents");
            if (o instanceof ViewGroup) main = (ViewGroup) o;
        } catch (NoSuchFieldException e) {
            hasField = false;
        } catch (Exception ignored) {
            // unreadable: treat as not inflated yet
        }
        if (main != null && canHost(main)) return main;
        if ((!hasField || allowRoot || main != null) && root instanceof ViewGroup && canHost((ViewGroup) root)) {
            return (ViewGroup) root;
        }
        return null;
    }

    /** A MATCH_PARENT child at index 0 would push the content of these containers away. */
    private static boolean canHost(ViewGroup g) {
        return !(g instanceof LinearLayout) && !(g instanceof AdapterView)
                && !(g instanceof ScrollView) && !(g instanceof HorizontalScrollView)
                && !g.getClass().getName().endsWith("RecyclerView");
    }

    private static Object field(Object instance, String name) throws Exception {
        for (Class<?> c = instance.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(instance);
            } catch (NoSuchFieldException ignored) {
                // try the superclass
            }
        }
        throw new NoSuchFieldException(name);
    }

    // ------------------------------------------------------------------ libxposed API 102 hooks

    private static final String HOOK_TAG = "MilkyWave";

    /** Framework interface of the loaded module, set in onModuleLoaded. */
    static volatile XposedInterface xposed;

    /** Intercepts one method or constructor: {@code hook(target).intercept(hooker)}. */
    static HookHandle intercept(Executable target, Hooker hooker) {
        XposedInterface x = xposed;
        if (x == null) throw new IllegalStateException("Xposed module is not loaded");
        return x.hook(target)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(hooker);
    }

    /** Every non-abstract method with this name declared by {@code type}; fails only if none could be hooked. */
    static List<HookHandle> interceptMethods(Class<?> type, String name, Hooker hooker) {
        List<Executable> targets = new ArrayList<>();
        for (Method m : type.getDeclaredMethods()) {
            if (m.getName().equals(name) && !Modifier.isAbstract(m.getModifiers())) targets.add(m);
        }
        return interceptAll(targets, hooker);
    }

    /** Every constructor declared by {@code type}; fails only if none could be hooked. */
    static List<HookHandle> interceptConstructors(Class<?> type, Hooker hooker) {
        List<Executable> targets = new ArrayList<>();
        for (Constructor<?> c : type.getDeclaredConstructors()) targets.add(c);
        return interceptAll(targets, hooker);
    }

    private static List<HookHandle> interceptAll(List<Executable> targets, Hooker hooker) {
        List<HookHandle> out = new ArrayList<>(targets.size());
        Throwable failure = null;
        for (Executable e : targets) {
            try {
                out.add(intercept(e, hooker));
            } catch (Throwable t) {
                failure = t;
                hookError("hook " + e, t);
            }
        }
        if (out.isEmpty() && failure != null) {
            if (failure instanceof Error) throw (Error) failure;
            throw failure instanceof RuntimeException ? (RuntimeException) failure : new RuntimeException(failure);
        }
        return out;
    }

    private static void hookError(String what, Throwable t) {
        XposedInterface x = xposed;
        if (x != null) x.log(Log.ERROR, HOOK_TAG, what, t);
        else Log.e(HOOK_TAG, what, t);
    }
}
