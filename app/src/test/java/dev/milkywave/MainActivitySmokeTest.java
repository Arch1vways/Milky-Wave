package dev.milkywave;

import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.res.Configuration;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import org.robolectric.shadows.ShadowLooper;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

/** Launches the settings screen through its whole lifecycle; any start-up crash fails the build's tests. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {31, 34, 35}, qualifiers = "ru")
public class MainActivitySmokeTest {
    @Test public void launchesAndStops() {
        ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup();
        Activity a = c.get();
        assertTrue(a.getWindow().getDecorView().isAttachedToWindow() || !a.isFinishing());
        c.pause().stop().destroy();
    }

    @Test @Config(qualifiers = "ru-night") public void launchesInDarkTheme() {
        int night = RuntimeEnvironment.getApplication().getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        assertTrue(night == Configuration.UI_MODE_NIGHT_YES);
        Robolectric.buildActivity(MainActivity.class).setup().pause().stop().destroy();
    }

    /** Phones in any language other than Russian get the English texts. */
    @Test @Config(qualifiers = "en") public void englishTexts() {
        ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup();
        View root = c.get().getWindow().getDecorView();
        click(root, "Custom");
        click(root, "Wave");
        click(root, "Album art");
        c.pause().stop().destroy();
    }

    /** Every control the user touches: colour modes, hex input, swatches, sliders down to 0, reset of style. */
    @Test public void controlsDoNotCrash() {
        ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup();
        View root = c.get().getWindow().getDecorView();
        click(root, "Свой");
        EditText hex = first(root, EditText.class);
        hex.setText("ff8800");
        hex.setText("#12abef");
        hex.setText("zz");                              // filtered out
        hex.onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        for (SeekBar bar : all(root, SeekBar.class)) {
            bar.setProgress(0);
            bar.setProgress(bar.getMax());
        }
        // Colour dot opens the picker; choose a colour on the wheel and confirm.
        View dot = null;
        for (View v : all(root, View.class)) {
            if ("Открыть выбор цвета".contentEquals(String.valueOf(v.getContentDescription()))) dot = v;
        }
        assertTrue("colour dot", dot != null && dot.performClick());
        android.app.Dialog picker = org.robolectric.shadows.ShadowDialog.getLatestDialog();
        assertTrue("picker shown", picker != null && picker.isShowing());
        View pickerRoot = picker.getWindow().getDecorView();
        ColorWheelView wheel = first(pickerRoot, ColorWheelView.class);
        wheel.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY));
        wheel.layout(0, 0, 800, 800);
        wheel.dispatchTouchEvent(android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_DOWN, 700, 400, 0));
        wheel.dispatchTouchEvent(android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_UP, 700, 400, 0));
        first(pickerRoot, SeekBar.class).setProgress(50);
        String before = hex.getText().toString();
        click(pickerRoot, "Готово");
        assertTrue("picker closed", !picker.isShowing());
        assertTrue("hex updated from picker", !before.contentEquals(hex.getText()));
        click(root, "Система");
        click(root, "Волна");
        click(root, c.get().getString(R.string.color_album));
        ShadowLooper.idleMainLooper(2, java.util.concurrent.TimeUnit.SECONDS);
        c.pause().resume().pause().stop().destroy();
    }

    /** Lock-screen view as created by the hook in System UI: build, lay out and draw in every colour mode. */
    @Test public void lockScreenViewDraws() {
        android.content.Context ctx = RuntimeEnvironment.getApplication();
        WaveView live = new WaveView(ctx, false);
        live.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY));
        live.layout(0, 0, 1080, 2400);
        android.graphics.Canvas canvas = new android.graphics.Canvas(
                android.graphics.Bitmap.createBitmap(1080, 2400, android.graphics.Bitmap.Config.ARGB_8888));
        for (int mode = 0; mode < 3; mode++) {
            dev.milkywave.Config cfg = new dev.milkywave.Config();
            cfg.colorSource = mode;
            cfg.alphaPct = mode == 1 ? 0 : 80;
            ConfigStore.update(ctx, cfg);
            live.draw(canvas);
        }
        ShadowLooper.idleMainLooper(2, java.util.concurrent.TimeUnit.SECONDS);
        live.draw(canvas);
    }

    private static void click(View root, String label) {
        for (TextView t : all(root, TextView.class)) {
            if (label.contentEquals(t.getText())) {
                assertTrue(label, t.performClick());
                return;
            }
        }
        throw new AssertionError("no view " + label);
    }

    private static <T extends View> T first(View root, Class<T> type) {
        List<T> list = all(root, type);
        if (list.isEmpty()) throw new AssertionError("no " + type.getSimpleName());
        return list.get(0);
    }

    private static <T extends View> List<T> all(View v, Class<T> type) {
        List<T> out = new ArrayList<>();
        collect(v, type, out);
        return out;
    }

    private static <T extends View> void collect(View v, Class<T> type, List<T> out) {
        if (type.isInstance(v)) out.add(type.cast(v));
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), type, out);
        }
    }
}
