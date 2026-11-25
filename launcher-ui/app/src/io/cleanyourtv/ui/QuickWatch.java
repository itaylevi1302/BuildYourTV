package io.cleanyourtv.ui;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.util.List;
import java.util.Map;

/**
 * "Watch" in the hold-OK menu: opens the title in the first streaming app the TV has, else in the first allowed watch
 * provider that has it, else on its page. Never asks about a new provider (the title page does that), so a menu pick
 * never stops on a question.
 */
final class QuickWatch {
    private QuickWatch() {}

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    static void open(Activity a, Tmdb.Title t) {
        new Thread(() -> {
            try {
                JSONObject o = Tmdb.get(DetailActivity.path(t.kind, t.id));
                String[] app = DetailActivity.firstApp(a, regionOf(o));
                if (app != null) {
                    String pkg = app[0];
                    String url = DeepLinks.find(a, t.kind, t.id, t.name).get(pkg);
                    MAIN.post(() -> {
                        if (a.isFinishing()) return;
                        Tmdb.setSeen(a, t, true);
                        if (!DeepLinks.open(a, pkg, url)) RemoteKeys.run(a, pkg);
                        Recent.note(a, pkg);   // the shelf's "Last opened first" counts it too (#63)
                    });
                    return;
                }
                List<WatchProviders.Provider> on = WatchProviders.allowed(WatchProviders.found(a));
                if (!on.isEmpty()) {
                    boolean movie = "movie".equals(t.kind);
                    int year = 0;
                    try { year = Integer.parseInt(t.year); } catch (NumberFormatException ignored) {}
                    JSONObject req = WatchProviders.request(t.kind, t.id, t.name, o.optString(movie ? "original_title" : "original_name"),
                            year, o.optJSONObject("external_ids"), 0, 0);
                    for (List<WatchProviders.Item> items : WatchProviders.ask(a, on, req.toString()).values()) {
                        if (items.isEmpty()) continue;
                        WatchProviders.Item item = items.get(0);
                        MAIN.post(() -> {
                            if (a.isFinishing()) return;
                            Tmdb.setSeen(a, t, true);
                            WatchProviders.open(a, item);
                        });
                        return;
                    }
                }
            } catch (Exception ignored) {
            }
            MAIN.post(() -> { if (!a.isFinishing()) DetailActivity.open(a, t.kind, t.id); });
        }, "quick-watch").start();
    }

    private static JSONObject regionOf(JSONObject o) {
        JSONObject wp = o.optJSONObject("watch/providers");
        JSONObject results = wp == null ? null : wp.optJSONObject("results");
        return results == null ? null : results.optJSONObject(Tmdb.region());
    }
}
