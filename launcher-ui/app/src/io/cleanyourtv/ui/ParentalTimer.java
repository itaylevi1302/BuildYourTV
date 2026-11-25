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
    private static final String FILE = "parental-end", PIN_FILE = "parental-pin", FAILS_FILE = "parental-fails";
    /** Wrong codes in a row before a lockout, and how long it lasts. */
    static final int MAX_FAILS = 5;
    static final long LOCKOUT_MS = 5 * 60_000;

    private static PendingIntent alarm(Context c, int flags) {
        Intent i = new Intent(END).setClass(c, ParentalReceiver.class);
        return PendingIntent.getBroadcast(c, 3, i, flags | PendingIntent.FLAG_IMMUTABLE);
    }

    /**
     * The file holds three numbers: the end in wall-clock time (it survives a restart), the end on the monotonic clock
     * (SystemClock.elapsedRealtime, which a changed date or time doesn't move) and the boot it belongs to. Index 0 is
     * the wall-clock end, 0 when parental control is off.
     */
    private static long[] read(Context c, String name) {
        String s = TvConfig.read(new File(c.getFilesDir(), name));
        long[] out = new long[5];
        if (s == null) return out;
        String[] parts = s.trim().split(" +");
        for (int i = 0; i < parts.length && i < 5; i++) {
            try {
                out[i] = Long.parseLong(parts[i]);
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    /** This boot's number (Settings.Global.BOOT_COUNT), so a monotonic time is only compared within one boot. */
    private static long boot(Context c) {
        return android.provider.Settings.Global.getInt(c.getContentResolver(), android.provider.Settings.Global.BOOT_COUNT, 0);
    }

    /** When the allowance ends (System.currentTimeMillis), or 0 when parental control is off. */
    static long end(Context c) { return read(c, FILE)[0]; }

    static boolean on(Context c) { return end(c) > 0; }

    /**
     * The allowance is used up: the lock screen must be on top. Either clock ending it is enough, so setting the TV's
     * date or time back doesn't add time within a boot.
     */
    static boolean locked(Context c) {
        long[] f = read(c, FILE);
        if (f[0] <= 0) return false;
        if (System.currentTimeMillis() >= f[0]) return true;
        return f[2] == boot(c) && f[1] > 0 && android.os.SystemClock.elapsedRealtime() >= f[1];
    }

    /**
     * Parental control is on but the key service is off (switched off in the TV's accessibility settings): nothing
     * could cover other apps or turn the TV off, so the home screen shows the lock until the code is typed.
     */
    static boolean tampered(Context c) { return on(c) && !SleepTimer.available(c); }

    /** The lock is due: the time is up, or its service was switched off. */
    static boolean mustLock(Context c) { return locked(c) || tampered(c); }

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
        long ms = minutes * 60_000L;
        write(c, FILE, (System.currentTimeMillis() + ms) + " " + (android.os.SystemClock.elapsedRealtime() + ms) + " " + boot(c));
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
        if (!mustLock(c) || !c.getSystemService(PowerManager.class).isInteractive()) return;
        try {
            c.startActivity(new Intent(c, ParentalLockActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        } catch (RuntimeException e) {
            AppLog.w("parental lock: " + e.getMessage());
        }
    }

    private static void write(Context c, String name, String text) {
        try (FileOutputStream out = new FileOutputStream(new File(c.getFilesDir(), name))) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
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

    static final int PIN_OK = 0, PIN_WRONG = 1, PIN_LOCKED_OUT = 2;

    /** Milliseconds until code entry opens again after too many wrong codes, or 0. */
    /*
     * The fails file: wrong codes in a row, the lockout's end on the monotonic clock, its boot, the lockout's end in
     * wall-clock time, and how many lockouts in a row. All of it survives a restart: the count goes on, and a lockout
     * from an earlier boot holds until its wall-clock end.
     */
    static long lockoutLeft(Context c) {
        long[] f = read(c, FAILS_FILE);
        long left = f[2] == boot(c) ? f[1] - android.os.SystemClock.elapsedRealtime() : f[3] - System.currentTimeMillis();
        return Math.max(0, left);
    }

    /**
     * Every code check (the lock screen and Settings): MAX_FAILS wrong codes in a row close code entry for LOCKOUT_MS,
     * doubled for each lockout in a row up to an hour, in every process and across restarts, so the four digits can't
     * be tried one after another.
     */
    static int tryPin(Context c, String pin) {
        if (lockoutLeft(c) > 0) return PIN_LOCKED_OUT;
        long[] f = read(c, FAILS_FILE);
        if (checkPin(c, pin)) {
            new File(c.getFilesDir(), FAILS_FILE).delete();
            return PIN_OK;
        }
        long fails = f[0] + 1, untilMono = 0, untilWall = 0, lockouts = f[4];
        if (fails >= MAX_FAILS) {
            fails = 0;
            lockouts++;
            long ms = Math.min(60 * 60_000L, LOCKOUT_MS << Math.min(4, lockouts - 1));
            untilMono = android.os.SystemClock.elapsedRealtime() + ms;
            untilWall = System.currentTimeMillis() + ms;
            AppLog.w("parental control: too many wrong codes, code entry closed for " + ms / 60_000 + " min");
        }
        write(c, FAILS_FILE, fails + " " + untilMono + " " + boot(c) + " " + untilWall + " " + lockouts);
        return untilMono > 0 ? PIN_LOCKED_OUT : PIN_WRONG;
    }

    private static boolean checkPin(Context c, String pin) {
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
