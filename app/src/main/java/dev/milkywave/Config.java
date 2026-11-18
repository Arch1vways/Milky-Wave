package dev.milkywave;

import android.content.ContentResolver;
import android.provider.Settings;
import java.util.Locale;

/**
 * Module settings, stored as one {@code Settings.Secure} string ("k=v;k=v").
 * The settings app sends the string to System UI by broadcast (see {@link ConfigReceiver}); System UI stores it
 * in Settings.Secure so it survives reboots, and views follow changes through a ContentObserver.
 */
final class Config {
    static final String KEY = "milkywave_cfg";

    static final int STYLE_BARS = 0, STYLE_WAVE = 1;
    static final int COLOR_ALBUM = 0, COLOR_SYSTEM = 1, COLOR_CUSTOM = 2;
    /** Seed colours offered in the settings app (Material baseline-like hues). */
    static final int[] SWATCHES = {
            0xFF6750A4, 0xFF0061A4, 0xFF006A6A, 0xFF3F6837,
            0xFF7D5700, 0xFF8F4C38, 0xFFB3261E, 0xFF984061
    };

    static final int ALPHA_MIN = 0;

    boolean enabled = true;
    int style = STYLE_BARS;
    int colorSource = COLOR_ALBUM;
    int customColor = SWATCHES[0];
    int heightPct = 30;
    int gainPct = 100;
    int alphaPct = 75;
    int bars = 32;

    static Config parse(String s) {
        Config c = new Config();
        if (s == null || s.isEmpty()) return c;
        for (String part : s.split(";")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            String k = part.substring(0, eq).trim();
            String v = part.substring(eq + 1).trim();
            try {
                switch (k) {
                    case "enabled": c.enabled = flag(v); break;
                    case "style": c.style = clamp(Integer.parseInt(v), 0, 1); break;
                    case "color": c.colorSource = clamp(Integer.parseInt(v), 0, 2); break;
                    case "custom": c.customColor = 0xFF000000 | (int) (Long.parseLong(v, 16) & 0xFFFFFF); break;
                    case "height": c.heightPct = clamp(Integer.parseInt(v), 10, 60); break;
                    case "gain": c.gainPct = clamp(Integer.parseInt(v), 30, 300); break;
                    case "alpha": c.alphaPct = clamp(Integer.parseInt(v), ALPHA_MIN, 100); break;
                    case "bars": c.bars = clamp(Integer.parseInt(v), 8, 64); break;
                    default: break; // unknown keys are ignored (forward compatibility)
                }
            } catch (NumberFormatException ignored) {
                // keep the default for this key
            }
        }
        return c;
    }

    String serialize() {
        return String.format(Locale.ROOT,
                "enabled=%d;style=%d;color=%d;custom=%06x;height=%d;gain=%d;alpha=%d;bars=%d",
                enabled ? 1 : 0, style, colorSource, customColor & 0xFFFFFF, heightPct, gainPct, alphaPct, bars);
    }

    static Config read(ContentResolver cr) {
        try {
            return parse(Settings.Secure.getString(cr, KEY));
        } catch (Throwable t) {
            return new Config();
        }
    }

    /**
     * Parses a user-typed colour: "RGB", "RRGGBB", optionally with a leading '#' and surrounding spaces.
     *
     * @return opaque ARGB colour, or {@code null} when the text is not a complete colour
     */
    static Integer parseHex(String text) {
        if (text == null) return null;
        String h = text.trim();
        if (h.startsWith("#")) h = h.substring(1);
        if (h.length() == 3) {
            h = new String(new char[]{h.charAt(0), h.charAt(0), h.charAt(1), h.charAt(1), h.charAt(2), h.charAt(2)});
        }
        if (h.length() != 6) return null;
        for (int i = 0; i < 6; i++) {
            if (Character.digit(h.charAt(i), 16) < 0) return null;
        }
        return 0xFF000000 | Integer.parseInt(h, 16);
    }

    static String hex(int color) {
        return String.format(Locale.ROOT, "#%06X", color & 0xFFFFFF);
    }

    private static boolean flag(String v) {
        return "1".equals(v) || "true".equalsIgnoreCase(v);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
