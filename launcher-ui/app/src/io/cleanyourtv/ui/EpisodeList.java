package io.cleanyourtv.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * A season's episodes on the title page: OK marks one as seen (kept on the TV), Menu rates it on the TMDB account, and
 * with a watch provider switched on a round button opens it. Rows are built a few at a time as the focus nears the end.
 */
final class EpisodeList extends LinearLayout {
    static final int CHUNK = 8;

    private final Activity a;
    private final float k;
    private final int series;
    private final String request;          // the show's request for the watch providers (docs/specs/provider.md); null without
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<JSONObject> pending = new ArrayList<>();
    private int season, generation;
    private boolean hasProviders, asking;
    private TmdbLists.Progress progress;

    EpisodeList(Activity a, float k, int series, String request) {
        super(a);
        this.a = a;
        this.k = k;
        this.series = series;
        this.request = request;
        setOrientation(VERTICAL);
        setClipChildren(false);
    }

    /** Where tk:watching says the person stopped (null: nowhere): that episode's still shows a bar. */
    void setProgress(TmdbLists.Progress p) {
        progress = p;
    }

    /** Shows the season's episodes, the first rows at once and the rest as the focus gets there. */
    void load(int number) {
        season = number;
        int mine = ++generation;
        pending.clear();
        removeAllViews();
        TextView loading = TvUi.text(a, k, 26, Ui.regular, TvUi.DIM);
        loading.setText(Strings.get(R.string.loading));
        loading.setPadding(Math.round(14 * k), Math.round(14 * k), 0, 0);
        addView(loading);
        new Thread(() -> {
            try {
                JSONObject o = Tmdb.get("/tv/" + series + "/season/" + number);
                boolean providers = request != null && !WatchProviders.allowed(WatchProviders.found(a)).isEmpty();
                main.post(() -> {
                    if (mine != generation || a.isFinishing()) return;
                    hasProviders = providers;
                    removeAllViews();
                    JSONArray episodes = o.optJSONArray("episodes");
                    for (int i = 0; episodes != null && i < episodes.length(); i++) pending.add(episodes.optJSONObject(i));
                    addChunk();
                });
            } catch (Exception e) {
                main.post(() -> {
                    if (mine == generation) loading.setText(Strings.get(R.string.couldnt_open_the_season_prefix) + e.getMessage());
                });
            }
        }).start();
    }

    private void addChunk() {
        for (int i = 0; i < CHUNK && !pending.isEmpty(); i++) addView(episode(pending.remove(0)));
    }

    @Override public void requestChildFocus(View child, View focused) {
        super.requestChildFocus(child, focused);
        if (!pending.isEmpty() && indexOfChild(child) >= getChildCount() - 3) addChunk();
    }

