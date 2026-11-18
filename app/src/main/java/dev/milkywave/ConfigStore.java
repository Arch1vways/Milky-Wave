package dev.milkywave;

import android.annotation.SuppressLint;
import android.content.ContentResolver;
import android.content.Context;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

/** Process-wide, live copy of the settings for views running inside System UI. */
final class ConfigStore {
    private static volatile Config current = new Config();
    private static boolean registered;

    private ConfigStore() { }

    static Config get() {
        return current;
    }

    /** Applies new settings immediately and stores them (System UI may write Settings.Secure) to survive restarts. */
    static void update(Context context, Config c) {
        current = c;
        try {
            Settings.Secure.putString(context.getContentResolver(), Config.KEY, c.serialize());
        } catch (Throwable ignored) {
            // not persisted: the new values still apply until System UI restarts
        }
    }

    /**
     * Called from the lock screen hook. Restores stored settings and starts listening for the app.
     * The receiver must be exported: the broadcast comes from the settings app, a different process/UID.
     * Android 12-12L has no RECEIVER_EXPORTED flag, so the plain overload is the exported equivalent there.
     */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    static synchronized void ensure(Context context) {
        if (registered) return;
        registered = true;
        Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        final ContentResolver cr = app.getContentResolver();
        current = Config.read(cr);
        try {
            cr.registerContentObserver(Settings.Secure.getUriFor(Config.KEY), false,
                    new ContentObserver(new Handler(Looper.getMainLooper())) {
                        @Override public void onChange(boolean selfChange) {
                            Config c = Config.read(cr);
                            if (!c.serialize().equals(current.serialize())) current = c;
                        }
                    });
        } catch (Throwable ignored) {
            // changes made outside the app (adb) are picked up after a restart only
        }
        try {
            IntentFilter filter = new IntentFilter(ConfigReceiver.ACTION);
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(new ConfigReceiver(), filter, Context.RECEIVER_EXPORTED);
            } else {
                app.registerReceiver(new ConfigReceiver(), filter);
            }
        } catch (Throwable ignored) {
            // without the receiver the app cannot reach System UI
        }
    }
}
