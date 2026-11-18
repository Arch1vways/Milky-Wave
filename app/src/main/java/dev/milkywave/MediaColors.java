package dev.milkywave;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import java.util.List;

/**
 * Picks a seed colour from the album art of the currently playing media session.
 * System UI holds MEDIA_CONTENT_CONTROL, so {@code getActiveSessions(null)} is allowed there.
 * Called from the audio thread only; the result is read from anywhere via {@link #seed()}.
 */
final class MediaColors {
    private static volatile int seed;          // 0 = unknown (ARGB colours always carry alpha, so never 0)
    private static String lastKey;
    private static int misses;
    private static boolean haveSeedForTrack;

    private MediaColors() { }

    static int seed() {
        return seed;
    }

    static void refresh(Context ctx) {
        try {
            MediaSessionManager msm = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (msm == null) return;
            List<MediaController> sessions = msm.getActiveSessions(null);
            MediaController playing = null;
            for (MediaController c : sessions) {
                PlaybackState st = c.getPlaybackState();
                if (st != null && st.getState() == PlaybackState.STATE_PLAYING) {
                    playing = c;
                    break;
                }
            }
            if (playing == null) return;
            MediaMetadata md = playing.getMetadata();
            if (md == null) return;

            String key = playing.getPackageName() + "|" + md.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
                    + "|" + md.getString(MediaMetadata.METADATA_KEY_TITLE)
                    + "|" + md.getString(MediaMetadata.METADATA_KEY_ALBUM);
            boolean sameTrack = key.equals(lastKey);
            if (sameTrack && (haveSeedForTrack || misses >= 4)) return;
            if (!sameTrack) {
                lastKey = key;
                misses = 0;
                haveSeedForTrack = false;
            }

            Bitmap art = findArt(ctx, md);
            if (art != null) {
                int s = dominant(art);
                if (s != 0) {
                    seed = s;
                    haveSeedForTrack = true;
                    return;
                }
            }
            misses++;
            if (misses >= 2) seed = 0;   // no artwork: fall back to the system palette
        } catch (Throwable t) {
            // no media session access or no artwork: keep the previous palette
        }
    }

    private static Bitmap findArt(Context ctx, MediaMetadata md) {
        Bitmap bmp = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (bmp == null) bmp = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (bmp == null) bmp = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        if (bmp != null) return bmp;
        String[] uriKeys = {
                MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
                MediaMetadata.METADATA_KEY_ART_URI,
                MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI};
        for (String key : uriKeys) {
            bmp = loadUri(ctx, md.getString(key));
            if (bmp != null) return bmp;
        }
        return null;
    }

    /** Only local schemes: no network access from System UI. */
    private static Bitmap loadUri(Context ctx, String uriString) {
        if (uriString == null || uriString.isEmpty()) return null;
        try {
            Uri uri = Uri.parse(uriString);
            String scheme = uri.getScheme();
            if (!"content".equals(scheme) && !"file".equals(scheme) && !"android.resource".equals(scheme)) return null;
            ImageDecoder.Source src = ImageDecoder.createSource(ctx.getContentResolver(), uri);
            return ImageDecoder.decodeBitmap(src, (decoder, info, source) -> {
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                decoder.setTargetSize(64, 64);
            });
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Dominant vivid colour: pixels are bucketed into 12 hue sectors, weighted by saturation and brightness.
     * Mostly grey artwork returns the average colour (the palette builder then goes monochrome).
     */
    static int dominant(Bitmap src) {
        Bitmap bmp = src;
        if (bmp.getConfig() == Bitmap.Config.HARDWARE) {
            bmp = bmp.copy(Bitmap.Config.ARGB_8888, false);
            if (bmp == null) return 0;
        }
        final int size = 32;
        Bitmap small = Bitmap.createScaledBitmap(bmp, size, size, true);
        int[] px = new int[size * size];
        small.getPixels(px, 0, size, 0, 0, size, size);

        double[] wsum = new double[12], rs = new double[12], gs = new double[12], bs = new double[12];
        double ar = 0, ag = 0, ab = 0;
        int counted = 0;
        float[] hsv = new float[3];
        for (int p : px) {
            if (Color.alpha(p) < 200) continue;
            counted++;
            ar += Color.red(p);
            ag += Color.green(p);
            ab += Color.blue(p);
            Color.colorToHSV(p, hsv);
            if (hsv[1] < 0.18f || hsv[2] < 0.18f) continue;
            double w = hsv[1] * (0.4 + hsv[2]);
            int sector = ((int) (hsv[0] / 30f)) % 12;
            wsum[sector] += w;
            rs[sector] += Color.red(p) * w;
            gs[sector] += Color.green(p) * w;
            bs[sector] += Color.blue(p) * w;
        }
        if (counted == 0) return 0;
        int best = 0;
        for (int i = 1; i < 12; i++) {
            if (wsum[i] > wsum[best]) best = i;
        }
        if (wsum[best] < counted * 0.03) {
            return Color.rgb((int) (ar / counted), (int) (ag / counted), (int) (ab / counted));
        }
        return Color.rgb((int) (rs[best] / wsum[best]), (int) (gs[best] / wsum[best]), (int) (bs[best] / wsum[best]));
    }
}
