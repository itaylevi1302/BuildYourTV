package io.cleanyourtv.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * The sleep timer's alarms (SleepTimer), in the key service's process (:keys), where its "lock screen" action is. Not
 * exported: only this app's own AlarmManager PendingIntents (explicit component) reach it.
 */
public class SleepReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        String action = String.valueOf(i.getAction());
        long end = i.getLongExtra(SleepTimer.EXTRA_END, 0);
        if (!SleepTimer.due(c, end)) return;
        if (!RemoteKeyService.running()) {   // the key service was turned off since: nothing can turn the TV off
            AppLog.w("sleep timer: the key service is off, timer ended");
            SleepTimer.cancel(c);
            return;
        }
        if (action.equals(SleepTimer.WARN)) SleepTimer.warn(c, end);
        else if (action.equals(SleepTimer.SLEEP)) SleepTimer.sleepNow(c);
    }
}
