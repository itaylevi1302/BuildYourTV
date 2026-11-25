package io.cleanyourtv.ui;

import android.content.Context;
import android.service.dreams.DreamService;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

/**
 * The screensaver (set from the computer with `settings put secure screensaver_components`). The system
 * still decides when it starts and when the TV turns off (e.g. after 10 min idle, then 2 h later), as before: the app
 * doesn't change those timings. Any key exits. Its style is picked in Settings (Prefs.screensaverStyle).
 *
 * With Home Settings, "OK on the screensaver opens the title" on (Prefs.screensaverOk; off by default), OK opens the
 * title on screen in its app (DreamView.watch; the caption says so) and any other key, Back included, still exits. For
 * that the dream is interactive: Android then hands every input event to the dream's window instead of waking up on the
 * first one, so each one here ends the dream itself (finish()), or it would stay up. Off, the dream is not interactive
 * and none of this runs: Android wakes on the first event, as it always did. ScreensaverAlone (Android 11 to 12L, main
 * process) inherits all of this.
 */
public class Screensaver extends DreamService {
    /** What the screensaver shows (DreamView: the picks' photos, or the clock over the aurora). */
    interface Show {
        void start();
        void stop();
        /** OK: opens the title on screen in its app; false when nothing on screen has one (the clock, the aurora). */
        boolean watch();
    }

    /** The view for the style picked in Settings; the preview in Settings uses it too. */
    static View make(Context c) {
        return new DreamView(c, Prefs.screensaverStyle().equals("aurora"));
    }

    /** The remote's OK, as the screensaver and its preview read it. */
    static boolean isOk(int code) {
        return code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
    }

    private View view;
    private boolean watching;   // Prefs.screensaverOk when the dream started: interactive, OK opens the title
    private boolean okDown;     // OK went down during this dream: its release is the one that opens the title
    private boolean done;

    @Override public void onAttachedToWindow() {
        super.onAttachedToWindow();
        Prefs.reload();     // the style and the switch may have changed in another process (Android 11 to 12L)
        Ui.init(this);
        watching = Prefs.screensaverOk();
        setInteractive(watching);   // on: the keys come to dispatchKeyEvent; off: the first one wakes the TV
        setFullscreen(true);
        setScreenBright(true);
        view = make(this);
        // TalkBack may turn OK into a click on the view instead of sending the key: the same as OK.
        if (watching) view.setOnClickListener(v -> ok());
        setContentView(view);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (!watching) return super.dispatchKeyEvent(event);
        if (isOk(event.getKeyCode())) {
            // OK acts when it's released, so the app it opens doesn't get the release.
            if (event.getAction() == KeyEvent.ACTION_DOWN) okDown = true;
            else if (event.getAction() == KeyEvent.ACTION_UP) {
                if (okDown && !event.isCanceled()) ok();
                else close();
            }
            return true;
        }
        close();   // any other key, Back included, closes it at once, as before
        return true;
    }

    // Interactive, the system no longer wakes up on these either: they close the screensaver, as before.
    @Override public boolean dispatchKeyShortcutEvent(KeyEvent event) {
        if (!watching) return super.dispatchKeyShortcutEvent(event);
        close();
        return true;
    }
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (!watching) return super.dispatchTouchEvent(event);
        close();
        return true;
    }
    @Override public boolean dispatchTrackballEvent(MotionEvent event) {
        if (!watching) return super.dispatchTrackballEvent(event);
        close();
        return true;
    }
    @Override public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (!watching) return super.dispatchGenericMotionEvent(event);
        close();
        return true;
    }

    /** OK: the title opens first (while the dream is still on screen, so Android lets it start an app), then it ends. */
    private void ok() {
        if (done) return;
        if (view != null) ((Show) view).watch();   // nothing with an app on screen: OK only closes it
        close();
    }

    private void close() {
        if (done) return;
        done = true;
        finish();
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