    private View episode(JSONObject e) {
        int number = e.optInt("episode_number");
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Math.round(18 * k), Math.round(18 * k), Math.round(28 * k), Math.round(18 * k));
        TextView num = TvUi.text(a, k, 34, Ui.medium, TvUi.DIM);
        num.setText(String.valueOf(number));
        num.setGravity(Gravity.CENTER);
        row.addView(num, new LinearLayout.LayoutParams(Math.round(60 * k), ViewGroup.LayoutParams.WRAP_CONTENT));
        FrameLayout frame = new FrameLayout(a);
        frame.setBackground(TvUi.round(TvUi.CARD, 12 * k));
        frame.setClipToOutline(true);
        ImageView still = new ImageView(a);
        still.setScaleType(ImageView.ScaleType.CENTER_CROP);
        String path = e.optString("still_path");
        Images.load(still, path.isEmpty() || "null".equals(path) ? null : "https://image.tmdb.org/t/p/w300" + path);
        frame.addView(still, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (progress != null && progress.season == season && progress.episode == number && progress.d > 0) {
            int width = Math.round(264 * k);
            FrameLayout track = new FrameLayout(a);
            track.setBackground(TvUi.round(0x40FFFFFF, 3 * k));
            View fill = new View(a);
            fill.setBackground(TvUi.round(TvUi.ACCENT, 3 * k));
            float fraction = Math.max(0f, Math.min(1f, progress.t / (float) progress.d));
            track.addView(fill, new FrameLayout.LayoutParams(Math.round(width * fraction), ViewGroup.LayoutParams.MATCH_PARENT));
            FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(width, Math.round(5 * k), Gravity.BOTTOM);
            tp.leftMargin = tp.rightMargin = tp.bottomMargin = Math.round(8 * k);
            frame.addView(track, tp);
        }
        row.addView(frame, new LinearLayout.LayoutParams(Math.round(280 * k), Math.round(158 * k)));
        ((LinearLayout.LayoutParams) frame.getLayoutParams()).leftMargin = Math.round(12 * k);
        LinearLayout texts = new LinearLayout(a);
        texts.setOrientation(VERTICAL);
        texts.setPadding(Math.round(30 * k), 0, 0, 0);
        TextView title = TvUi.text(a, k, 29, Ui.medium, TvUi.INK);
        TextView meta = TvUi.text(a, k, 23, Ui.regular, TvUi.DIM);
        TextView over = TvUi.text(a, k, 23, Ui.regular, TvUi.DIM);
        over.setMaxLines(2);
        over.setEllipsize(TextUtils.TruncateAt.END);
        meta.setPadding(0, Math.round(8 * k), 0, Math.round(8 * k));
        List<String> parts = new ArrayList<>();
        String air = e.optString("air_date");
        if (air.length() == 10) parts.add(air.substring(8) + "/" + air.substring(5, 7) + "/" + air.substring(0, 4));
        int runtime = e.optInt("runtime");
        if (runtime > 0) parts.add(runtime + " min");
        double vote = e.optDouble("vote_average");
        if (vote > 0) parts.add(String.format(Ui.PT, "★ %.1f", vote));
        meta.setText(TextUtils.join("  ·  ", parts));
        over.setText(e.optString("overview"));
        texts.addView(title);
        texts.addView(meta);
        texts.addView(over);
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Runnable label = () -> title.setText((Tmdb.episodeSeen(a, series, season, number) ? "✓  " : "") + e.optString("name"));
        label.run();
        TvUi.softFocus(row, k, 18 * k);
        row.setOnClickListener(v -> {
            Tmdb.setEpisodeSeen(a, series, season, number, !Tmdb.episodeSeen(a, series, season, number));
            label.run();
        });
        row.setOnKeyListener((v, code, ev) -> {
            if (code != KeyEvent.KEYCODE_MENU || ev.getAction() != KeyEvent.ACTION_UP) return code == KeyEvent.KEYCODE_MENU;
            rate(number, e.optString("name"), label);
            return true;
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Math.round(14 * k);
        if (!hasProviders) {
            row.setLayoutParams(lp);
            return row;
        }
        // The Watch button sits beside the row: OK on the row still marks it seen, Menu still rates it.
        LinearLayout outer = new LinearLayout(a);
        outer.setOrientation(HORIZONTAL);
        outer.setGravity(Gravity.CENTER_VERTICAL);
        outer.setLayoutParams(lp);
        outer.addView(row, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TvUi.Action watch = TvUi.action(a, k, R.drawable.ic_play, null, v -> watchEpisode(number));
        watch.setContentDescription(Strings.get(R.string.watch_episode_button));
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wp.leftMargin = Math.round(20 * k);
        outer.addView(watch, wp);
        return outer;
    }

    /** Asks the allowed apps about this episode: none has it, one opens at once, several are listed to pick from. */
    private void watchEpisode(int number) {
        if (asking || request == null) return;
        asking = true;
        int s = season;
        new Thread(() -> {
            List<WatchProviders.Item> items = new ArrayList<>();
            try {
                JSONObject req = new JSONObject(request).put("season", s).put("episode", number);
                List<WatchProviders.Provider> on = WatchProviders.allowed(WatchProviders.found(a));
                for (List<WatchProviders.Item> got : WatchProviders.ask(a, on, req.toString()).values()) items.addAll(got);
            } catch (Exception ignored) {
            }
            main.post(() -> {
                asking = false;
                if (a.isFinishing() || a.isDestroyed()) return;
                if (items.isEmpty()) {
                    Toast.makeText(a, Strings.get(R.string.no_app_has_this_episode), Toast.LENGTH_LONG).show();
                } else if (items.size() == 1) {
                    WatchProviders.open(a, items.get(0));
                } else {
                    WatchProviders.choose(a, Strings.get(R.string.watch_episode, s, number), items);
                }
            });
        }).start();
    }

    private void rate(int number, String name, Runnable relabel) {
        if (!Tmdb.signedIn(a)) {
            Toast.makeText(a, Strings.get(R.string.connect_your_tmdb_account_in_settings_to_rate), Toast.LENGTH_LONG).show();
            return;
        }
        int s = season;
        String[] items = new String[10];
        for (int i = 0; i < 10; i++) items[i] = "★ " + (10 - i);
        new AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(number + ". " + name)
                .setItems(items, (d, which) -> new Thread(() -> {
                    try {
                        Tmdb.rateEpisode(a, series, s, number, 10 - which);
                        Tmdb.setEpisodeSeen(a, series, s, number, true);
                        main.post(relabel);
                    } catch (Exception ex) {
                        main.post(() -> Toast.makeText(a, ex.getMessage(), Toast.LENGTH_LONG).show());
                    }
                }).start())
                .show();
    }
}
