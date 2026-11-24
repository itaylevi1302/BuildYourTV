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
            if (SleepTimer.end(c) != 0) SleepTimer.cancel(c);
        }
    };

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        bound = this;
        IntentFilter off = new IntentFilter(Intent.ACTION_SCREEN_OFF);
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
        return RemoteKeys.onKey(this, event);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override public void onInterrupt() {}
}
