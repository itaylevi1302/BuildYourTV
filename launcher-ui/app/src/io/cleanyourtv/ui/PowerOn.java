package io.cleanyourtv.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;

/**
 * Home Settings > At power-on (#61; Free My TV c5f414b and 77b0f27, without their ADB): what shows after the TV starts
 * from off, never when it wakes from standby. "tv" (the default) changes nothing. "live" opens Live TV 10 s after the
 * home screen first comes up in a start, unless a key reached it first or another screen is in front by then. "home"
 * gives the screen back to ours when another app takes it within 5 s of our home screen first showing in a start,
 * before any key (some MediaTek TV centers do, "Please scan channels"); without the TV's ADB nothing tells us about
 * keys pressed inside other apps, so it acts only in that short window.
 *
 * A start is a new Settings.Global.BOOT_COUNT (readable without a permission), kept in Prefs when the home screen is
 * created within 4 minutes of boot: an update or a crash later on is no start. A TV that starts in standby after a
 * power cut (Android's quiescent boot, Free My TV PR 489) creates the home screen with the screen off: the wait starts
 * again when the screen comes on with the home screen in front, and Home pressed before that cancels it. The state is
 * static, in the :home process: HomeActivity can be recreated in between (a pushed config.json). Every outcome is in
 * the log ("at power-on: ...").
 */
final class PowerOn {
    private PowerOn() {}

    static final long LIVE_DELAY_MS = 10_000, HOME_GRAB_MS = 5_000, BOOT_WINDOW_MS = 240_000;

    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static final Runnable live = PowerOn::openLive;
    private static Context app;
    /** "live" or "home" while it is still to come in this start, else null. */
    private static String waiting;
    /** The screen went off (or was off) while waiting: the wait starts again when it comes on with Home in front. */
    private static boolean dark;
    /** Uptime of the first resume with the screen on in this start, or 0. */
    private static long shownAt;
    /** The home screen while it is resumed, else null. */
    private static Activity front;

    /** HomeActivity.onCreate: the first home screen of a new boot arms the setting. */
    static void created(Activity a) {
        app = a.getApplicationContext();
        int boot = Settings.Global.getInt(a.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        if (boot < 0 || boot == Prefs.powerOnBoot()) return;   // the same start, or no count on this TV
        Prefs.setPowerOnBoot(boot);   // even for "tv": a choice made later in this start waits for the next one
        String mode = Prefs.powerOn();
        if (mode.equals("tv")) return;
        long sinceBoot = SystemClock.elapsedRealtime();
        if (sinceBoot > BOOT_WINDOW_MS) {
            AppLog.w("at power-on: the home screen came up " + sinceBoot / 1000 + " s after boot, not a start");
            return;
        }
        waiting = mode;
        dark = !interactive(a);
        if (dark) {
            AppLog.w("at power-on: " + mode + " once the screen comes on");
        } else if (mode.equals("live")) {
            AppLog.w("at power-on: Live TV in 10 s, unless a key comes first");
            handler.postDelayed(live, LIVE_DELAY_MS);
        }
    }

    /** HomeActivity.onResume (after its recreate check). */
    static void resumed(Activity a) {
        front = a;
        if (waiting == null) return;
        if (!interactive(a)) {   // resumed with the screen off: wait for it
            dark = true;
            handler.removeCallbacks(live);
            return;
        }
        if (shownAt == 0) shownAt = SystemClock.uptimeMillis();
        if (dark) {   // the screen came on with the home screen in front
            dark = false;
            if (waiting.equals("live")) {
                handler.removeCallbacks(live);
                handler.postDelayed(live, LIVE_DELAY_MS);
                AppLog.w("at power-on: the screen is on, Live TV in 10 s unless a key comes first");
            }
        }
    }

    /** HomeActivity.onPause: the screen going off keeps the wait; another screen in front ends it. */
    static void paused(Activity a) {
        if (front == a) front = null;
        if (waiting == null) return;
        if (!interactive(a)) {
            dark = true;
            handler.removeCallbacks(live);
            return;
        }
        String mode = waiting;
        cancel(null);
        if (mode.equals("home") && shownAt > 0 && SystemClock.uptimeMillis() - shownAt < HOME_GRAB_MS) {
            AppLog.w("at power-on: another app took the screen before any key, back to the home screen");
            try {
                a.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                        .setPackage(a.getPackageName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException e) {
                AppLog.w("at power-on: " + e.getMessage());
            }
        } else {
            AppLog.w("at power-on: another screen is in front, nothing more");
        }
    }

    /** HomeActivity.onNewIntent: Home pressed while waiting for the screen counts as a key. */
    static void homeIntent() {
        if (waiting != null && dark) cancel("Home came first");
    }

    /** HomeActivity.dispatchKeyEvent: any key reaching the home screen first ends it. */
    static void key(KeyEvent e) {
        if (waiting != null) cancel("a key came first (" + KeyEvent.keyCodeToString(e.getKeyCode()) + ")");
    }

    /** HomeActivity.onDestroy. */
    static void destroyed(Activity a) {
        if (front == a) front = null;
    }

    private static void cancel(String why) {
        waiting = null;
        dark = false;
        handler.removeCallbacks(live);
        if (why != null) AppLog.w("at power-on: " + why + ", nothing more");
    }

    /** Live TV as the button opens it, if the home screen is still in front with the screen on. */
    private static void openLive() {
        if (!"live".equals(waiting)) return;
        if (app != null && !interactive(app)) {
            dark = true;
            AppLog.w("at power-on: the screen is off, Live TV 10 s after it comes on");
            return;
        }
        Activity a = front;
        if (a == null) {
            cancel(null);
            AppLog.w("at power-on: another screen is in front, no Live TV");
            return;
        }
        waiting = null;
        View from = a.getWindow().getDecorView();
        HomeApps.Item item = HomeApps.action(HomeApps.LIVE_TV, "live", Strings.get(R.string.live_tv), "tv");
        AppLog.w("at power-on: Live TV");
        if (!HomeApps.open(a, item, from, 0, 0, from.getWidth(), from.getHeight())) {
            AppLog.w("at power-on: no Live TV on this TV");
        }
    }

    private static boolean interactive(Context c) {
        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        return pm == null || pm.isInteractive();
    }
}
