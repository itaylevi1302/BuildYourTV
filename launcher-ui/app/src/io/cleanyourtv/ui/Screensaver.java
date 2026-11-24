package io.cleanyourtv.ui;

import android.content.Context;
import android.service.dreams.DreamService;
import android.view.View;

/**
 * The screensaver (set from the computer with `settings put secure screensaver_components`). The system
 * still decides when it starts and when the TV turns off (e.g. after 10 min idle, then 2 h later), as before: the app
 * doesn't change those timings. Any key exits. Its style is picked in Settings (Prefs.screensaverStyle).
 */
public class Screensaver extends DreamService {
    /** What the screensaver shows (DreamView: the picks' photos, or the clock over the aurora). */
    interface Show {
        void start();
        void stop();
    }

    /** The view for the style picked in Settings; the preview in Settings uses it too. */
    static View make(Context c) {
        return new DreamView(c, Prefs.screensaverStyle().equals("aurora"));
    }

    private View view;

    @Override public void onAttachedToWindow() {
        super.onAttachedToWindow();
        Prefs.reload();     // the style may have changed in another process (Android 11 to 12L)
        Ui.init(this);
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(true);
        view = make(this);
        setContentView(view);
    }

    @Override public void onDreamingStarted() {
        super.onDreamingStarted();
        if (view != null) ((Show) view).start();
    }

    @Override public void onDreamingStopped() {
        if (view != null) ((Show) view).stop();
        super.onDreamingStopped();
    }

    @Override public void onDetachedFromWindow() {
        if (view != null) ((Show) view).stop();
        super.onDetachedFromWindow();
    }
}
