package dev.milkywave;

/** Host-side tests for the pure colour and settings logic (no device needed). Run: tests/host/run.sh */
public class ColorTest {
    private static int checks;

    private static void eq(long expected, long actual, String what) {
        checks++;
        if (expected != actual) throw new AssertionError(what + ": expected " + Long.toHexString(expected) + ", got " + Long.toHexString(actual));
    }

    private static void ok(boolean cond, String what) {
        checks++;
        if (!cond) throw new AssertionError(what);
    }

    public static void main(String[] args) {
        // --- wallpaper average
        eq(0xFF336699L, MaterialYou.averageColor(new int[]{0xFF336699, 0xFF336699}) & 0xFFFFFFFFL, "uniform image");
        eq(0, MaterialYou.averageColor(new int[]{0x00FF0000, 0x00000000}), "transparent pixels are ignored");
        eq(0, MaterialYou.averageColor(new int[0]), "empty image");
        int bw = MaterialYou.averageColor(new int[]{0xFFFFFFFF, 0xFF000000});
        ok((bw & 0xFF) > 0xB0, "linear-light average of black+white is a light grey, got " + Integer.toHexString(bw));
        int half = MaterialYou.averageColor(new int[]{0xFFFF0000, 0x00FF0000, 0xFF0000FF});
        eq(MaterialYou.averageColor(new int[]{0xFFFF0000, 0xFF0000FF}), half, "alpha-weighted average");

        // --- lock-screen legibility keeps the hue, lifts dark colours
        int dark = 0xFF102040;
        int lifted = MaterialYou.forLockScreen(dark);
        double[] a = MaterialYou.toOklch(dark), b = MaterialYou.toOklch(lifted);
        ok(b[0] >= 0.679, "dark average is lifted, L=" + b[0]);
        ok(Math.abs(a[2] - b[2]) < 3, "hue is kept: " + a[2] + " vs " + b[2]);
        int light = 0xFFD8E2FF;
        eq(light, MaterialYou.forLockScreen(light), "already legible colour is unchanged");

        // --- custom hex input
        eq(0xFF12ABEFL, Config.parseHex("#12abef") & 0xFFFFFFFFL, "#RRGGBB");
        eq(0xFF12ABEFL, Config.parseHex(" 12ABEF ") & 0xFFFFFFFFL, "RRGGBB with spaces");
        eq(0xFFFFAA00L, Config.parseHex("#fa0") & 0xFFFFFFFFL, "short #RGB");
        ok(Config.parseHex("12ABE") == null, "incomplete");
        ok(Config.parseHex("12ABEG") == null, "not hex");
        ok(Config.parseHex(null) == null, "null");
        ok("#0061A4".equals(Config.hex(0xFF0061A4)), "hex formatting");

        // --- opacity can go down to 0 and survives the round trip to System UI
        Config c = new Config();
        c.alphaPct = 0;
        eq(0, Config.parse(c.serialize()).alphaPct, "alpha 0 round trip");
        eq(0, Config.parse("alpha=-5").alphaPct, "alpha clamped at 0");
        eq(100, Config.parse("alpha=500").alphaPct, "alpha clamped at 100");
        c.customColor = 0xFFABCDEF;
        eq(0xFFABCDEFL, Config.parse(c.serialize()).customColor & 0xFFFFFFFFL, "custom colour round trip");

        System.out.println("PASS: " + checks + " checks (wallpaper average, legibility, hex input, opacity)");
    }
}
