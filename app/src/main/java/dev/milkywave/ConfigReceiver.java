package dev.milkywave;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;

/**
 * Runs inside System UI. Receives the settings string from the milkywave app and answers the ordered broadcast
 * with RESULT_OK, which the app uses as its "module is active" check.
 */
final class ConfigReceiver extends BroadcastReceiver {
    static final String ACTION = "dev.milkywave.action.CONFIG";
    static final String EXTRA = "cfg";
    static final String EXTRA_ECHO = "cfg_echo";
    static final String EXTRA_WALLPAPER = "wallpaper_avg";
    private static final String APP = BuildConfig.APPLICATION_ID;

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) return;
        // Android 14+: the app shares its identity with the broadcast, so only our own app is accepted.
        if (Build.VERSION.SDK_INT >= 34 && !APP.equals(getSentFromPackage())) return;
        String raw = intent.getStringExtra(EXTRA);
        if (raw == null || raw.length() > 256) return;
        ConfigStore.update(context, Config.parse(raw));
        if (isOrderedBroadcast()) {
            Bundle result = getResultExtras(true);
            result.putString(EXTRA_ECHO, raw);
            // Wallpaper average for the app's preview; computed lazily, so the first answer may not have it yet.
            int avg = WallpaperAverage.get(context, null);
            if (avg != 0) result.putInt(EXTRA_WALLPAPER, avg);
            setResultCode(Activity.RESULT_OK);
        }
    }
}
