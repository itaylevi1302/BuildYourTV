package io.cleanyourtv.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A row's "See more": all its titles in a vertical grid. A TMDB list (a path) loads its next page as the focus nears
 * the end; a list already in hand (cast, a person's credits, picks) shows as it is.
 */
public class GridActivity extends Activity {
    private static final String TITLE = "title", PATH = "path", KIND = "kind", TOKEN = "token";
    private static final int COLUMNS = 7, MAX_PAGES = 20;
    private static final Map<Integer, Object[]> LISTS = new HashMap<>();
    private static int next;

    /** A list already loaded; `open` is what OK on a poster does. */
    static void show(Activity a, String title, List<Tmdb.Title> titles, TvUi.OnTitle open) {
        int token;
        synchronized (LISTS) {
            token = ++next;
            LISTS.put(token, new Object[] {titles, open});
        }
        a.startActivity(new Intent(a, GridActivity.class).putExtra(TITLE, title).putExtra(TOKEN, token));
    }

    /** A TMDB list ("/discover/movie?…", a search), page after page. */
    static void open(Context c, String title, String path, String kind) {
        Intent i = new Intent(c, GridActivity.class).putExtra(TITLE, title).putExtra(PATH, path).putExtra(KIND, kind);
        if (!(c instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(i);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private float k;
    private TvUi.Grid grid;
    private TextView status;
    private String path, kind;
    private int page, pages = 1;
    private boolean loading;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        k = TvUi.k(this);
        path = getIntent().getStringExtra(PATH);
        kind = getIntent().getStringExtra(KIND);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(TvUi.BG);
        TvUi.Scroll scroll = new TvUi.Scroll(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        content.setPaddingRelative(Math.round(96 * k), Math.round(64 * k), 0, Math.round(80 * k));
        TextView h = TvUi.text(this, k, 56, Ui.displaySemibold, TvUi.INK);
        h.setText(getIntent().getStringExtra(TITLE));
        content.addView(h);
        status = TvUi.text(this, k, 26, Ui.regular, TvUi.DIM);
        status.setPaddingRelative(0, Math.round(12 * k), 0, Math.round(6 * k));
        content.addView(status);
        TvUi.OnTitle open = t -> DetailActivity.open(this, t.kind, t.id);
        if (path == null) {
            Object[] list;
            synchronized (LISTS) { list = LISTS.get(getIntent().getIntExtra(TOKEN, 0)); }
            if (list == null) {   // the app was restarted since: the list is gone
                finish();
                return;
            }
            @SuppressWarnings("unchecked") List<Tmdb.Title> titles = (List<Tmdb.Title>) list[0];
            open = (TvUi.OnTitle) list[1];
            grid = new TvUi.Grid(this, k, COLUMNS, open);
            grid.add(titles);
            status.setText(titles.size() == 1 ? Ui.tr("1 título", "1 title") : titles.size() + Ui.tr(" títulos", " titles"));
        } else {
            grid = new TvUi.Grid(this, k, COLUMNS, open);
            status.setText(Ui.tr("Carregando…", "Loading…"));
            more();
        }
        content.addView(grid);
        scroll.addView(content);
        if (path != null) grid.needMore = this::more;
        root.addView(scroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        if (path == null) grid.post(() -> { if (grid.getChildCount() > 0) grid.getChildAt(0).requestFocus(); });
    }

    @Override protected void onDestroy() {
        if (isFinishing() && path == null) {
            synchronized (LISTS) { LISTS.remove(getIntent().getIntExtra(TOKEN, 0)); }
        }
        super.onDestroy();
    }

    private void more() {
        if (loading || page >= Math.min(pages, MAX_PAGES)) return;
        loading = true;
        int want = page + 1;
        new Thread(() -> {
            try {
                JSONObject o = Tmdb.get(path + (path.contains("?") ? "&" : "?") + "page=" + want);
                List<Tmdb.Title> titles = Tmdb.titles(o, kind == null ? "movie" : kind);
                main.post(() -> {
                    if (isFinishing()) return;
                    page = want;
                    pages = Math.max(1, o.optInt("total_pages", 1));
                    int total = o.optInt("total_results", 0);
                    boolean first = grid.count == 0;
                    grid.add(titles);
                    status.setText(grid.count == 0 ? Ui.tr("Nada encontrado.", "Nothing found.")
                            : total > 0 ? total + Ui.tr(" títulos · dados do TMDB", " titles · data from TMDB") : "");
                    loading = false;
                    if (first && grid.getChildCount() > 0) grid.getChildAt(0).requestFocus();
                });
            } catch (Exception e) {
                main.post(() -> {
                    loading = false;
                    if (grid.count == 0) status.setText(Ui.tr("Não deu para carregar: ", "Couldn't load: ") + e.getMessage());
                });
            }
        }).start();
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
