package io.cleanyourtv.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;

/**
 * The home screen (Android's "Home" role, set from the computer by launcher-ui/build-and-install.sh). It runs in the
 * :home process. If it ever fails, tools/restore.sh gives the home screen back to the TV's original one.
 */
public class HomeActivity extends Activity {
    private HomeView home;
    private int prefsVersion = Prefs.version;   // settings changed while we were away: start over

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.init(this);
        home = new HomeView(this);
        setContentView(home);
        getWindow().setBackgroundDrawable(null);
        if (wantsAllApps(getIntent())) home.showAllApps();
        openFrom(getIntent());
    }

    @Override protected void onResume() {
        super.onResume();
        TvConfig.apply(this);
        if (prefsVersion != Prefs.version) {
            prefsVersion = Prefs.version;
            HomeData.get(this).settingsChanged();
            recreate();   // a new language or city: draw everything again
            return;
        }
        if (home != null) home.resume();
        Images.trimSoon();   // Explore's caches, if it was open: Home doesn't use them
    }

    @Override protected void onPause() {
        if (home != null) home.pause();
        super.onPause();
    }

    /** Home button pressed on the home screen: back to the entry state (focus on the first app, sheet closed). */
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (home != null && wantsAllApps(intent)) {   // the Apps key: opens the sheet, or closes it (#53)
            home.showAllApps();
            return;
        }
        if (home != null) home.reset(true);
        openFrom(intent);
    }

    /** The remote's Apps key, as the system sends it to the home app. */
    private static boolean wantsAllApps(Intent intent) {
        return intent != null && Intent.ACTION_ALL_APPS.equals(intent.getAction());
    }

    /** `--es open explore|watchlist`: Explore from the computer (adb), as the remote's buttons open it. */
    private void openFrom(Intent intent) {
        String what = intent == null ? null : intent.getStringExtra("open");
        if (what == null) return;
        intent.removeExtra("open");
        if (what.equals("explore")) RemoteKeys.run(this, "tmdb:explore");
        else if (what.equals("watchlist")) RemoteKeys.run(this, "tmdb:watchlist");
    }

    @Override protected void onDestroy() {
        if (home != null) home.destroy();
        super.onDestroy();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (RemoteKeys.onKey(this, event)) return true;
        // The Apps key, unless it has a row in Remote Buttons on this TV: there the row decides, and with no action
        // the key is left to the TV (#53).
        if (event.getKeyCode() == KeyEvent.KEYCODE_ALL_APPS && !remoteButton(KeyEvent.KEYCODE_ALL_APPS)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0 && home != null) {
                home.showAllApps();
            }
            return true;
        }
        if (home != null && home.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    /** The key is one of Remote Buttons' rows on this TV (remote.json, or the built-in TCL/Vestel ones). */
    private boolean remoteButton(int keyCode) {
        String id = "k" + keyCode;
        for (RemoteKeys.Button b : RemoteKeys.buttons(this)) if (b.id.equals(id)) return true;
        return false;
    }

}
