package io.cleanyourtv.ui;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Settings > Sleep Timer (#68): the TV goes to standby after 15 to 120 minutes. Free My TV 80902d0 sends the remote's
 * Sleep key through the TV's own ADB, shows the minutes left over other apps and keeps an exact alarm (an overlay, a
 * foreground service, exact alarms). Here, with no new permission:
 * - the key service's "lock screen" action puts the TV to sleep (RemoteKeyService.sleep), so the timer needs
 *   KEY_SERVICE=1, and Settings says so without it;
 * - two inexact alarms: a minute before the end, SleepWarningActivity, our own window over whatever plays; at the end,
 *   the sleep. Both land in SleepReceiver, in the key service's process (:keys), so the home screen's process may be gone;
 * - the end (SystemClock.elapsedRealtime) is kept in a small file the processes share (Prefs is cached in each), and the
 *   timer runs only while its sleep alarm is pending: a restart drops the alarm, so the timer ends there. The TV going
 *   off some other way ends it too (RemoteKeyService hears the screen go off).
 */
final class SleepTimer {
    private SleepTimer() {}

    /** Settings' choices, in minutes; 0 is Off. */
    static final int[] CHOICES = {0, 15, 30, 60, 90, 120};
    /** The warning's "15 more minutes" (R.string.sleep_more says 15). */
    static final int MORE = 15;
    static final long WARN_MS = 60_000;
    /** An alarm this late found the TV off (a non-wakeup alarm waits for the TV to wake): it does nothing then. */
    static final long STALE_MS = 5 * 60_000;
    static final String WARN = "io.cleanyourtv.ui.SLEEP_WARN", SLEEP = "io.cleanyourtv.ui.SLEEP", EXTRA_END = "end";
    private static final String FILE = "sleep-timer";

    /** The same flags for every lookup: a PendingIntent's flags are part of what finds it again. */
    private static PendingIntent alarm(Context c, String action, long end, int flags) {
        Intent i = new Intent(action).setClass(c, SleepReceiver.class).putExtra(EXTRA_END, end);
        return PendingIntent.getBroadcast(c, SLEEP.equals(action) ? 1 : 2, i,
                flags | PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Minutes left, rounded up; 0 when off (canceled, ended, or the TV restarted since). */
    static int minutesLeft(Context c) {
        long end = end(c);
        if (end == 0 || alarm(c, SLEEP, 0, PendingIntent.FLAG_NO_CREATE) == null) return 0;
        long left = end - SystemClock.elapsedRealtime();
        return left <= 0 ? 0 : (int) ((left + 59_999) / 60_000);
    }

    /** The key service is on and bound: the only way this app can put the TV to sleep. */
    static boolean available(Context c) {
        AccessibilityManager am = c.getSystemService(AccessibilityManager.class);
        if (am == null) return false;
        String me = new ComponentName(c, RemoteKeyService.class).flattenToShortString();
        for (AccessibilityServiceInfo s : am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
            if (me.equals(s.getId())) return true;
        }
        return false;
    }

    /** Starts the timer for `minutes` from now; 0 ends it. Any process. */
    static void set(Context c, int minutes) {
        if (minutes <= 0) cancel(c);
        else start(c, minutes * 60_000L);
    }

    static void start(Context c, long ms) {
        cancel(c);
        long now = SystemClock.elapsedRealtime(), end = now + ms;
        write(c, end);
        AlarmManager am = c.getSystemService(AlarmManager.class);
        // Inexact windows: exact alarms need a permission from Android 12. While the TV is on these normally come on
        // time; Android 12 and later may deliver one a few minutes late. Non-wakeup: a TV that is off has nothing to
        // turn off, and SleepReceiver drops an alarm that waited for it.
        am.setWindow(AlarmManager.ELAPSED_REALTIME, Math.max(now, end - WARN_MS), 30_000,
                alarm(c, WARN, end, PendingIntent.FLAG_UPDATE_CURRENT));
        am.setWindow(AlarmManager.ELAPSED_REALTIME, end, 60_000, alarm(c, SLEEP, end, PendingIntent.FLAG_UPDATE_CURRENT));
        AppLog.w("sleep timer: " + ms / 1000 + " s");
    }

    /** Ends the timer: its alarms and its file. Any process. */
    static void cancel(Context c) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        for (String action : new String[] {WARN, SLEEP}) {
            PendingIntent pi = alarm(c, action, 0, PendingIntent.FLAG_NO_CREATE);
            if (pi != null) {
                am.cancel(pi);
                pi.cancel();
            }
        }
        if (new File(c.getFilesDir(), FILE).delete()) AppLog.w("sleep timer: off");
    }

    /** When the timer ends (SystemClock.elapsedRealtime), or 0 with none. Alone it can be stale: see minutesLeft. */
    static long end(Context c) {
        String s = TvConfig.read(new File(c.getFilesDir(), FILE));
        try {
            return s == null ? 0 : Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void write(Context c, long end) {
        try (FileOutputStream out = new FileOutputStream(new File(c.getFilesDir(), FILE))) {
            out.write(Long.toString(end).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            AppLog.w("sleep timer not saved: " + e.getMessage());
        }
    }

    /**
     * An alarm of the timer that is on now (not one canceled or replaced since), with the TV on. One that comes late
     * or finds the TV off ends the timer: the TV was turned off meanwhile, and turning it on again must not sleep it.
     */
    static boolean due(Context c, long end) {
        if (end == 0 || end != end(c)) return false;
        if (SystemClock.elapsedRealtime() - end > STALE_MS || !c.getSystemService(PowerManager.class).isInteractive()) {
            AppLog.w("sleep timer: the TV was off when it came due, timer ended");
            cancel(c);
            return false;
        }
        return true;
    }

    /** A minute before the end (SleepReceiver, :keys): the warning over whatever plays. */
    static void warn(Context c, long end) {
        try {
            c.startActivity(new Intent(c, SleepWarningActivity.class).putExtra(EXTRA_END, end)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {   // the sleep alarm still comes
            AppLog.w("sleep timer warning: " + e.getMessage());
        }
    }

    /** The end, or "Turn off now" (:keys, where the key service is): the timer ends and the TV goes to sleep. */
    static void sleepNow(Context c) {
        cancel(c);
        AppLog.w(RemoteKeyService.sleep() ? "sleep timer: TV off" : "sleep timer: the key service is off, the TV stays on");
    }
}
