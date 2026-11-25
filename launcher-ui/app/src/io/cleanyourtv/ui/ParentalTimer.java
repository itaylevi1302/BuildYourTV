package io.cleanyourtv.ui;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * BuildYourTV parental control (Settings > Parental control): a time allowance for this viewing session. When it runs
 * out, ParentalLockActivity covers whatever plays: more time needs the parents' 4-digit code, otherwise the TV turns off
 * a minute later. Built like SleepTimer, with no new permission:
 * - the end is wall-clock time in a small file every process reads, so a restart or turning the TV off and on does not
 *   reset it: the key service (RemoteKeyService, :keys) shows the lock again when it connects and when the screen
 *   comes on;
 * - one inexact alarm at the end, in ParentalReceiver (:keys), whose process may start our window over other apps;
 * - the code is kept only as a salted SHA-256, in the app's private files.
 */
final class ParentalTimer {
    private ParentalTimer() {}

    /** Settings' choices, in minutes; 0 is Off. */
    static final int[] CHOICES = {0, 1, 30, 60, 90, 120, 180};   // 1: a quick check that it works
    /** What "Add time" offers once the code is right. */
    static final int[] MORE = {15, 30, 60};
    /** How long the lock screen waits before it turns the TV off. */
    static final long GRACE_MS = 60_000;
    static final String END = "io.cleanyourtv.ui.PARENTAL_END";
    private static final String FILE = "parental-end", PIN_FILE = "parental-pin";

    private static PendingIntent alarm(Context c, int flags) {
        Intent i = new Intent(END).setClass(c, ParentalReceiver.class);
        return PendingIntent.getBroadcast(c, 3, i, flags | PendingIntent.FLAG_IMMUTABLE);
    }

    /** When the allowance ends (System.currentTimeMillis), or 0 when parental control is off. */
    static long end(Context c) {
        String s = TvConfig.read(new File(c.getFilesDir(), FILE));
        try {
            return s == null ? 0 : Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static boolean on(Context c) { return end(c) > 0; }

    /** The allowance is used up: the lock screen must be on top. */
    static boolean locked(Context c) {
        long e = end(c);
        return e > 0 && System.currentTimeMillis() >= e;
    }

    /** Minutes left, rounded up; 0 when off or used up. */
    static int minutesLeft(Context c) {
        long e = end(c);
        long left = e - System.currentTimeMillis();
        return e == 0 || left <= 0 ? 0 : (int) ((left + 59_999) / 60_000);
    }

    /** A new allowance of `minutes` from now (0 turns parental control off). Any process. */
    static void start(Context c, int minutes) {
        if (minutes <= 0) {
            cancel(c);
            return;
        }
        write(c, System.currentTimeMillis() + minutes * 60_000L);
        arm(c);
        AppLog.w("parental control: " + minutes + " min");
    }

    /** Parental control off: no allowance, no alarm. The code stays for next time. */
    static void cancel(Context c) {
        PendingIntent pi = alarm(c, PendingIntent.FLAG_NO_CREATE);
        if (pi != null) {
            c.getSystemService(AlarmManager.class).cancel(pi);
            pi.cancel();
        }
        if (new File(c.getFilesDir(), FILE).delete()) AppLog.w("parental control: off");
    }

    /** The end's alarm, again after a restart (alarms don't survive one). Inexact: no exact-alarm permission. */
    static void arm(Context c) {
        long e = end(c);
        if (e == 0) return;
        AlarmManager am = c.getSystemService(AlarmManager.class);
        am.setWindow(AlarmManager.RTC, Math.max(System.currentTimeMillis(), e), 20_000,
                alarm(c, PendingIntent.FLAG_UPDATE_CURRENT));
    }

    /** The lock screen over whatever plays, when the allowance is used up and the TV is on. */
    static void showLock(Context c) {
        if (!locked(c) || !c.getSystemService(PowerManager.class).isInteractive()) return;
        try {
            c.startActivity(new Intent(c, ParentalLockActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        } catch (RuntimeException e) {
            AppLog.w("parental lock: " + e.getMessage());
        }
    }

    private static void write(Context c, long end) {
        try (FileOutputStream out = new FileOutputStream(new File(c.getFilesDir(), FILE))) {
            out.write(Long.toString(end).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            AppLog.w("parental control not saved: " + e.getMessage());
        }
    }

    // MARK: - The parents' code

    static boolean hasPin(Context c) { return new File(c.getFilesDir(), PIN_FILE).exists(); }

    static void setPin(Context c, String pin) {
        try (FileOutputStream out = new FileOutputStream(new File(c.getFilesDir(), PIN_FILE))) {
            out.write(hash(pin).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            AppLog.w("parental code not saved: " + e.getMessage());
        }
    }

    static boolean checkPin(Context c, String pin) {
        String saved = TvConfig.read(new File(c.getFilesDir(), PIN_FILE));
        return saved != null && MessageDigest.isEqual(saved.trim().getBytes(StandardCharsets.UTF_8),
                hash(pin).getBytes(StandardCharsets.UTF_8));
    }

    static boolean validPin(String pin) { return pin != null && pin.matches("\\d{4}"); }

    private static String hash(String pin) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                    .digest(("buildyourtv-parental:" + pin).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
