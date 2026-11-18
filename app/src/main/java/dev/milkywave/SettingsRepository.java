package dev.milkywave;

import android.app.Activity;
import android.app.BroadcastOptions;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;

/**
 * Data layer of the settings app: the single source of truth for {@link Config}.
 * Settings are kept in app preferences and delivered to System UI with an ordered broadcast; the module's answer
 * doubles as the "module is active" check and carries the wallpaper average for the preview.
 */
final class SettingsRepository {
    /** Result of one delivery to System UI. Called on the main thread. */
    interface Callback {
        /**
         * @param linked         System UI answered
         * @param wallpaperColor wallpaper average computed in System UI, or 0 if not known yet
         */
        void onDelivered(boolean linked, int wallpaperColor);
    }

    private static final String PREFS = "settings", KEY = "cfg";

    private final Context context;
    private final Handler main;

    SettingsRepository(Context context, Handler main) {
        this.context = context.getApplicationContext();
        this.main = main;
    }

    Config load() {
        String saved = prefs().getString(KEY, null);
        return saved != null ? Config.parse(saved) : Config.read(context.getContentResolver());
    }

    /** Persists locally, then hands the settings to System UI. */
    void save(Config cfg, Callback callback) {
        String serialized = cfg.serialize();
        prefs().edit().putString(KEY, serialized).apply();
        deliver(serialized, callback);
    }

    /** Sends the settings to System UI without changing anything locally (connection check). */
    void deliver(String serialized, Callback callback) {
        Intent i = new Intent(ConfigReceiver.ACTION)
                .setPackage("com.android.systemui")
                .putExtra(ConfigReceiver.EXTRA, serialized);
        BroadcastReceiver ack = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent in) {
                Bundle result = getResultExtras(false);
                // Ignore answers to outdated sends: a newer value is already on its way.
                String echo = result != null ? result.getString(ConfigReceiver.EXTRA_ECHO) : null;
                if (echo != null && !echo.equals(prefs().getString(KEY, echo))) return;
                boolean ok = getResultCode() == Activity.RESULT_OK;
                int wallpaper = ok && result != null ? result.getInt(ConfigReceiver.EXTRA_WALLPAPER, 0) : 0;
                callback.onDelivered(ok, wallpaper);
            }
        };
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                Bundle opts = BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle();
                context.sendOrderedBroadcast(i, null, opts, ack, main, Activity.RESULT_CANCELED, null, null);
            } else {
                context.sendOrderedBroadcast(i, null, ack, main, Activity.RESULT_CANCELED, null, null);
            }
        } catch (Throwable t) {
            callback.onDelivered(false, 0);
        }
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
