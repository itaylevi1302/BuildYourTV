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
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level < TRIM_MEMORY_UI_HIDDEN) return;
        Images.trim();
        Tmdb.trimMemory();
        Recs.trimLoaded();
        WatchArt.trimLoaded();
    }
}
