package io.cleanyourtv.ui;

import android.app.Application;

/**
 * Gives Prefs a context in every process. When the home screen and Explore leave the screen (an app or the TV is
 * on), what's kept in memory for speed goes: the posters (Explore's cache), the parsed TMDB answers, the home's extra
 * pictures. Both caches live on disk too, so coming back stays quick, and the app holds little while you watch.
 */
public class App extends Application {
    @Override public void onCreate() {
        super.onCreate();
        Prefs.init(this);
        Tmdb.init(this);
        // Hebrew: the view-based screens (title pages, Explore…) lay out right to left. The home screen and Settings
        // draw themselves and mirror on their own, so they keep a left-to-right window.
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(android.app.Activity a, android.os.Bundle b) {
                boolean drawn = a instanceof HomeActivity || a instanceof SettingsActivity;
                a.getWindow().getDecorView().setLayoutDirection(Ui.he && !drawn
                        ? android.view.View.LAYOUT_DIRECTION_RTL : android.view.View.LAYOUT_DIRECTION_LTR);
            }
            @Override public void onActivityStarted(android.app.Activity a) {}
            @Override public void onActivityResumed(android.app.Activity a) {}
            @Override public void onActivityPaused(android.app.Activity a) {}
            @Override public void onActivityStopped(android.app.Activity a) {}
            @Override public void onActivitySaveInstanceState(android.app.Activity a, android.os.Bundle b) {}
            @Override public void onActivityDestroyed(android.app.Activity a) {}
        });
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level < TRIM_MEMORY_UI_HIDDEN) return;
        Images.trim();
        Tmdb.trimMemory();
        Recs.trimLoaded();
    }
}
