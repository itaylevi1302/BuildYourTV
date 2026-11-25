package io.cleanyourtv.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * Hold OK on a movie or a show (a poster, Explore's banner): seen, My list, favorites and the account's lists, without
 * opening the title. The screen behind is told (Host) so its rows and seen badges follow.
 */
final class TitleMenu {
    private TitleMenu() {}

    /** A screen whose rows depend on the lists (Explore): told after a change (`lists`: My list or favorites, not seen). */
    interface Host { void titleChanged(boolean lists); }

    private interface Task { void run() throws Exception; }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    static void show(Activity a, View anchor, Tmdb.Title t) {
        if (!"movie".equals(t.kind) && !"tv".equals(t.kind)) return;
        boolean account = Tmdb.signedIn(a);
        new Thread(() -> {   // with an account, My list and favorites are the account's: ask TMDB first
            JSONObject states = account ? Tmdb.states(a, t.kind, t.id) : new JSONObject();
            boolean inList = account ? states.optBoolean("watchlist") : Tmdb.inWatchlist(a, t.kind, t.id);
            boolean favorite = states.optBoolean("favorite");
            int rated = account ? Tmdb.ratingOf(states) : 0;
            MAIN.post(() -> {
                if (!a.isFinishing() && anchor.isAttachedToWindow()) pick(a, anchor, t, account, inList, favorite, rated);
            });
        }, "title-menu").start();
    }

    private static void pick(Activity a, View anchor, Tmdb.Title t, boolean account, boolean inList, boolean favorite, int rated) {
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        boolean seen = Tmdb.isSeen(a, t.kind, t.id);
        labels.add("▶  " + Strings.get(R.string.watch));
        actions.add(() -> QuickWatch.open(a, t));
        labels.add(Strings.get(R.string.details));
        actions.add(() -> DetailActivity.open(a, t.kind, t.id));
        labels.add(inList ? Strings.get(R.string.remove_from_my_list) : "+  " + Strings.get(R.string.add_to_my_list));
        actions.add(() -> background(a, Strings.get(R.string.couldnt_save_prefix), () -> Tmdb.setWatchlist(a, t, !inList),
                inList ? Strings.get(R.string.removed_from_my_list) : Strings.get(R.string.added_to_my_list)));
        if (account) {
            labels.add(favorite ? Strings.get(R.string.remove_from_favorites) : "♥  " + Strings.get(R.string.mark_favorite));
            actions.add(() -> background(a, Strings.get(R.string.couldnt_favorite_prefix), () -> Tmdb.setFavorite(a, t, !favorite),
                    favorite ? Strings.get(R.string.removed_from_favorites) : Strings.get(R.string.added_to_favorites)));
        }
        labels.add(seen ? Strings.get(R.string.unmark_as_seen) : "✓  " + Strings.get(R.string.mark_as_seen));
        actions.add(() -> {
            TmdbLists.markSeen(a, t, !seen);
            changed(a, seen ? Strings.get(R.string.unmarked) : Strings.get(R.string.marked_as_seen), false);
        });
        if (account) {
            labels.add(rated > 0 ? "★  " + Strings.get(R.string.your_rating_prefix) + rated : "★  " + Strings.get(R.string.rate));
            actions.add(() -> rate(a, t, rated, v -> {
                changed(a, v > 0 ? Strings.get(R.string.your_rating_prefix) + v : Strings.get(R.string.unmarked), false);
                TvUi.refreshSeen(a.getWindow().getDecorView());
            }));
            if ("movie".equals(t.kind)) {   // TMDB's v3 lists hold movies only
                labels.add("≡  " + Strings.get(R.string.add_to_a_list));
                actions.add(() -> addToList(a, t.id));
            }
        }
        labels.add(Strings.get(R.string.cancel));
        actions.add(null);
        Rect at = TvUi.rectOf(anchor);
        TvUi.popover(a, anchor, at, t.name, labels.toArray(new String[0]), which -> {
            Runnable r = actions.get(which);
            if (r != null) r.run();
        });
    }

    /** The 1 to 10 rating (or removing it) in a dropdown at the middle of the screen; `done` gets the new rating, 0 when removed. */
    static void rate(Activity a, Tmdb.Title t, int current, IntConsumer done) {
        String[] items = new String[current > 0 ? 11 : 10];
        for (int i = 0; i < 10; i++) items[i] = "★ " + (10 - i);
        if (current > 0) items[10] = Strings.get(R.string.remove_my_rating);
        View root = a.getWindow().getDecorView();
        int w = root.getWidth(), h = root.getHeight();
        Rect middle = new Rect(w / 2, h / 2, w / 2, h / 2 + 1);
        TvUi.dropdown(a, root, middle, Strings.get(R.string.your_rating_for_prefix) + t.name, items, current > 0 ? 10 - current : -1, which -> new Thread(() -> {
            try {
                int value = 0;
                if (which == 10) {
                    Tmdb.unrate(a, t);
                } else {
                    value = 10 - which;
                    Tmdb.rate(a, t, value);
                    Tmdb.setSeen(a, t, true);
                }
                int v = value;
                MAIN.post(() -> done.accept(v));
            } catch (Exception e) {
                MAIN.post(() -> Toast.makeText(a, Strings.get(R.string.couldnt_save_the_rating_prefix) + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }, "title-rate").start());
    }

    /** The account's lists to pick from, or a new one (movies only). */
    static void addToList(Activity a, int movie) {
        new Thread(() -> {
            try {
                List<String[]> lists = Tmdb.lists(a);
                MAIN.post(() -> { if (!a.isFinishing()) pickList(a, movie, lists); });
            } catch (Exception e) {
                MAIN.post(() -> Toast.makeText(a, e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }, "title-lists").start();
    }

    private static void pickList(Activity a, int movie, List<String[]> lists) {
        String[] items = new String[lists.size() + 1];
        for (int i = 0; i < lists.size(); i++) items[i] = lists.get(i)[1] + "  (" + lists.get(i)[2] + ")";
        items[lists.size()] = "+  " + Strings.get(R.string.new_list);
        new AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(Strings.get(R.string.add_to_a_list_2))
                .setItems(items, (d, which) -> {
                    if (which < lists.size()) {
                        String list = lists.get(which)[0], name = lists.get(which)[1];
                        background(a, Strings.get(R.string.couldnt_add_prefix), () -> Tmdb.addToList(a, list, movie),
                                Strings.get(R.string.added_to_prefix) + name);
                        return;
                    }
                    EditText field = new EditText(a);
                    field.setSingleLine(true);
                    field.setHint(Strings.get(R.string.list_name));
                    new AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                            .setTitle(Strings.get(R.string.new_list_2))
                            .setView(field)
                            .setPositiveButton(Strings.get(R.string.create), (d2, w2) -> background(a, Strings.get(R.string.couldnt_create_prefix),
                                    () -> Tmdb.addToList(a, Tmdb.createList(a, field.getText().toString().trim()), movie),
                                    Strings.get(R.string.list_created)))
                            .setNegativeButton(Strings.get(R.string.cancel), null)
                            .show();
                })
                .show();
    }

    /** Runs `task` off the main thread (the account is TMDB's), then says `done` and refreshes the screen. */
    private static void background(Activity a, String failure, Task task, String done) {
        new Thread(() -> {
            try {
                task.run();
                MAIN.post(() -> changed(a, done, true));
            } catch (Exception e) {
                MAIN.post(() -> Toast.makeText(a, failure + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }, "title-action").start();
    }

    private static void changed(Activity a, String done, boolean lists) {
        if (a.isFinishing()) return;
        Toast.makeText(a, done, Toast.LENGTH_SHORT).show();
        TvUi.refreshSeen(a.getWindow().getDecorView());
        if (a instanceof Host) ((Host) a).titleChanged(lists);
    }
}
