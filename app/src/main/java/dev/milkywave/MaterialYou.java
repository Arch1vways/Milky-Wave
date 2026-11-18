package dev.milkywave;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;

/**
 * Material You colour roles.
 *
 * <ul>
 *   <li>{@link #averageColor}: the average colour of a wallpaper, used by the "System" colour mode.</li>
 *   <li>{@link #fromSeed}: builds a "TonalSpot"-like palette from any colour (album art, custom swatch).
 *       This is an approximation: tones and gamut mapping are done in OKLCH, not in Google's HCT/CAM16,
 *       so results are close to, but not identical with, Android's own dynamic colours.</li>
 * </ul>
 */
final class MaterialYou {
    private MaterialYou() { }

    static final class Scheme {
        int primary, onPrimary, primaryContainer, onPrimaryContainer;
        int secondary, secondaryContainer, onSecondaryContainer;
        int tertiary;
        int surface, surfaceContainer, onSurface, onSurfaceVariant, outline;
    }

    static boolean isNight(Context c) {
        int mode = c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    // ---------------------------------------------------------------- wallpaper colour

    /**
     * Average of the given ARGB pixels, computed in linear light so bright and dark areas mix like real light
     * (a plain sRGB average comes out too dark and muddy). Fully transparent pixels are ignored.
     *
     * @return the opaque average colour, or 0 when there is nothing to average
     */
    static int averageColor(int[] pixels) {
        double r = 0, g = 0, b = 0, weight = 0;
        for (int px : pixels) {
            int a = Color.alpha(px);
            if (a == 0) continue;
            double w = a / 255.0;
            r += decode(Color.red(px)) * w;
            g += decode(Color.green(px)) * w;
            b += decode(Color.blue(px)) * w;
            weight += w;
        }
        if (weight == 0) return 0;
        return Color.rgb(encode(r / weight), encode(g / weight), encode(b / weight));
    }

    /**
     * Keeps the hue and saturation of {@code color} but moves its OKLab lightness into
     * [{@code minL}, {@code maxL}], so a dark or washed-out average still reads on the lock screen.
     */
    static int clampLightness(int color, double minL, double maxL) {
        double[] lch = toOklch(color);
        double l = Math.max(minL, Math.min(maxL, lch[0]));
        if (l == lch[0]) return 0xFF000000 | color;
        double c = lch[1];
        if (!inGamut(l, c, lch[2])) {
            double lo = 0, hi = c;
            for (int i = 0; i < 20; i++) {
                double mid = (lo + hi) / 2;
                if (inGamut(l, mid, lch[2])) lo = mid; else hi = mid;
            }
            c = lo;
        }
        double[] rgb = linearRgb(l, c, lch[2]);
        return Color.rgb(encode(rgb[0]), encode(rgb[1]), encode(rgb[2]));
    }

    /** Lightness window used for wallpaper colours on the lock screen. */
    static int forLockScreen(int color) {
        return clampLightness(color, 0.68, 0.93);
    }

    // ---------------------------------------------------------------- seed palette

    static Scheme fromSeed(int seed, boolean dark) {
        double[] lch = toOklch(seed);
        double hue = lch[2];
        // Near-grey seeds (monochrome covers) have no meaningful hue: keep the palette almost neutral.
        boolean mono = lch[1] < 0.025;
        double cPrimary = mono ? 0.02 : Math.max(0.13, Math.min(lch[1], 0.19));
        double cSecondary = mono ? 0.012 : 0.043;
        double cTertiary = mono ? 0.02 : 0.065;
        double cNeutral = mono ? 0.004 : 0.011;
        double cNeutralV = mono ? 0.008 : 0.022;
        double hTertiary = hue + 60;

        Scheme s = new Scheme();
        if (dark) {
            s.primary = tone(hue, cPrimary, 80);
            s.onPrimary = tone(hue, cPrimary, 20);
            s.primaryContainer = tone(hue, cPrimary, 30);
            s.onPrimaryContainer = tone(hue, cPrimary, 90);
            s.secondary = tone(hue, cSecondary, 80);
            s.secondaryContainer = tone(hue, cSecondary, 30);
            s.onSecondaryContainer = tone(hue, cSecondary, 90);
            s.tertiary = tone(hTertiary, cTertiary, 80);
            s.surface = tone(hue, cNeutral, 6);
            s.surfaceContainer = tone(hue, cNeutral, 12);
            s.onSurface = tone(hue, cNeutral, 90);
            s.onSurfaceVariant = tone(hue, cNeutralV, 80);
            s.outline = tone(hue, cNeutralV, 60);
        } else {
            s.primary = tone(hue, cPrimary, 40);
            s.onPrimary = Color.WHITE;
            s.primaryContainer = tone(hue, cPrimary, 90);
            s.onPrimaryContainer = tone(hue, cPrimary, 10);
            s.secondary = tone(hue, cSecondary, 40);
            s.secondaryContainer = tone(hue, cSecondary, 90);
            s.onSecondaryContainer = tone(hue, cSecondary, 10);
            s.tertiary = tone(hTertiary, cTertiary, 40);
            s.surface = tone(hue, cNeutral, 98);
            s.surfaceContainer = tone(hue, cNeutral, 94);
            s.onSurface = tone(hue, cNeutral, 10);
            s.onSurfaceVariant = tone(hue, cNeutralV, 30);
            s.outline = tone(hue, cNeutralV, 50);
        }
        return s;
    }

    // ---------------------------------------------------------------- colour helpers

    static int lerp(int a, int b, float t) {
        return Color.argb(
                Math.round(Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t),
                Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    // ---------------------------------------------------------------- OKLCH maths

    /** CIE L* tone (0..100) to OKLab lightness. Exact for greys, a good approximation for chromatic colours. */
    private static double toneToL(double tone) {
        if (tone >= 8.0) return (tone + 16.0) / 116.0;
        return Math.cbrt(tone / 903.3);
    }

    private static int tone(double hue, double chroma, double tone) {
        if (tone <= 0) return Color.BLACK;
        if (tone >= 100) return Color.WHITE;
        double l = toneToL(tone);
        double c = chroma;
        if (!inGamut(l, c, hue)) {
            double lo = 0, hi = c;
            for (int i = 0; i < 20; i++) {
                double mid = (lo + hi) / 2;
                if (inGamut(l, mid, hue)) lo = mid; else hi = mid;
            }
            c = lo;
        }
        double[] rgb = linearRgb(l, c, hue);
        return Color.rgb(encode(rgb[0]), encode(rgb[1]), encode(rgb[2]));
    }

    private static boolean inGamut(double l, double c, double hue) {
        double[] rgb = linearRgb(l, c, hue);
        for (double v : rgb) {
            if (v < -1e-4 || v > 1 + 1e-4) return false;
        }
        return true;
    }

    private static double[] linearRgb(double l, double c, double hueDeg) {
        double h = Math.toRadians(hueDeg);
        double a = c * Math.cos(h), b = c * Math.sin(h);
        double l_ = l + 0.3963377774 * a + 0.2158037573 * b;
        double m_ = l - 0.1055613458 * a - 0.0638541728 * b;
        double s_ = l - 0.0894841775 * a - 1.2914855480 * b;
        double lc = l_ * l_ * l_, mc = m_ * m_ * m_, sc = s_ * s_ * s_;
        return new double[]{
                4.0767416621 * lc - 3.3077115913 * mc + 0.2309699292 * sc,
                -1.2684380046 * lc + 2.6097574011 * mc - 0.3413193965 * sc,
                -0.0041960863 * lc - 0.7034186147 * mc + 1.7076147010 * sc};
    }

    /** @return {L, C, hue in degrees} of an sRGB colour in OKLCH. */
    static double[] toOklch(int color) {
        double r = decode(Color.red(color)), g = decode(Color.green(color)), b = decode(Color.blue(color));
        double l = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b);
        double m = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b);
        double s = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b);
        double lightness = 0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s;
        double a = 1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s;
        double bb = 0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s;
        double hue = Math.toDegrees(Math.atan2(bb, a));
        if (hue < 0) hue += 360;
        return new double[]{lightness, Math.hypot(a, bb), hue};
    }

    private static double decode(int v) {
        double c = v / 255.0;
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    private static int encode(double linear) {
        double v = Math.max(0, Math.min(1, linear));
        double e = v <= 0.0031308 ? 12.92 * v : 1.055 * Math.pow(v, 1 / 2.4) - 0.055;
        return (int) Math.round(255 * Math.max(0, Math.min(1, e)));
    }
}
