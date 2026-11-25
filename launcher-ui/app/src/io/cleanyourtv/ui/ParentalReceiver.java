package io.cleanyourtv.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Parental control's alarm (ParentalTimer), in the key service's process (:keys), which may start the lock screen over
 * other apps. Not exported: only this app's own AlarmManager PendingIntent reaches it.
 */
public class ParentalReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        if (!ParentalTimer.END.equals(i.getAction())) return;
        if (ParentalTimer.mustLock(c)) ParentalTimer.showLock(c);
        else ParentalTimer.arm(c);   // came early (an allowance extended meanwhile): wait for the new end
    }
}
