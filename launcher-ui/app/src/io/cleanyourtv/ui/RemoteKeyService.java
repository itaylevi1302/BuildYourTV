package io.cleanyourtv.ui;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Sees the remote's keys before any app does, so the app buttons work from inside Netflix, Disney and the rest. The
 * system waits for it on every key (up to half a second), so it runs in a process of its own and does next to nothing.
 */
public class RemoteKeyService extends AccessibilityService {
    /** This service while the system has it bound (in :keys): the sleep timer turns the TV off through it. */
    private static volatile RemoteKeyService bound;

    /** The TV turned off some other way (the remote, HDMI-CEC): a sleep timer that was running ends there. */
    private final BroadcastReceiver screenOff = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_SCREEN_ON.equals(i.getAction())) {
                // Parental control: turning the TV off and on doesn't end a used-up allowance.
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> ParentalTimer.showLock(c), 1500);
                return;
            }
            if (SleepTimer.end(c) != 0) SleepTimer.cancel(c);
        }
    };

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        bound = this;
        IntentFilter off = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        off.addAction(Intent.ACTION_SCREEN_ON);
        // Parental control after a restart: the alarm again, and the lock if the time ran out meanwhile.
        ParentalTimer.arm(this);
        ParentalTimer.showLock(this);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenOff, off, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(screenOff, off);
    }

    @Override public void onDestroy() {
        bound = null;
        try {
            unregisterReceiver(screenOff);
        } catch (IllegalArgumentException ignored) {   // never connected
        }
        super.onDestroy();
    }

    /**
     * Puts the TV to sleep (Settings > Sleep Timer): the "lock screen" global action, Android 9+, which AOSP carries out
     * as PowerManager.goToSleep, the path of the remote's power and Sleep keys. False when the service isn't bound.
     */
    static boolean sleep() {
        RemoteKeyService s = bound;
        return s != null && s.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
    }

    static boolean running() {
        return bound != null;
    }

    @Override protected boolean onKeyEvent(KeyEvent event) {
        if (ParentalTimer.locked(this)) return lockedKey(event);
        return RemoteKeys.onKey(this, event);
    }

    /**
     * Parental control's time is up: the lock screen's keys (arrows, OK, Back, digits) and the TV's own (volume, power)
     * pass; the app buttons, Home, Input and the rest bring the lock screen back instead of opening anything.
     */
    private boolean lockedKey(KeyEvent e) {
        int k = e.getKeyCode();
        boolean pass = (k >= KeyEvent.KEYCODE_DPAD_UP && k <= KeyEvent.KEYCODE_DPAD_CENTER) || k == KeyEvent.KEYCODE_ENTER
                || k == KeyEvent.KEYCODE_NUMPAD_ENTER || k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_DEL
                || (k >= KeyEvent.KEYCODE_0 && k <= KeyEvent.KEYCODE_9) || k == KeyEvent.KEYCODE_VOLUME_UP
                || k == KeyEvent.KEYCODE_VOLUME_DOWN || k == KeyEvent.KEYCODE_VOLUME_MUTE || k == KeyEvent.KEYCODE_POWER
                || k == KeyEvent.KEYCODE_SLEEP || k == KeyEvent.KEYCODE_WAKEUP;
        if (pass) return false;
        if (e.getAction() == KeyEvent.ACTION_UP) ParentalTimer.showLock(this);
        return true;
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override public void onInterrupt() {}
}
