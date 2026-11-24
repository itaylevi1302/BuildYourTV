package io.cleanyourtv.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
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

/** A season's episodes: OK marks one as seen (kept on the TV), Menu rates it on the TMDB account. */
public class SeasonActivity extends Activity {
    private static final String SERIES = "series", SEASON = "season", NAME = "name", REQUEST = "request";

    /** request: the show's Where to watch request (docs/specs/provider.md), or null for no watch providers. */
    static void open(Context c, int series, int season, String seriesName, String request) {
        c.startActivity(new Intent(c, SeasonActivity.class).putExtra(SERIES, series).putExtra(SEASON, season).putExtra(NAME, seriesName)
                .putExtra(REQUEST, request));
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private float k;
    private int series, season;
    private LinearLayout list;
    private String request;               // the show's request for the watch providers; null without
    private boolean hasProviders, asking;   // an app is switched on / a question is open

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        k = TvUi.k(this);
        series = getIntent().getIntExtra(SERIES, 0);
        season = getIntent().getIntExtra(SEASON, 1);
        request = getIntent().getStringExtra(REQUEST);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(TvUi.BG);
        TvUi.Scroll scroll = new TvUi.Scroll(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(Math.round(140 * k), Math.round(70 * k), Math.round(140 * k), Math.round(80 * k));
        TextView h = TvUi.text(this, k, 56, Ui.displaySemibold, TvUi.INK);
        h.setText(getIntent().getStringExtra(NAME));
        list.addView(h);
        TextView sub = TvUi.text(this, k, 28, Ui.medium, TvUi.DIM);
        sub.setText(season == 0 ? Strings.get(R.string.specials) : Strings.get(R.string.season_prefix) + season);
        sub.setPadding(0, Math.round(10 * k), 0, Math.round(26 * k));
        list.addView(sub);
        scroll.addView(list);
        root.addView(scroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        new Thread(() -> {
            try {
                JSONObject o = Tmdb.get("/tv/" + series + "/season/" + season);
                boolean providers = request != null && !WatchProviders.allowed(WatchProviders.found(this)).isEmpty();
                main.post(() -> {
                    if (isFinishing()) return;
                    hasProviders = providers;
                    show(o);
                });
            } catch (Exception e) {
                main.post(() -> sub.setText(Strings.get(R.string.couldnt_open_the_season_prefix) + e.getMessage()));
            }
        }).start();
    }

    private void show(JSONObject o) {
        String overview = o.optString("overview");
        if (!overview.isEmpty()) {
            TextView ov = TvUi.text(this, k, 25, Ui.regular, TvUi.DIM);
            ov.setText(overview);
            ov.setMaxLines(3);
            ov.setEllipsize(TextUtils.TruncateAt.END);
            ov.setPadding(0, 0, 0, Math.round(26 * k));
            list.addView(ov);
        }
        JSONArray episodes = o.optJSONArray("episodes");
        View first = null;
        for (int i = 0; episodes != null && i < episodes.length(); i++) {
            View row = episode(episodes.optJSONObject(i));
            list.addView(row);
            if (first == null) first = row;
        }
        if (first != null) first.requestFocus();
    }

    private View episode(JSONObject e) {
        int number = e.optInt("episode_number");
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Math.round(18 * k), Math.round(18 * k), Math.round(28 * k), Math.round(18 * k));
        ImageView still = new ImageView(this);
        still.setScaleType(ImageView.ScaleType.CENTER_CROP);
        still.setBackground(TvUi.round(TvUi.CARD, 12 * k));
        still.setClipToOutline(true);
        String path = e.optString("still_path");
        Images.load(still, path.isEmpty() || "null".equals(path) ? null : "https://image.tmdb.org/t/p/w300" + path);
        row.addView(still, new LinearLayout.LayoutParams(Math.round(280 * k), Math.round(158 * k)));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(Math.round(30 * k), 0, 0, 0);
        TextView title = TvUi.text(this, k, 29, Ui.medium, TvUi.INK);
        TextView meta = TvUi.text(this, k, 23, Ui.regular, TvUi.DIM);
        TextView over = TvUi.text(this, k, 23, Ui.regular, TvUi.DIM);
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
        Runnable label = () -> title.setText((Tmdb.episodeSeen(this, series, season, number) ? "✓  " : "") + number + ". " + e.optString("name"));
        label.run();
        TvUi.softFocus(row, k, 18 * k);
        row.setOnClickListener(v -> {
            Tmdb.setEpisodeSeen(this, series, season, number, !Tmdb.episodeSeen(this, series, season, number));
            label.run();
        });
        row.setOnKeyListener((v, code, ev) -> {
            if (code != KeyEvent.KEYCODE_MENU || ev.getAction() != KeyEvent.ACTION_UP) return code == KeyEvent.KEYCODE_MENU;
            rate(number, e.optString("name"));
            return true;
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Math.round(14 * k);
        if (!hasProviders) {
            row.setLayoutParams(lp);
            return row;
        }
        // The Watch pill sits beside the row: OK on the row still marks it seen, Menu still rates it.
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.HORIZONTAL);
        outer.setGravity(Gravity.CENTER_VERTICAL);
        outer.setLayoutParams(lp);
        outer.addView(row, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        View watch = TvUi.button(this, k, "▶  " + Strings.get(R.string.watch_episode_button), v -> watchEpisode(number));
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wp.leftMargin = Math.round(20 * k);
        outer.addView(watch, wp);
        return outer;
    }

    /** Asks the allowed apps about this episode: none has it, one opens at once, several are listed to pick from. */
    private void watchEpisode(int number) {
        if (asking || request == null) return;
        asking = true;
        new Thread(() -> {
            List<WatchProviders.Item> items = new ArrayList<>();
            try {
                JSONObject req = new JSONObject(request).put("season", season).put("episode", number);
                List<WatchProviders.Provider> on = WatchProviders.allowed(WatchProviders.found(this));
                for (List<WatchProviders.Item> got : WatchProviders.ask(this, on, req.toString()).values()) items.addAll(got);
            } catch (Exception ignored) {
            }
            main.post(() -> {
                asking = false;
                if (isFinishing() || isDestroyed()) return;
                if (items.isEmpty()) {
                    Toast.makeText(this, Strings.get(R.string.no_app_has_this_episode), Toast.LENGTH_LONG).show();
                } else if (items.size() == 1) {
                    WatchProviders.open(this, items.get(0));
                } else {
                    WatchProviders.choose(this, Strings.get(R.string.watch_episode, season, number), items);
                }
            });
        }).start();
    }

    private void rate(int number, String name) {
        if (!Tmdb.signedIn(this)) {
            Toast.makeText(this, Strings.get(R.string.connect_your_tmdb_account_in_settings_to_rate), Toast.LENGTH_LONG).show();
            return;
        }
        String[] items = new String[10];
        for (int i = 0; i < 10; i++) items[i] = "★ " + (10 - i);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(number + ". " + name)
                .setItems(items, (d, which) -> new Thread(() -> {
                    try {
                        Tmdb.rateEpisode(this, series, season, number, 10 - which);
                        Tmdb.setEpisodeSeen(this, series, season, number, true);
                        main.post(() -> recreate());
                    } catch (Exception ex) {
                        main.post(() -> Toast.makeText(this, ex.getMessage(), Toast.LENGTH_LONG).show());
                    }
                }).start())
                .show();
    }

    private boolean stopped;

    @Override protected void onStop() {
        super.onStop();
        stopped = true;
        TvUi.releaseImages(getWindow().getDecorView());   // covered: its pictures go, the cache and the disk keep them
    }

    @Override protected void onStart() {
        super.onStart();
        if (stopped) TvUi.restoreImages(getWindow().getDecorView());
        stopped = false;
    }
}
