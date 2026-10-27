package io.cleanyourtv.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Explore, laid out like the Apple TV app and the home screen: a banner, rows of up to ten posters with "See more",
 * catalogs (Movies, Shows, Kids, Anime) as tabs over a grid, search and categories as the same filter (type,
 * categories, release year; categories can be saved to the menu), and a menu on the left (Left at the edge opens
 * it, Right or Back closes it). The menu starts with Explore and its settings button, then Search, Home and My list;
 * the rest is the person's to order or hide in Settings. The background is the focused title's picture, softly
 * blurred. With EXTRA_SECTION = "watchlist" (the remote's bookmark button) it opens on My list.
 */
public class ExploreActivity extends Activity {
    static final String EXTRA_SECTION = "section";
    private static final String HOME = "home", SEARCH = "search", LIST = "watchlist", TOP = "top", MOVIES = "movie",
            SHOWS = "tv", KIDS = "kids", ANIME = "anime", CATS = "categories", SETTINGS = "settings", SERVICE = "service:",
            SAVED = "f:";
    /** The menu's items the person can move or hide, in their first order (services and saved filters follow). */
    private static final String[] MOVABLE = {TOP, MOVIES, SHOWS, KIDS, ANIME, CATS};
    private static final long HERO_MS = 9000;
    private static final int HERO_H = 720, HERO_BLEED = 144, INSET = 120;

    /**
     * Categories: key, Portuguese, English, movie genre, show genre, keyword, origin country, original language (to
     * filter search results, which TMDB doesn't filter).
     */
    private static final String[][] GENRES = {
        {"acao", "Ação", "Action", "28", "10759", null, null, null},
        {"aventura", "Aventura", "Adventure", "12", "10759", null, null, null},
        {"anime", "Anime", "Anime", "16", "16", "210024", null, "ja"},
        {"animacao", "Animação", "Animation", "16", "16", null, null, null},
        {"comedia", "Comédia", "Comedy", "35", "35", null, null, null},
        {"crime", "Crime", "Crime", "80", "80", null, null, null},
        {"documentarios", "Documentários", "Documentaries", "99", "99", null, null, null},
        {"drama", "Drama", "Drama", "18", "18", null, null, null},
        {"familia", "Família", "Family", "10751", "10751", null, null, null},
        {"fantasia", "Fantasia", "Fantasy", "14", "10765", null, null, null},
        {"ficcao", "Ficção científica", "Sci-fi", "878", "10765", null, null, null},
        {"guerra", "Guerra", "War", "10752", "10768", null, null, null},
        {"historia", "História", "History", "36", null, null, null, null},
        {"infantil", "Infantil", "Kids", "10751", "10762", null, null, null},
        {"misterio", "Mistério", "Mystery", "9648", "9648", null, null, null},
        {"musica", "Música", "Music", "10402", null, null, null, null},
        {"faroeste", "Faroeste", "Western", "37", "37", null, null, null},
        {"reality", "Reality", "Reality", null, "10764", null, null, null},
        {"romance", "Romance", "Romance", "10749", null, null, null, null},
        {"suspense", "Suspense", "Thriller", "53", "9648", null, null, null},
        {"terror", "Terror", "Horror", "27", null, null, null, null},
        {"novela", "Novelas", "Soaps", null, "10766", null, null, null},
        {"doramas", "Doramas", "K-dramas", null, "18", null, "KR", "ko"},
        {"nacionais", "Nacionais", "Brazilian", null, null, null, "BR", "pt"},
    };

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(4);
    private float k;
    private FrameLayout root;
    private TvUi.Scroll scroll;
    private LinearLayout page, rows, menu, menuMovable;
    private TextView status;
    private View shade, overlay;
    private String current = HOME;
    private boolean fromBookmark, menuOpen, resumedOnce;
    private View beforeMenu, beforeOverlay;
    private int generation;
    private final Map<String, TextView> menuItems = new LinkedHashMap<>();
    private final Map<Integer, View> placed = new HashMap<>();
    private final Map<Integer, String> placedKeys = new HashMap<>();
    private List<Section> sections = new ArrayList<>();
    private List<Service> services;

    /** A streaming service with an app: TMDB lists some more than once ("with ads"), so one entry per app. */
    private static final class Service {
        final String key, name, ids, logo;
        Service(String key, String name, String ids, String logo) {
            this.key = key;
            this.name = name;
            this.ids = ids;
            this.logo = logo;
        }
    }

    private static final class Section {
        final String title;
        final Callable<List<Tmdb.Title>> load;
        String morePath, moreKind;
        Runnable more;
        boolean personal, ranked;

        Section(String title, Callable<List<Tmdb.Title>> load) {
            this.title = title;
            this.load = load;
        }

        Section more(String path, String kind) {
            morePath = path;
            moreKind = kind;
            return this;
        }

        Section more(Runnable r) {
            more = r;
            return this;
        }

        Section personal() {
            personal = true;
            return this;
        }

        Section ranked() {
            ranked = true;
            return this;
        }
    }

    /** What search and categories show: type, categories (all of them), release year, and words (search only). */
    private static final class Filter {
        String kind = "all", year = "", query = "", sort = "popularity";
        final Set<String> genres = new LinkedHashSet<>();
        boolean services;   // only the person's services (the catalogs)

        Filter copy() {
            Filter f = fromJson(json());
            f.query = query;
            f.services = services;
            return f;
        }

        String json() {
            try {
                return new JSONObject().put("kind", kind).put("year", year).put("sort", sort).put("genres", new JSONArray(genres)).toString();
            } catch (Exception e) {
                return "{}";
            }
        }

        static Filter fromJson(String text) {
            Filter f = new Filter();
            try {
                JSONObject o = new JSONObject(text);
                f.kind = o.optString("kind", "all");
                f.year = o.optString("year", "");
                f.sort = o.optString("sort", "popularity");
                JSONArray g = o.optJSONArray("genres");
                for (int i = 0; g != null && i < g.length(); i++) f.genres.add(g.getString(i));
            } catch (Exception ignored) {
            }
            return f;
        }

        static Filter of(String kind, String... genres) {
            Filter f = new Filter();
            f.kind = kind;
            f.services = true;
            for (String g : genres) f.genres.add(g);
            return f;
        }

        boolean empty() {
            return kind.equals("all") && year.isEmpty() && genres.isEmpty();
        }

        String label() {
            List<String> parts = new ArrayList<>();
            for (String g : genres) {
                String[] d = genre(g);
                if (d != null) parts.add(Ui.tr(d[1], d[2]));
            }
            if (!year.isEmpty()) parts.add(yearLabel(year));
            if (kind.equals("movie")) parts.add(Ui.tr("Filmes", "Movies"));
            if (kind.equals("tv")) parts.add(Ui.tr("Séries", "Shows"));
            return parts.isEmpty() ? Ui.tr("Tudo", "Everything") : TextUtils.join(" · ", parts);
        }
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        Images.init(this);
        k = TvUi.k(this);
        fromBookmark = "watchlist".equals(getIntent().getStringExtra(EXTRA_SECTION));
        root = new FrameLayout(this);
        root.setBackgroundColor(TvUi.BG);
        root.addView(backdrops(), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        scroll = new TvUi.Scroll(this);
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setClipChildren(false);
        page.setPaddingRelative(0, 0, 0, Math.round(80 * k));
        scroll.addView(page);
        root.addView(scroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(burger(), burgerParams());
        shade = new View(this);
        shade.setBackgroundColor(0xB3000000);
        shade.setAlpha(0);
        shade.setVisibility(View.GONE);
        root.addView(shade, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(buildMenu(), new FrameLayout.LayoutParams(Math.round(560 * k), ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        scroll.getViewTreeObserver().addOnGlobalFocusChangeListener((was, now) -> followFocus(now));
        if (!Tmdb.hasKey()) {
            header(Ui.tr("Explorar", "Explore"), Ui.tr("Sem chave do TMDB: coloque a sua em launcher-ui/config.properties e instale de novo.",
                    "No TMDB key: add yours to launcher-ui/config.properties and install again."));
            return;
        }
        io.execute(() -> {
            services();
            main.post(() -> { if (!isFinishing()) fillMenu(); });
        });
        showPage(fromBookmark ? LIST : HOME);
    }

    @Override protected void onResume() {
        super.onResume();
        if (hero != null && !menuOpen) restartHero();
        if (resumedOnce && Tmdb.hasKey()) {   // back from a title page: the list, the picks and what was seen may have changed
            refreshPersonal();
            TvUi.refreshSeen(page);
        }
        resumedOnce = true;
    }

    @Override protected void onPause() {
        main.removeCallbacks(heroTick);
        super.onPause();
    }

    @Override protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    // MARK: - Background: the focused title's picture

    private ImageView backA, backB;
    private boolean backFront;
    private String backUrl, backWant;
    private final Runnable backSoon = this::loadBackdrop;

    private View backdrops() {
        FrameLayout box = new FrameLayout(this);
        backA = new ImageView(this);
        backB = new ImageView(this);
        for (ImageView v : new ImageView[] {backA, backB}) {
            v.setScaleType(ImageView.ScaleType.CENTER_CROP);
            v.setAlpha(0f);
            box.addView(v, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        View dim = new View(this);
        dim.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[] {0xE60B0D12, 0xC70B0D12, 0xA30B0D12}));
        box.addView(dim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return box;
    }

    private void followFocus(View now) {
        if (menuOpen) return;
        Tmdb.Title t = now != null && now == hero && !heroList.isEmpty() ? (Tmdb.Title) heroList.get(heroAt)[0] : TvUi.titleOf(now);
        if (t == null || t.backdrop.isEmpty() || "null".equals(t.backdrop)) return;
        backWant = "https://image.tmdb.org/t/p/w300" + t.backdrop;
        main.removeCallbacks(backSoon);
        main.postDelayed(backSoon, 350);
    }

    /** Small and blurred: a soft background, lighter than the home screen's, that costs nothing to draw. */
    private void loadBackdrop() {
        String url = backWant;
        if (url == null || url.equals(backUrl)) return;
        backUrl = url;
        io.execute(() -> {
            Bitmap b = Images.fetch(url);
            if (b == null) return;
            Bitmap soft = blur(Bitmap.createScaledBitmap(b, 160, Math.max(1, Math.round(160f * b.getHeight() / b.getWidth())), true), 3);
            main.post(() -> {
                if (!url.equals(backUrl) || isFinishing()) return;
                ImageView in = backFront ? backA : backB, out = backFront ? backB : backA;
                backFront = !backFront;
                in.setImageBitmap(soft);
                in.animate().alpha(1).setDuration(600).start();
                out.animate().alpha(0).setDuration(600).start();
            });
        });
    }

    /** Three box blurs on a small copy: close to a Gaussian, smooth when stretched over the screen. */
    private static Bitmap blur(Bitmap small, int radius) {
        int w = small.getWidth(), h = small.getHeight();
        int[] px = new int[w * h], tmp = new int[w * h];
        small.getPixels(px, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < 3; pass++) {
            boxBlur(px, tmp, w, h, radius, true);
            boxBlur(tmp, px, w, h, radius, false);
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
    }

    private static void boxBlur(int[] in, int[] out, int w, int h, int r, boolean across) {
        int lines = across ? h : w, len = across ? w : h;
        for (int line = 0; line < lines; line++) {
            for (int i = 0; i < len; i++) {
                int rs = 0, gs = 0, bs = 0, n = 0;
                for (int j = Math.max(0, i - r); j <= Math.min(len - 1, i + r); j++) {
                    int c = in[across ? line * w + j : j * w + line];
                    rs += (c >> 16) & 0xFF;
                    gs += (c >> 8) & 0xFF;
                    bs += c & 0xFF;
                    n++;
                }
                out[across ? line * w + i : i * w + line] = 0xFF000000 | (rs / n) << 16 | (gs / n) << 8 | (bs / n);
            }
        }
    }

    // MARK: - Menu

    /** Three short bars at the top left: the menu is there, to the left. */
    private View burger() {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < 3; i++) {
            View bar = new View(this);
            bar.setBackground(TvUi.round(0xB3FFFFFF, 2 * k));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Math.round(34 * k), Math.round(4 * k));
            lp.topMargin = i == 0 ? 0 : Math.round(7 * k);
            b.addView(bar, lp);
        }
        return b;
    }

    private FrameLayout.LayoutParams burgerParams() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart(Math.round(52 * k));
        lp.topMargin = Math.round(66 * k);
        return lp;
    }

    private View buildMenu() {
        TvUi.Scroll box = new TvUi.Scroll(this);
        box.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[] {0xFC12151C, 0xF512151C}));
        box.setElevation(24 * k);
        menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setPaddingRelative(Math.round(40 * k), Math.round(48 * k), Math.round(40 * k), Math.round(60 * k));
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = TvUi.text(this, k, 40, Ui.displaySemibold, TvUi.INK);
        title.setText(Ui.tr("Explorar", "Explore"));
        title.setPaddingRelative(Math.round(26 * k), 0, 0, 0);
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        top.addView(TvUi.iconButton(this, k, "settings", null, v -> {
            closeMenu(false);
            showPage(SETTINGS);
        }));
        top.setPaddingRelative(0, 0, 0, Math.round(26 * k));
        menu.addView(top);
        menuItem(menu, SEARCH, Ui.tr("Buscar", "Search"), null);
        menuItem(menu, HOME, Ui.tr("Início", "Home"), null);
        menuItem(menu, LIST, Ui.tr("Minha lista", "My list"), null);
        View line = new View(this);
        line.setBackgroundColor(0x1FFFFFFF);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Math.round(2 * k)));
        ll.setMargins(Math.round(26 * k), Math.round(14 * k), Math.round(26 * k), Math.round(14 * k));
        menu.addView(line, ll);
        menuMovable = new LinearLayout(this);
        menuMovable.setOrientation(LinearLayout.VERTICAL);
        menu.addView(menuMovable);
        fillMenu();
        box.addView(menu);
        box.setVisibility(View.GONE);
        box.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        return box;
    }

    /** The movable part, in the person's order, without what they hid. */
    private void fillMenu() {
        for (int i = 0; i < menuMovable.getChildCount(); i++) menuItems.values().remove(menuMovable.getChildAt(i).getTag());
        menuMovable.removeAllViews();
        for (String id : order()) {
            if (hidden().contains(id)) continue;
            Service s = id.startsWith(SERVICE) ? service(id) : null;
            menuItem(menuMovable, id, label(id), s != null ? s.logo : null);
        }
        restyleMenu();
    }

    private void menuItem(LinearLayout into, String id, String label, String logo) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.HORIZONTAL);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPaddingRelative(Math.round(26 * k), Math.round(16 * k), Math.round(26 * k), Math.round(16 * k));
        if (logo != null) {
            ImageView img = new ImageView(this);
            img.setClipToOutline(true);
            img.setBackground(TvUi.round(TvUi.CARD, 10 * k));
            Images.load(img, logo.isEmpty() ? null : Tmdb.LOGO + logo);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Math.round(46 * k), Math.round(46 * k));
            lp.setMarginEnd(Math.round(20 * k));
            item.addView(img, lp);
        }
        TextView t = TvUi.text(this, k, 30, Ui.medium, TvUi.DIM);
        t.setText(label);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        item.addView(t);
        item.setFocusable(true);
        item.setTag(t);
        item.setOnFocusChangeListener((v, has) -> styleItem(id, item));
        item.setOnClickListener(v -> {
            closeMenu(false);
            showPage(id);
        });
        if (into == menuMovable) {   // a long press takes it off the menu, after asking
            item.setOnLongClickListener(v -> {
                boolean saved = id.startsWith(SAVED);
                TvUi.confirm(this, saved ? Ui.tr("Remover “", "Remove “") + label + Ui.tr("” do menu?", "” from the menu?")
                                : Ui.tr("Ocultar “", "Hide “") + label + Ui.tr("” do menu? Volta em Ajustes.", "”? It comes back in Settings."),
                        saved ? Ui.tr("Remover", "Remove") : Ui.tr("Ocultar", "Hide"), () -> {
                            if (saved) removeSaved(id);
                            else setHidden(id, true);
                            fillMenu();
                            ((View) (menuItems.get(HOME)).getParent()).requestFocus();
                        });
                return true;
            });
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Math.round(6 * k);
        into.addView(item, lp);
        menuItems.put(id, t);
        styleItem(id, item);
    }

    /** Focused: a white pill; the page showing: bright text on a soft pill; the others: dimmed. */
    private void styleItem(String id, View item) {
        TextView t = (TextView) item.getTag();
        boolean focused = item.hasFocus(), here = id.equals(current);
        item.setBackground(focused ? TvUi.round(TvUi.INK, 18 * k) : here ? TvUi.round(0x1FFFFFFF, 18 * k) : null);
        t.setTextColor(focused ? 0xFF14161A : here ? TvUi.INK : TvUi.DIM);
        t.setTypeface(here || focused ? Ui.semibold : Ui.medium);
    }

    private void restyleMenu() {
        for (Map.Entry<String, TextView> e : menuItems.entrySet()) styleItem(e.getKey(), (View) e.getValue().getParent());
    }

    private void openMenu() {
        if (menuOpen) return;
        menuOpen = true;
        beforeMenu = getCurrentFocus();
        ViewGroup box = (ViewGroup) menu.getParent();
        box.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        box.setVisibility(View.VISIBLE);
        box.setTranslationX((Ui.he ? 560 : -560) * k);   // Hebrew: the menu slides in from the right
        box.animate().translationX(0).setDuration(220).start();
        shade.setVisibility(View.VISIBLE);
        shade.animate().alpha(1).setDuration(220).start();
        restyleMenu();
        TextView here = menuItems.get(current);
        ((View) (here != null ? here : menuItems.get(HOME)).getParent()).requestFocus();
        scroll.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);   // only the menu takes the focus now
        main.removeCallbacks(heroTick);
    }

    /** `restore`: back to what was focused (Right, Back); otherwise the new page takes the focus. */
    private void closeMenu(boolean restore) {
        if (!menuOpen) return;
        menuOpen = false;
        ViewGroup box = (ViewGroup) menu.getParent();
        box.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        box.animate().translationX((Ui.he ? 560 : -560) * k).setDuration(200)
                .withEndAction(() -> { if (!menuOpen) box.setVisibility(View.GONE); }).start();
        shade.animate().alpha(0).setDuration(200).withEndAction(() -> { if (!menuOpen) shade.setVisibility(View.GONE); }).start();
        scroll.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        if (restore && beforeMenu != null && beforeMenu.isAttachedToWindow()) beforeMenu.requestFocus();
        else focusPage();
        if (hero != null) restartHero();
    }

    private long keyAt, pageAt;   // the banner arrives late: it takes the focus only if no key was pressed since

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        keyAt = android.os.SystemClock.uptimeMillis();
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (overlay != null) {
            if (code == KeyEvent.KEYCODE_BACK) {
                if (!down) closeOverlay();
                return true;
            }
            return super.dispatchKeyEvent(e);
        }
        if (menuOpen) {
            // Away from the menu closes it; toward the edge does nothing (mirrored in Hebrew, where the menu is on the right).
            int away = Ui.he ? KeyEvent.KEYCODE_DPAD_LEFT : KeyEvent.KEYCODE_DPAD_RIGHT;
            int edge = Ui.he ? KeyEvent.KEYCODE_DPAD_RIGHT : KeyEvent.KEYCODE_DPAD_LEFT;
            if (code == KeyEvent.KEYCODE_BACK || code == away) {
                if (!down) closeMenu(true);
                return true;
            }
            if (code == edge) return true;
            if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) {
                View f = getCurrentFocus();
                View next = f == null ? null : f.focusSearch(code == KeyEvent.KEYCODE_DPAD_UP ? View.FOCUS_UP : View.FOCUS_DOWN);
                if (next == null || !inside(next, menu)) return true;
            }
            return super.dispatchKeyEvent(e);
        }
        if (code == (Ui.he ? KeyEvent.KEYCODE_DPAD_RIGHT : KeyEvent.KEYCODE_DPAD_LEFT) && down && Tmdb.hasKey()) {
            View f = getCurrentFocus();
            boolean heroBack = f != null && f == hero && heroAt > 0;   // the banner goes back first, then the menu
            if (!heroBack && (f == null || f.focusSearch(Ui.he ? View.FOCUS_RIGHT : View.FOCUS_LEFT) == null)) {
                openMenu();
                return true;
            }
        }
        if (code == KeyEvent.KEYCODE_BACK && !fromBookmark && !HOME.equals(current) && Tmdb.hasKey()) {
            if (!down && !e.isCanceled()) showPage(HOME);
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    private static boolean inside(View v, View parent) {
        for (View p = v; p != null; p = p.getParent() instanceof View ? (View) p.getParent() : null) if (p == parent) return true;
        return false;
    }

    // MARK: - The menu's order, hidden items and saved filters (kept on the TV)

    private SharedPreferences prefs() {
        return getSharedPreferences("explore", MODE_PRIVATE);
    }

    private List<String> readList(String key) {
        List<String> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs().getString(key, "[]"));
            for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        } catch (Exception ignored) {
        }
        return out;
    }

    private void writeList(String key, List<String> list) {
        prefs().edit().putString(key, new JSONArray(list).toString()).apply();
    }

    private JSONObject saved() {
        try {
            return new JSONObject(prefs().getString("saved", "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /** Every movable item, in the person's order; ones they never placed (a new service, a new save) go last. */
    private List<String> order() {
        List<String> all = new ArrayList<>();
        for (String id : MOVABLE) all.add(id);
        for (java.util.Iterator<String> it = saved().keys(); it.hasNext(); ) all.add(it.next());
        if (services != null) for (Service s : services) all.add(SERVICE + s.key);
        List<String> out = new ArrayList<>();
        for (String id : readList("order")) if (all.contains(id) && !out.contains(id)) out.add(id);
        for (String id : all) if (!out.contains(id)) out.add(id);
        return out;
    }

    private Set<String> hidden() {
        return new HashSet<>(readList("hidden"));
    }

    private void setHidden(String id, boolean hide) {
        Set<String> h = hidden();
        if (hide) h.add(id); else h.remove(id);
        writeList("hidden", new ArrayList<>(h));
    }

    private void move(String id, int by) {
        List<String> o = order();
        int i = o.indexOf(id), j = i + by;
        if (i < 0 || j < 0 || j >= o.size()) return;
        o.set(i, o.get(j));
        o.set(j, id);
        writeList("order", o);
    }

    private void saveFilter(String id, String name, Filter f) {
        try {
            JSONObject all = saved();
            all.put(id, new JSONObject().put("name", name).put("filter", f.json()));
            prefs().edit().putString("saved", all.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    private void removeSaved(String id) {
        JSONObject all = saved();
        all.remove(id);
        prefs().edit().putString("saved", all.toString()).apply();
    }

    private String label(String id) {
        switch (id) {
            case TOP: return Ui.tr("Top 10 do dia", "Today's top 10");
            case MOVIES: return Ui.tr("Filmes", "Movies");
            case SHOWS: return Ui.tr("Séries", "Shows");
            case KIDS: return Ui.tr("Infantil", "Kids");
            case ANIME: return Ui.tr("Animes", "Anime");
            case CATS: return Ui.tr("Categorias", "Categories");
            case SEARCH: return Ui.tr("Buscar", "Search");
            case HOME: return Ui.tr("Início", "Home");
            case LIST: return Ui.tr("Minha lista", "My list");
            case SETTINGS: return Ui.tr("Ajustes", "Settings");
            default:
                if (id.startsWith(SAVED)) {
                    JSONObject s = saved().optJSONObject(id);
                    return s == null ? id : s.optString("name");
                }
                Service s = id.startsWith(SERVICE) ? service(id) : null;
                return s != null ? s.name : id;
        }
    }

    private List<String> recent() {
        return readList("recent");
    }

    private void remember(String q) {
        if (q == null || q.trim().length() < 2) return;
        List<String> list = recent();
        list.removeIf(x -> x.equalsIgnoreCase(q.trim()));
        list.add(0, q.trim());
        writeList("recent", list.size() > 10 ? new ArrayList<>(list.subList(0, 10)) : list);
    }

    // MARK: - Pages

    private void showPage(String id) {
        current = id;
        pageAt = android.os.SystemClock.uptimeMillis() + 1;
        int gen = ++generation;
        placed.clear();
        placedKeys.clear();
        hero = null;
        feed = null;
        closeOverlay();
        main.removeCallbacks(heroTick);
        main.removeCallbacks(searchSoon);
        main.removeCallbacks(tabSoon);
        page.removeAllViews();
        scroll.top();
        restyleMenu();
        boolean withHero = id.equals(HOME) || id.equals(TOP) || id.startsWith(SERVICE);
        if (withHero) page.addView(buildHero());
        else header(label(id), subtitle(id));
        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setClipChildren(false);
        rows.setClipToPadding(false);
        rows.setPaddingRelative(Math.round(INSET * k), 0, 0, 0);
        LinearLayout.LayoutParams rowsLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (withHero) rowsLp.topMargin = -Math.round(HERO_BLEED * k);   // the first row sits over the banner's picture
        page.addView(rows, rowsLp);
        status = TvUi.text(this, k, 26, Ui.regular, TvUi.DIM);
        status.setPaddingRelative(0, Math.round(30 * k), 0, 0);
        status.setText(Ui.tr("Carregando…", "Loading…"));
        rows.addView(status);
        switch (id) {
            case SEARCH: buildFilterPage(true, null); return;
            case CATS: buildFilterPage(false, null); return;
            case LIST: buildList(); return;
            case SETTINGS: buildSettings(); return;
            case MOVIES: case SHOWS: case KIDS: case ANIME: buildCatalog(id); return;
            default:
                if (id.startsWith(SAVED)) {
                    buildFilterPage(false, id);
                    return;
                }
        }
        io.execute(() -> {   // the rows don't wait for the banner (on Home it asks for picks, title by title)
            List<Section> list = sections(id);
            main.post(() -> {
                if (gen != generation || isFinishing()) return;
                sections = list;
                load(gen, list, false);
            });
        });
        if (withHero) {
            io.execute(() -> {
                List<Object[]> banner = heroItems(id);
                main.post(() -> { if (gen == generation && !isFinishing()) setHero(banner); });
            });
        }
    }

    private String subtitle(String id) {
        switch (id) {
            case LIST:
                return Tmdb.signedIn(this) ? Ui.tr("Da sua conta TMDB", "From your TMDB account") + " · " + Tmdb.accountName(this)
                        : Ui.tr("Salva nesta TV. Conecte sua conta TMDB em Ajustes para sincronizar.", "Kept on this TV. Connect your TMDB account in Settings to sync.");
            case SEARCH: return Ui.tr("Filmes, séries e pessoas · filtre por tipo, categoria e ano", "Movies, shows and people · filter by type, category and year");
            case CATS: return Ui.tr("Escolha categorias e ano no filtro; salve para ter no menu", "Pick categories and year in the filter; save it to the menu");
            case SETTINGS: return Ui.tr("Conta, serviços e a ordem do menu", "Account, services and the menu's order");
            case MOVIES: case SHOWS: case KIDS: case ANIME: return Ui.tr("Nos seus serviços · dados do TMDB", "On your services · data from TMDB");
            default: return id.startsWith(SAVED) ? Ui.tr("Filtro salvo", "Saved filter") : Ui.tr("Dados do TMDB", "Data from TMDB");
        }
    }

    private void header(String title, String sub) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPaddingRelative(Math.round(INSET * k), Math.round(56 * k), 0, Math.round(10 * k));
        TextView h = TvUi.text(this, k, 60, Ui.displaySemibold, TvUi.INK);
        h.setText(title);
        box.addView(h);
        TextView s = TvUi.text(this, k, 26, Ui.regular, TvUi.DIM);
        s.setText(sub);
        s.setPaddingRelative(0, Math.round(12 * k), 0, 0);
        box.addView(s);
        page.addView(box, 0);
    }

    private void load(int gen, List<Section> list, boolean personalOnly) {
        int[] left = {0};
        for (Section s : list) if (!personalOnly || s.personal) left[0]++;
        for (int i = 0; i < list.size(); i++) {
            final int slot = i;
            final Section s = list.get(i);
            if (personalOnly && !s.personal) continue;
            io.execute(() -> {
                List<Tmdb.Title> titles;
                try {
                    titles = s.load.call();
                } catch (Exception e) {
                    AppLog.w("explore " + s.title + ": " + e.getMessage());
                    titles = new ArrayList<>();
                }
                final List<Tmdb.Title> got = titles;
                main.post(() -> {
                    if (gen != generation || isFinishing()) return;
                    place(slot, s, got);
                    if (personalOnly) return;
                    left[0]--;
                    status.setVisibility(placed.isEmpty() ? View.VISIBLE : View.GONE);
                    if (placed.isEmpty() && left[0] == 0) {
                        status.setText(Ui.tr("Não deu para buscar os títulos agora. Confira a internet da TV.", "Couldn't fetch titles now. Check the TV's internet."));
                    }
                });
            });
        }
    }

    private void refreshPersonal() {
        if (current.equals(LIST)) {
            showTab(tab, true);
            return;
        }
        if (current.equals(HOME)) load(generation, sections, true);
    }

    /** Puts a section in its slot (they arrive in any order); an unchanged one stays, with the focus where it is. */
    private void place(int slot, Section s, List<Tmdb.Title> titles) {
        StringBuilder keys = new StringBuilder(s.title);
        for (Tmdb.Title t : titles) keys.append(',').append(t.key());
        if (keys.toString().equals(placedKeys.get(slot))) return;
        placedKeys.put(slot, keys.toString());
        View old = placed.remove(slot);
        boolean hadFocus = old != null && old.hasFocus();
        if (old != null) rows.removeView(old);
        if (titles.isEmpty()) {
            if (hadFocus) focusPage();
            return;
        }
        View view = s.ranked ? ranked(s, titles) : TvUi.row(this, k, s.title, titles, this::open, moreOf(s), Math.round(INSET * k));
        placed.put(slot, view);
        int index = rows.indexOfChild(status) + 1;
        for (Map.Entry<Integer, View> e : placed.entrySet()) {
            if (e.getKey() < slot && e.getValue().getParent() == rows) index = Math.max(index, rows.indexOfChild(e.getValue()) + 1);
        }
        rows.addView(view, Math.min(index, rows.getChildCount()));
        View focus = getCurrentFocus();
        if (hadFocus || (!menuOpen && overlay == null && (focus == null || !inside(focus, page)))) focusPage();
    }

    private Runnable moreOf(Section s) {
        if (s.more != null) return s.more;
        if (s.morePath != null) return () -> GridActivity.open(this, s.title, s.morePath, s.moreKind);
        return null;
    }

    private void open(Tmdb.Title t) {
        if (current.equals(SEARCH) && filter != null && !filter.query.isEmpty()) remember(filter.query);
        DetailActivity.open(this, t.kind, t.id);
    }

    /** The top 10: a title and a grid, each poster with its big number. */
    private View ranked(Section s, List<Tmdb.Title> titles) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setClipChildren(false);
        box.setPaddingRelative(0, Math.round(26 * k), 0, Math.round(10 * k));
        TextView h = TvUi.text(this, k, 32, Ui.semibold, TvUi.INK);
        h.setText(s.title);
        box.addView(h);
        TvUi.Grid grid = new TvUi.Grid(this, k, 5, this::open);
        for (int i = 0; i < titles.size(); i++) {
            View cell = TvUi.poster(this, k, titles.get(i), this::open);
            FrameLayout card = (FrameLayout) ((ViewGroup) cell).getChildAt(0);
            TextView num = TvUi.text(this, k, 120, Ui.displaySemibold, TvUi.INK);
            num.setText(String.valueOf(i + 1));
            num.setShadowLayer(18 * k, 0, 4 * k, 0xE6000000);
            num.setPaddingRelative(Math.round(12 * k), 0, 0, Math.round(4 * k));
            card.addView(num, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM | Gravity.START));
            grid.add(cell);
        }
        box.addView(grid);
        return box;
    }

    /** The page's first thing: the banner, or the first focusable thing under the title. */
    private void focusPage() {
        if (menuOpen || overlay != null) return;
        if (hero != null && !heroList.isEmpty()) {
            hero.requestFocus();
            return;
        }
        for (int i = 0; i < rows.getChildCount(); i++) {
            View v = rows.getChildAt(i);
            if (v != status && v.requestFocus()) return;
        }
    }

    // MARK: - Tabs over a paged grid (My list, the catalogs)

    private int tab;
    private LinearLayout tabBar, tabBody;
    private String tabKeys;
    private Runnable tabShow;
    private final Runnable tabSoon = () -> { if (tabShow != null) tabShow.run(); };

    /** Pill tabs; focusing one picks it after a short pause, like a TV app's tabs. */
    private void tabs(String[] labels, java.util.function.IntConsumer show) {
        tabBar = new LinearLayout(this);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setClipChildren(false);
        tabBar.setPaddingRelative(Math.round(INSET * k), Math.round(20 * k), Math.round(96 * k), Math.round(10 * k));
        for (int i = 0; i < labels.length; i++) {
            final int which = i;
            TextView t = TvUi.text(this, k, 28, Ui.medium, TvUi.DIM);
            t.setText(labels[i]);
            t.setPaddingRelative(Math.round(32 * k), Math.round(16 * k), Math.round(32 * k), Math.round(16 * k));
            t.setFocusable(true);
            t.setOnFocusChangeListener((v, has) -> {
                styleTabs();
                if (has && which != tab) {
                    tab = which;
                    tabShow = () -> show.accept(which);
                    main.removeCallbacks(tabSoon);
                    main.postDelayed(tabSoon, 260);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(Math.round(14 * k));
            tabBar.addView(t, lp);
        }
        TvUi.HScroll bar = new TvUi.HScroll(this, Math.round(260 * k));
        bar.addView(tabBar);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart(-Math.round(INSET * k));
        rows.addView(bar, lp);
        tabBody = new LinearLayout(this);
        tabBody.setOrientation(LinearLayout.VERTICAL);
        tabBody.setClipChildren(false);
        rows.addView(tabBody);
        tab = 0;
        styleTabs();
        tabBar.getChildAt(0).requestFocus();
    }

    private void styleTabs() {
        for (int i = 0; i < tabBar.getChildCount(); i++) {
            TextView t = (TextView) tabBar.getChildAt(i);
            boolean focused = t.hasFocus(), here = i == tab;
            t.setBackground(focused ? TvUi.round(TvUi.INK, 40 * k) : here ? TvUi.round(0x33FFFFFF, 40 * k) : null);
            t.setTextColor(focused ? 0xFF14161A : here ? TvUi.INK : TvUi.DIM);
            t.setTypeface(here || focused ? Ui.semibold : Ui.medium);
        }
    }

    private static final String[][] LIST_TABS = {{"list", "Minha lista", "My list"}, {"favorite", "Favoritos", "Favorites"},
            {"seen", "Já assisti", "Seen"}, {"rated", "Suas notas", "Your ratings"}};

    private void buildList() {
        status.setVisibility(View.GONE);
        String[] labels = new String[LIST_TABS.length];
        for (int i = 0; i < labels.length; i++) labels[i] = Ui.tr(LIST_TABS[i][1], LIST_TABS[i][2]);
        tabKeys = null;
        tabs(labels, which -> showTab(which, false));
        showTab(0, false);
    }

    /** A My list tab as a grid built as you scroll; `quiet`: only if it changed (back from a title page). */
    private void showTab(int which, boolean quiet) {
        int gen = generation;
        String kind = LIST_TABS[which][0];
        if (!quiet) {
            tabKeys = null;
            tabBody.removeAllViews();
            tabBody.addView(note(Ui.tr("Carregando…", "Loading…")));
        }
        io.execute(() -> {
            List<Tmdb.Title> titles = new ArrayList<>();
            String problem = null;
            try {
                switch (kind) {
                    case "list": titles = Tmdb.watchlist(this); break;
                    case "seen": titles = Tmdb.seen(this); break;
                    default:
                        if (Tmdb.signedIn(this)) titles = Tmdb.accountList(this, kind);
                        else problem = Ui.tr("Conecte sua conta TMDB em Ajustes para ver favoritos e notas.",
                                "Connect your TMDB account in Settings to see favorites and ratings.");
                }
            } catch (Exception e) {
                problem = Ui.tr("Não deu para carregar: ", "Couldn't load: ") + e.getMessage();
            }
            List<Tmdb.Title> got = titles;
            String why = problem;
            main.post(() -> {
                if (gen != generation || which != tab || isFinishing()) return;
                StringBuilder keys = new StringBuilder(kind);
                for (Tmdb.Title t : got) keys.append(',').append(t.key());
                if (quiet && keys.toString().equals(tabKeys)) return;
                tabKeys = keys.toString();
                boolean hadFocus = tabBody.hasFocus();
                tabBody.removeAllViews();
                if (why != null || got.isEmpty()) {
                    tabBody.addView(note(why != null ? why : Ui.tr("Nada aqui ainda. Abra um título para marcar.", "Nothing here yet. Open a title to add it.")));
                } else {
                    tabBody.addView(note(got.size() == 1 ? Ui.tr("1 título", "1 title") : got.size() + Ui.tr(" títulos", " titles")));
                    TvUi.Grid grid = new TvUi.Grid(this, k, 7, this::open);
                    grid.add(got);
                    tabBody.addView(grid);
                }
                if (hadFocus) tabBar.getChildAt(tab).requestFocus();
            });
        });
    }

    private TextView note(String text) {
        TextView t = TvUi.text(this, k, 26, Ui.regular, TvUi.DIM);
        t.setText(text);
        t.setPaddingRelative(0, Math.round(24 * k), 0, 0);
        return t;
    }

    /** A catalog tab: a TMDB list path, or a filter. */
    private static final class Tab {
        final String label, path, kind;
        final Filter filter;
        Tab(String label, String path, String kind) {
            this.label = label;
            this.path = path;
            this.kind = kind;
            this.filter = null;
        }
        Tab(String label, Filter filter) {
            this.label = label;
            this.path = null;
            this.kind = null;
            this.filter = filter;
        }
    }

    /** Movies, Shows, Kids and Anime, like the TV catalogs people know: tabs of kinds over a grid of posters. */
    private void buildCatalog(String id) {
        status.setVisibility(View.GONE);
        List<Tab> list = new ArrayList<>();
        switch (id) {
            case MOVIES:
                list.add(new Tab(Ui.tr("Em alta", "Trending"), "/trending/movie/week", "movie"));
                list.add(new Tab(Ui.tr("Lançamentos", "New"), sorted(Filter.of("movie"), "new")));
                list.add(new Tab(Ui.tr("Mais votados", "Top rated"), sorted(Filter.of("movie"), "votes")));
                for (String g : new String[] {"acao", "comedia", "drama", "terror", "suspense", "ficcao", "romance", "animacao", "documentarios", "nacionais"}) {
                    list.add(new Tab(genreName(g), Filter.of("movie", g)));
                }
                break;
            case SHOWS:
                list.add(new Tab(Ui.tr("Em alta", "Trending"), "/trending/tv/week", "tv"));
                list.add(new Tab(Ui.tr("No ar", "On the air"), "/tv/on_the_air", "tv"));
                list.add(new Tab(Ui.tr("Mais votadas", "Top rated"), sorted(Filter.of("tv"), "votes")));
                for (String g : new String[] {"drama", "comedia", "crime", "ficcao", "misterio", "reality", "doramas", "novela", "nacionais"}) {
                    list.add(new Tab(genreName(g), Filter.of("tv", g)));
                }
                break;
            case KIDS:
                list.add(new Tab(Ui.tr("Tudo", "All"), Filter.of("all", "infantil")));
                list.add(new Tab(Ui.tr("Desenhos", "Cartoons"), Filter.of("tv", "animacao", "infantil")));
                list.add(new Tab(Ui.tr("Filmes", "Movies"), Filter.of("movie", "infantil")));
                list.add(new Tab(Ui.tr("Animação", "Animation"), Filter.of("movie", "animacao", "familia")));
                list.add(new Tab(Ui.tr("Mais votados", "Top rated"), sorted(Filter.of("all", "infantil"), "votes")));
                break;
            default:   // ANIME
                list.add(new Tab(Ui.tr("Em alta", "Trending"), Filter.of("all", "anime")));
                list.add(new Tab(Ui.tr("Séries", "Shows"), Filter.of("tv", "anime")));
                list.add(new Tab(Ui.tr("Filmes", "Movies"), Filter.of("movie", "anime")));
                list.add(new Tab(Ui.tr("Lançamentos", "New"), sorted(Filter.of("all", "anime"), "new")));
                for (String g : new String[] {"acao", "comedia", "romance", "fantasia", "misterio"}) {
                    list.add(new Tab(genreName(g), Filter.of("all", "anime", g)));
                }
                list.add(new Tab(Ui.tr("Mais votados", "Top rated"), sorted(Filter.of("all", "anime"), "votes")));
        }
        String[] labels = new String[list.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = list.get(i).label;
        tabs(labels, which -> showCatalogTab(list.get(which)));
        showCatalogTab(list.get(0));
    }

    private static Filter sorted(Filter f, String sort) {
        f.sort = sort;
        return f;
    }

    private void showCatalogTab(Tab t) {
        tabBody.removeAllViews();
        TvUi.Grid grid = new TvUi.Grid(this, k, 7, this::open);
        tabBody.addView(grid);
        Pager pager = t.path != null ? p -> Tmdb.titles(Tmdb.get(t.path + (t.path.contains("?") ? "&" : "?") + "page=" + p), t.kind)
                : p -> fetch(t.filter, p);
        startFeed(grid, pager, null);
    }

    // MARK: - Paged grids

    private interface Pager { List<Tmdb.Title> page(int p) throws Exception; }

    private Feed feed;

    /** A grid that asks for its next page as the focus nears its end (TvUi.Grid.needMore). */
    private final class Feed {
        final TvUi.Grid grid;
        final Pager pager;
        final TextView count;
        final Set<String> keys = new HashSet<>();
        int page, gen = generation;
        boolean loading, done;

        Feed(TvUi.Grid grid, Pager pager, TextView count) {
            this.grid = grid;
            this.pager = pager;
            this.count = count;
            grid.needMore = this::more;
        }

        void more() {
            if (loading || done || page >= 25 || this != feed) return;
            loading = true;
            int want = page + 1;
            io.execute(() -> {
                List<Tmdb.Title> got;
                try {
                    got = pager.page(want);
                } catch (Exception e) {
                    AppLog.w("explore feed: " + e.getMessage());
                    got = null;
                }
                List<Tmdb.Title> list = got;
                main.post(() -> {
                    if (this != feed || isFinishing()) return;
                    loading = false;
                    page = want;
                    if (list == null) {
                        done = true;
                        return;
                    }
                    List<Tmdb.Title> fresh = new ArrayList<>();
                    for (Tmdb.Title t : list) if (keys.add(t.key())) fresh.add(t);
                    boolean first = grid.count == 0;
                    grid.add(fresh);
                    if (list.isEmpty()) done = true;
                    if (count != null) {
                        count.setText(grid.count == 0 ? Ui.tr("Nada encontrado com esse filtro.", "Nothing matches this filter.") : "");
                        count.setVisibility(grid.count == 0 ? View.VISIBLE : View.GONE);
                    }
                    if (grid.count < 21 && !done) more();   // a filtered page can come back thin: fill the first screen
                    if (first && grid.count > 0 && grid.hasFocus()) grid.getChildAt(0).requestFocus();
                });
            });
        }
    }

    private void startFeed(TvUi.Grid grid, Pager pager, TextView count) {
        feed = new Feed(grid, pager, count);
        feed.more();
    }

    // MARK: - Search and categories: the same filter

    private Filter filter;
    private String savedId;
    private LinearLayout chips;
    private TvUi.Grid results;
    private TextView resultsNote;
    private final Runnable searchSoon = this::runFilter;

    /** `search`: words plus the filter; otherwise only the filter, which can be saved to the menu. */
    private void buildFilterPage(boolean search, String saved) {
        status.setVisibility(View.GONE);
        savedId = saved;
        filter = saved != null ? Filter.fromJson(saved().optJSONObject(saved) == null ? "{}" : saved().optJSONObject(saved).optString("filter"))
                : new Filter();
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setClipChildren(false);
        bar.setPaddingRelative(0, Math.round(16 * k), 0, 0);
        View first;
        if (search) {
            EditText field = new EditText(this);
            field.setSingleLine(true);
            field.setTextSize(TypedValue.COMPLEX_UNIT_PX, 34 * k);
            field.setTextColor(TvUi.INK);
            field.setHintTextColor(TvUi.FAINT);
            field.setTypeface(Ui.medium);
            field.setHint(Ui.tr("Filme, série ou pessoa", "Movie, show or person"));
            field.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
            field.setPaddingRelative(Math.round(36 * k), Math.round(22 * k), Math.round(36 * k), Math.round(22 * k));
            field.setBackground(TvUi.round(0x1FFFFFFF, 40 * k));
            field.setOnFocusChangeListener((v, has) -> v.setBackground(TvUi.round(has ? 0x47FFFFFF : 0x1FFFFFFF, 40 * k)));
            field.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void afterTextChanged(Editable s) {
                    filter.query = s.toString().trim();
                    main.removeCallbacks(searchSoon);
                    main.postDelayed(searchSoon, 600);
                }
            });
            field.setOnEditorActionListener((v, action, event) -> {
                ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(v.getWindowToken(), 0);
                main.removeCallbacks(searchSoon);
                remember(filter.query);
                runFilter();
                return true;
            });
            bar.addView(field, new LinearLayout.LayoutParams(Math.round(1000 * k), ViewGroup.LayoutParams.WRAP_CONTENT));
            first = field;
        } else {
            first = null;
        }
        LinearLayout filterButton = TvUi.iconButton(this, k, "tune", Ui.tr("Filtros", "Filters"), v -> openFilter());
        LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fl.setMarginStart(search ? Math.round(20 * k) : 0);
        bar.addView(filterButton, fl);
        if (!search) {
            TextView save = TvUi.button(this, k, saved != null ? Ui.tr("Salvar alterações", "Save changes") : Ui.tr("Salvar no menu", "Save to menu"),
                    v -> askSave());
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            sl.setMarginStart(Math.round(20 * k));
            bar.addView(save, sl);
        }
        rows.addView(bar);
        chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.VERTICAL);
        chips.setClipChildren(false);
        rows.addView(chips);
        if (search && !recent().isEmpty()) {
            TvUi.Flow recent = new TvUi.Flow(this, Math.round(14 * k), Math.round(14 * k));
            recent.setPaddingRelative(Math.round(6 * k), Math.round(22 * k), Math.round(INSET * k), 0);
            TextView label = TvUi.text(this, k, 24, Ui.medium, TvUi.FAINT);
            label.setText(Ui.tr("Recentes", "Recent"));
            label.setPaddingRelative(0, Math.round(14 * k), Math.round(6 * k), 0);
            recent.addView(label);
            EditText field = (EditText) first;
            for (String q : recent()) {
                TextView b = TvUi.button(this, k, q, v -> field.setText(q));
                b.setTextSize(TypedValue.COMPLEX_UNIT_PX, 24 * k);
                recent.addView(b);
            }
            rows.addView(recent, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        resultsNote = note("");
        resultsNote.setVisibility(View.GONE);
        rows.addView(resultsNote);
        showChips();
        runFilter();
        (first != null ? first : filterButton).requestFocus();
    }

    /** The filter's parts as chips under the bar; OK on one takes it off. */
    private void showChips() {
        chips.removeAllViews();
        if (filter.empty()) return;
        TvUi.Flow flow = new TvUi.Flow(this, Math.round(14 * k), Math.round(14 * k));
        flow.setPaddingRelative(Math.round(6 * k), Math.round(22 * k), Math.round(INSET * k), 0);
        if (!filter.kind.equals("all")) {
            flow.addView(TvUi.toggle(this, k, filter.kind.equals("movie") ? Ui.tr("Filmes", "Movies") : Ui.tr("Séries", "Shows"), true, v -> {
                filter.kind = "all";
                filterChanged();
            }));
        }
        for (String g : new ArrayList<>(filter.genres)) {
            flow.addView(TvUi.toggle(this, k, genreName(g), true, v -> {
                filter.genres.remove(g);
                filterChanged();
            }));
        }
        if (!filter.year.isEmpty()) {
            flow.addView(TvUi.toggle(this, k, yearLabel(filter.year), true, v -> {
                filter.year = "";
                filterChanged();
            }));
        }
        chips.addView(flow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void filterChanged() {
        boolean focusInChips = chips.hasFocus();
        showChips();
        runFilter();
        if (focusInChips) focusPage();
    }

    /** New results: words go to TMDB's search (then the filter is applied here); without words, discover. */
    private void runFilter() {
        if (results != null) rows.removeView(results);
        results = new TvUi.Grid(this, k, 7, this::open);
        rows.addView(results);
        Filter f = filter.copy();
        if (f.query.isEmpty() && f.empty() && current.equals(SEARCH)) {
            resultsNote.setText(Ui.tr("Em alta na semana", "Trending this week"));
            resultsNote.setVisibility(View.VISIBLE);
            startFeed(results, p -> Tmdb.titles(Tmdb.get("/trending/all/week?page=" + p), "movie"), null);
            return;
        }
        resultsNote.setVisibility(View.GONE);
        startFeed(results, p -> fetch(f, p), resultsNote);
    }

    /** One page of a filter's titles: shows and movies mixed, or TMDB's search narrowed to the filter. */
    private List<Tmdb.Title> fetch(Filter f, int page) throws Exception {
        if (!f.query.isEmpty()) {   // three of TMDB's pages per page here: the filter may leave few of each
            List<Tmdb.Title> out = new ArrayList<>();
            String path = "/search/multi?include_adult=false&query=" + URLEncoder.encode(f.query, "UTF-8") + "&page=";
            for (int p = (page - 1) * 3 + 1; p <= page * 3; p++) {
                JSONObject o = Tmdb.get(path + p);
                for (Tmdb.Title t : Tmdb.titles(o, "movie")) if (matches(f, t)) out.add(t);
                if (p >= o.optInt("total_pages", 1)) break;
            }
            return out;
        }
        List<Tmdb.Title> tv = new ArrayList<>(), movies = new ArrayList<>();
        String tvParams = f.kind.equals("movie") ? null : params(f, "tv");
        String movieParams = f.kind.equals("tv") ? null : params(f, "movie");
        if (tvParams != null) tv = Tmdb.titles(Tmdb.get(discoverPath("tv", tvParams + "&page=" + page)), "tv");
        if (movieParams != null) movies = Tmdb.titles(Tmdb.get(discoverPath("movie", movieParams + "&page=" + page)), "movie");
        return mix(tv, movies);
    }

    /** Discover parameters for one type, or null when a chosen category has nothing of that type. */
    private String params(Filter f, String kind) {
        boolean movie = kind.equals("movie");
        List<String> ids = new ArrayList<>(), keywords = new ArrayList<>(), origins = new ArrayList<>();
        for (String key : f.genres) {
            String[] g = genre(key);
            if (g == null) continue;
            String id = movie ? g[3] : g[4];
            if (id == null && g[5] == null && g[6] == null) return null;
            if (id != null && !ids.contains(id)) ids.add(id);
            if (g[5] != null) keywords.add(g[5]);
            if (g[6] != null) origins.add(g[6]);
        }
        StringBuilder p = new StringBuilder();
        if (!ids.isEmpty()) p.append("&with_genres=").append(TextUtils.join(",", ids));
        if (!keywords.isEmpty()) p.append("&with_keywords=").append(TextUtils.join("|", keywords));
        if (!origins.isEmpty()) p.append("&with_origin_country=").append(TextUtils.join("|", origins));
        String[] range = yearRange(f.year);
        String field = movie ? "primary_release_date" : "first_air_date";
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date());
        if (range != null) p.append("&").append(field).append(".gte=").append(range[0]).append("&").append(field).append(".lte=").append(range[1]);
        switch (f.sort) {
            case "new":
                p.append("&sort_by=").append(field).append(".desc&").append(field).append(".lte=").append(today).append("&vote_count.gte=10");
                break;
            case "votes":
                p.append("&sort_by=vote_average.desc&vote_count.gte=").append(movie ? 500 : 200);
                break;
            default:
                p.append("&sort_by=popularity.desc&vote_count.gte=").append(range != null ? 5 : 20);
        }
        if (f.services) p.append(servicesParam());
        return p.toString();
    }

    /** The filter applied to a search result (TMDB's search takes no filters). */
    private static boolean matches(Filter f, Tmdb.Title t) {
        if (!f.kind.equals("all") && !f.kind.equals(t.kind)) return false;
        if (t.kind.equals("person")) return f.empty();
        String[] range = yearRange(f.year);
        if (range != null && (t.date.length() < 10 || t.date.compareTo(range[0]) < 0 || t.date.compareTo(range[1]) > 0)) return false;
        for (String key : f.genres) {
            String[] g = genre(key);
            if (g == null) continue;
            String id = t.kind.equals("movie") ? g[3] : g[4];
            if (id != null) {
                boolean has = false;
                for (int x : t.genres) has |= String.valueOf(x).equals(id);
                if (!has) return false;
            }
            if (g[7] != null && !g[7].equals(t.lang)) return false;
            if (id == null && g[7] == null) return false;
        }
        return true;
    }

    private static String[] genre(String key) {
        for (String[] g : GENRES) if (g[0].equals(key)) return g;
        return null;
    }

    private static String genreName(String key) {
        String[] g = genre(key);
        return g == null ? key : Ui.tr(g[1], g[2]);
    }

    /** "" (any), a year, a decade ("2010s") or "old" (before 1990). */
    private static List<String> years() {
        List<String> out = new ArrayList<>();
        int now = Calendar.getInstance().get(Calendar.YEAR);
        for (int y = now; y > now - 7; y--) out.add(String.valueOf(y));
        out.add("2010s");
        out.add("2000s");
        out.add("1990s");
        out.add("old");
        return out;
    }

    private static String yearLabel(String y) {
        if (y.equals("old")) return Ui.tr("Antes de 1990", "Before 1990");
        if (y.endsWith("s") && Ui.tur) return Turkish.decade(y.substring(0, 4));
        if (y.endsWith("s")) return Ui.tr("Anos ", "The ") + y.substring(0, 4) + (Ui.tr("", "s"));
        return y;
    }

    private static String[] yearRange(String y) {
        if (y == null || y.isEmpty()) return null;
        if (y.equals("old")) return new String[] {"1900-01-01", "1989-12-31"};
        int from = Integer.parseInt(y.substring(0, 4));
        int to = y.endsWith("s") ? from + 9 : from;
        return new String[] {from + "-01-01", to + "-12-31"};
    }

    /** The filter panel, over the page on the right: type, categories (several), release; Apply or Clear. */
    private void openFilter() {
        if (overlay != null) return;
        beforeOverlay = getCurrentFocus();
        Filter draft = filter.copy();
        FrameLayout layer = new FrameLayout(this);
        View dim = new View(this);
        dim.setBackgroundColor(0x99000000);
        layer.addView(dim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        TvUi.Scroll panelScroll = new TvUi.Scroll(this);
        panelScroll.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[] {0xF512151C, 0xFC12151C}));
        panelScroll.setElevation(24 * k);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setClipChildren(false);
        panel.setPaddingRelative(Math.round(56 * k), Math.round(56 * k), Math.round(56 * k), Math.round(56 * k));
        TextView h = TvUi.text(this, k, 48, Ui.displaySemibold, TvUi.INK);
        h.setText(Ui.tr("Filtros", "Filters"));
        panel.addView(h);
        panel.addView(panelLabel(Ui.tr("Tipo", "Type")));
        TvUi.Flow kinds = panelFlow();
        String[][] kindOptions = {{"all", Ui.tr("Tudo", "All")}, {"movie", Ui.tr("Filmes", "Movies")}, {"tv", Ui.tr("Séries", "Shows")}};
        List<TextView> kindChips = new ArrayList<>();
        for (String[] o : kindOptions) {
            TextView c = TvUi.toggle(this, k, o[1], draft.kind.equals(o[0]), v -> {
                draft.kind = o[0];
                for (TextView other : kindChips) if (other != v && Boolean.TRUE.equals(other.getTag())) other.performClick();
                v.setTag(true);
            });
            kindChips.add(c);
            kinds.addView(c);
        }
        panel.addView(kinds);
        panel.addView(panelLabel(Ui.tr("Categorias (uma ou mais)", "Categories (one or more)")));
        TvUi.Flow cats = panelFlow();
        for (String[] g : GENRES) {
            cats.addView(TvUi.toggle(this, k, Ui.tr(g[1], g[2]), draft.genres.contains(g[0]), v -> {
                if (Boolean.TRUE.equals(v.getTag())) draft.genres.add(g[0]); else draft.genres.remove(g[0]);
            }));
        }
        panel.addView(cats);
        panel.addView(panelLabel(Ui.tr("Lançamento", "Release")));
        TvUi.Flow yrs = panelFlow();
        List<TextView> yearChips = new ArrayList<>();
        List<String> options = new ArrayList<>();
        options.add("");
        options.addAll(years());
        for (String y : options) {
            TextView c = TvUi.toggle(this, k, y.isEmpty() ? Ui.tr("Qualquer ano", "Any year") : yearLabel(y), draft.year.equals(y), v -> {
                draft.year = y;
                for (TextView other : yearChips) if (other != v && Boolean.TRUE.equals(other.getTag())) other.performClick();
                v.setTag(true);
            });
            yearChips.add(c);
            yrs.addView(c);
        }
        panel.addView(yrs);
        TvUi.Flow buttons = panelFlow();
        buttons.setPaddingRelative(0, Math.round(40 * k), 0, 0);
        TextView apply = TvUi.button(this, k, Ui.tr("Aplicar", "Apply"), v -> {
            String query = filter.query;
            filter = draft;
            filter.query = query;
            closeOverlay();
            filterChanged();
        });
        buttons.addView(apply);
        buttons.addView(TvUi.button(this, k, Ui.tr("Limpar", "Clear"), v -> {
            String query = filter.query;
            filter = new Filter();
            filter.query = query;
            closeOverlay();
            filterChanged();
        }));
        panel.addView(buttons);
        panelScroll.addView(panel);
        FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(Math.round(1040 * k), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END);
        layer.addView(panelScroll, pl);
        overlay = layer;
        root.addView(layer, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        scroll.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        layer.setAlpha(0);
        layer.animate().alpha(1).setDuration(180).start();
        kinds.getChildAt(0).requestFocus();
    }

    private TextView panelLabel(String text) {
        TextView t = TvUi.text(this, k, 24, Ui.semibold, TvUi.FAINT);
        t.setText(text.toUpperCase(Locale.ROOT));
        t.setLetterSpacing(0.1f);
        t.setPaddingRelative(0, Math.round(34 * k), 0, Math.round(16 * k));
        return t;
    }

    private TvUi.Flow panelFlow() {
        TvUi.Flow f = new TvUi.Flow(this, Math.round(14 * k), Math.round(14 * k));
        f.setPaddingRelative(Math.round(4 * k), 0, 0, 0);
        f.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return f;
    }

    private void closeOverlay() {
        if (overlay == null) return;
        View layer = overlay;
        overlay = null;
        root.removeView(layer);
        scroll.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        if (beforeOverlay != null && beforeOverlay.isAttachedToWindow()) beforeOverlay.requestFocus();
    }

    /** Categories: saves the filter as a menu item (a name the person can change). */
    private void askSave() {
        if (filter.empty()) {
            Toast.makeText(this, Ui.tr("Escolha algo no filtro antes de salvar.", "Pick something in the filter first."), Toast.LENGTH_SHORT).show();
            return;
        }
        EditText name = new EditText(this);
        name.setSingleLine(true);
        name.setText(savedId != null ? label(savedId) : filter.label());
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(Ui.tr("Nome no menu", "Name in the menu"))
                .setView(name)
                .setPositiveButton(Ui.tr("Salvar", "Save"), (d, w) -> {
                    String n = name.getText().toString().trim();
                    String id = savedId != null ? savedId : SAVED + System.currentTimeMillis();
                    saveFilter(id, n.isEmpty() ? filter.label() : n, filter);
                    fillMenu();
                    Toast.makeText(this, Ui.tr("Salvo no menu", "Saved to the menu"), Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(Ui.tr("Cancelar", "Cancel"), null)
                .show();
    }

    // MARK: - Settings: account, services and the menu's order

    private void buildSettings() {
        status.setVisibility(View.GONE);
        TvUi.Flow buttons = new TvUi.Flow(this, Math.round(18 * k), Math.round(18 * k));
        buttons.setPaddingRelative(Math.round(6 * k), Math.round(30 * k), Math.round(INSET * k), 0);
        String account = Tmdb.signedIn(this) ? Ui.tr("Conta TMDB: ", "TMDB account: ") + Tmdb.accountName(this) : Ui.tr("Conectar conta TMDB", "Connect TMDB account");
        buttons.addView(TvUi.button(this, k, account, v -> startActivity(new Intent(this, TmdbLoginActivity.class))));
        buttons.addView(TvUi.button(this, k, Ui.tr("Serviços e apps", "Services and apps"), v -> startActivity(new Intent(this, ServicesActivity.class))));
        rows.addView(buttons, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView h = TvUi.text(this, k, 32, Ui.semibold, TvUi.INK);
        h.setText(Ui.tr("Menu lateral", "Side menu"));
        h.setPaddingRelative(0, Math.round(50 * k), 0, Math.round(8 * k));
        rows.addView(h);
        rows.addView(note(Ui.tr("Buscar, Início e Minha lista ficam sempre no topo. OK num item: mover, ocultar ou remover.",
                "Search, Home and My list always stay on top. OK on an item: move, hide or remove.")));
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPaddingRelative(0, Math.round(20 * k), 0, 0);
        rows.addView(list);
        fillOrganizer(list, null);
        buttons.getChildAt(0).requestFocus();
    }

    private void fillOrganizer(LinearLayout list, String focusId) {
        list.removeAllViews();
        Set<String> hid = hidden();
        List<String> ids = order();
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i);
            int at = i;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPaddingRelative(Math.round(28 * k), Math.round(18 * k), Math.round(28 * k), Math.round(18 * k));
            TextView num = TvUi.text(this, k, 24, Ui.medium, TvUi.FAINT);
            num.setText(String.valueOf(i + 1));
            num.setMinWidth(Math.round(54 * k));
            row.addView(num);
            TextView name = TvUi.text(this, k, 28, Ui.medium, hid.contains(id) ? TvUi.FAINT : TvUi.INK);
            name.setText(label(id));
            row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            TextView state = TvUi.text(this, k, 24, Ui.regular, TvUi.DIM);
            state.setText(hid.contains(id) ? Ui.tr("Oculto", "Hidden") : id.startsWith(SAVED) ? Ui.tr("Filtro salvo", "Saved filter")
                    : id.startsWith(SERVICE) ? Ui.tr("Serviço", "Service") : "");
            row.addView(state);
            TvUi.softFocus(row, k, 18 * k);
            row.setOnClickListener(v -> {
                List<String> labels = new ArrayList<>();
                List<Runnable> actions = new ArrayList<>();
                if (at > 0) {
                    labels.add(Ui.tr("Mover para cima", "Move up"));
                    actions.add(() -> move(id, -1));
                }
                if (at < ids.size() - 1) {
                    labels.add(Ui.tr("Mover para baixo", "Move down"));
                    actions.add(() -> move(id, 1));
                }
                boolean isHidden = hidden().contains(id);
                labels.add(isHidden ? Ui.tr("Mostrar no menu", "Show in the menu") : Ui.tr("Ocultar do menu", "Hide from the menu"));
                actions.add(() -> setHidden(id, !isHidden));
                if (id.startsWith(SAVED)) {
                    labels.add(Ui.tr("Remover", "Remove"));
                    actions.add(() -> removeSaved(id));
                }
                labels.add(Ui.tr("Cancelar", "Cancel"));
                actions.add(null);
                int[] xy = new int[2];
                v.getLocationInWindow(xy);
                Rect anchor = new Rect(xy[0] + v.getWidth() - Math.round(220 * k), xy[1], xy[0] + v.getWidth(), xy[1] + v.getHeight());
                TvUi.popover(this, v, anchor, label(id), labels.toArray(new String[0]), which -> {
                    Runnable r = actions.get(which);
                    if (r == null) return;
                    r.run();
                    fillMenu();
                    fillOrganizer(list, id);
                });
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Math.round(1100 * k), ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = Math.round(10 * k);
            list.addView(row, lp);
            if (id.equals(focusId)) row.post(row::requestFocus);
        }
    }

    // MARK: - Banner

    private FrameLayout hero;
    private ImageView heroImage, heroLogo;
    private TextView heroTag, heroTitle, heroMeta, heroOverview, heroButton;
    private LinearLayout heroDots;
    private final List<Object[]> heroList = new ArrayList<>();   // {title, tag}
    private int heroAt;
    private final Runnable heroTick = () -> {
        if (hero == null || menuOpen || heroList.size() < 2) return;
        stepHero(1);
    };

    private void restartHero() {
        main.removeCallbacks(heroTick);
        main.postDelayed(heroTick, HERO_MS);
    }

    private void stepHero(int by) {
        heroAt = (heroAt + by + heroList.size()) % heroList.size();
        showHero();
        restartHero();
    }

    private View buildHero() {
        heroList.clear();
        hero = new FrameLayout(this);
        int bleed = Math.round((HERO_H + HERO_BLEED) * k);   // the picture runs on behind the first row
        FadeOut picture = new FadeOut(this, 0.42f);
        heroImage = new ImageView(this);
        heroImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
        picture.addView(heroImage, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, bleed));
        View left = new View(this);
        left.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[] {0xF00B0D12, 0x990B0D12, 0x000B0D12}));
        picture.addView(left, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, bleed));
        hero.addView(picture, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, bleed));
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        heroTag = TvUi.text(this, k, 24, Ui.semibold, TvUi.ACCENT);
        heroTag.setLetterSpacing(0.08f);
        text.addView(heroTag);
        heroLogo = new ImageView(this);
        heroLogo.setAdjustViewBounds(true);
        heroLogo.setScaleType(ImageView.ScaleType.FIT_START);
        heroLogo.setMaxWidth(Math.round(620 * k));
        heroLogo.setMaxHeight(Math.round(190 * k));
        heroLogo.setVisibility(View.GONE);
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        logoLp.topMargin = Math.round(18 * k);
        text.addView(heroLogo, logoLp);
        heroTitle = TvUi.text(this, k, 76, Ui.displaySemibold, TvUi.INK);
        heroTitle.setMaxLines(2);
        heroTitle.setEllipsize(TextUtils.TruncateAt.END);
        heroTitle.setPaddingRelative(0, Math.round(14 * k), 0, 0);
        text.addView(heroTitle);
        heroMeta = TvUi.text(this, k, 26, Ui.medium, TvUi.DIM);
        heroMeta.setPaddingRelative(0, Math.round(18 * k), 0, 0);
        text.addView(heroMeta);
        heroOverview = TvUi.text(this, k, 26, Ui.regular, TvUi.DIM);
        heroOverview.setMaxLines(3);
        heroOverview.setEllipsize(TextUtils.TruncateAt.END);
        heroOverview.setLineSpacing(6 * k, 1);
        heroOverview.setPaddingRelative(0, Math.round(14 * k), 0, 0);
        text.addView(heroOverview);
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPaddingRelative(0, Math.round(28 * k), 0, 0);
        heroButton = TvUi.text(this, k, 27, Ui.medium, TvUi.INK);
        heroButton.setText(Ui.tr("Ver detalhes", "Details"));
        heroButton.setPaddingRelative(Math.round(34 * k), Math.round(16 * k), Math.round(34 * k), Math.round(16 * k));
        heroButton.setBackground(TvUi.round(0x33FFFFFF, 40 * k));
        bar.addView(heroButton);
        heroDots = new LinearLayout(this);
        heroDots.setOrientation(LinearLayout.HORIZONTAL);
        heroDots.setPaddingRelative(Math.round(30 * k), 0, 0, 0);
        bar.addView(heroDots);
        text.addView(bar);
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(Math.round(900 * k), ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.START);
        tl.setMarginStart(Math.round(INSET * k));
        tl.bottomMargin = Math.round((40 + HERO_BLEED) * k);
        hero.addView(text, tl);
        hero.setFocusable(true);
        hero.setFocusableInTouchMode(true);
        hero.setOnFocusChangeListener((v, has) -> {
            heroButton.setBackground(TvUi.round(has ? TvUi.INK : 0x33FFFFFF, 40 * k));
            heroButton.setTextColor(has ? 0xFF14161A : TvUi.INK);
        });
        hero.setOnClickListener(v -> {
            if (!heroList.isEmpty()) open((Tmdb.Title) heroList.get(heroAt)[0]);
        });
        hero.setOnKeyListener((v, code, e) -> {
            // Next banner: → (← in Hebrew, which reads the other way); back one: the opposite key.
            int nextKey = Ui.he ? KeyEvent.KEYCODE_DPAD_LEFT : KeyEvent.KEYCODE_DPAD_RIGHT;
            int backKey = Ui.he ? KeyEvent.KEYCODE_DPAD_RIGHT : KeyEvent.KEYCODE_DPAD_LEFT;
            boolean right = code == nextKey, back = code == backKey && heroAt > 0;
            if ((!right && !back) || heroList.size() < 2) return false;
            if (e.getAction() == KeyEvent.ACTION_DOWN) stepHero(right ? 1 : -1);
            return true;
        });
        hero.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, bleed));
        return hero;
    }

    /** Its lower part fades to transparent (a mask, not a dark band), so the banner melts into the background. */
    private static final class FadeOut extends FrameLayout {
        private final float from;
        private final android.graphics.Paint mask = new android.graphics.Paint();
        private int maskH;

        FadeOut(Context c, float from) {
            super(c);
            this.from = from;
            mask.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN));
            setWillNotDraw(false);
        }

        @Override protected void dispatchDraw(android.graphics.Canvas c) {
            int w = getWidth(), h = getHeight();
            if (h != maskH) {
                maskH = h;
                mask.setShader(new android.graphics.LinearGradient(0, h * from, 0, h, 0xFF000000, 0x00000000, android.graphics.Shader.TileMode.CLAMP));
            }
            int layer = c.saveLayer(0, 0, w, h, null);
            super.dispatchDraw(c);
            c.drawRect(0, h * from, w, h, mask);
            c.restoreToCount(layer);
        }
    }

    private void setHero(List<Object[]> list) {
        if (list.isEmpty()) {   // nothing with a backdrop: a plain title instead
            page.removeView(hero);
            hero = null;
            ((LinearLayout.LayoutParams) rows.getLayoutParams()).topMargin = 0;
            rows.requestLayout();
            header(label(current), subtitle(current));
            return;
        }
        heroList.clear();
        heroList.addAll(list);
        heroAt = 0;
        heroDots.removeAllViews();
        for (int i = 0; i < list.size(); i++) {
            View dot = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Math.round(12 * k), Math.round(12 * k));
            lp.setMarginEnd(Math.round(10 * k));
            heroDots.addView(dot, lp);
        }
        showHero();
        if (!menuOpen && overlay == null && keyAt < pageAt) hero.requestFocus();
        restartHero();
    }

    private void showHero() {
        Tmdb.Title t = (Tmdb.Title) heroList.get(heroAt)[0];
        heroTag.setText(((String) heroList.get(heroAt)[1]).toUpperCase(Locale.ROOT));
        heroImage.setAlpha(0.35f);
        Images.load(heroImage, Tmdb.BACKDROP + t.backdrop);
        heroImage.animate().alpha(1).setDuration(500).start();
        heroTitle.setText(t.name);
        heroTitle.setVisibility(View.VISIBLE);
        heroLogo.setVisibility(View.GONE);
        List<String> meta = new ArrayList<>();
        meta.add(t.kind.equals("movie") ? Ui.tr("Filme", "Movie") : Ui.tr("Série", "Show"));
        if (!t.year.isEmpty()) meta.add(t.year);
        if (t.vote > 0) meta.add(String.format(Ui.PT, "★ %.1f", t.vote));
        if (Tmdb.isSeen(this, t.kind, t.id)) meta.add("✓ " + Ui.tr("Assistido", "Seen"));
        heroMeta.setText(TextUtils.join("  ·  ", meta));
        heroOverview.setText(t.overview);
        for (int i = 0; i < heroDots.getChildCount(); i++) {
            heroDots.getChildAt(i).setBackground(TvUi.round(i == heroAt ? TvUi.INK : 0x59FFFFFF, 6 * k));
        }
        if (hero.hasFocus()) followFocus(hero);
        int at = heroAt;
        FrameLayout shown = hero;
        io.execute(() -> {
            String logo = logo(t);
            if (logo == null) return;
            main.post(() -> {
                if (hero != shown || heroAt != at) return;
                Images.load(heroLogo, logo);
                heroLogo.setVisibility(View.VISIBLE);
                heroTitle.setVisibility(View.GONE);
            });
        });
    }

    /** The title's logo (a transparent PNG) in the app's language, else English, else without text; null if none. */
    private static String logo(Tmdb.Title t) {
        try {
            String mine = Tmdb.lang().substring(0, 2);
            JSONArray logos = Tmdb.get("/" + t.kind + "/" + t.id + "/images?include_image_language=" + mine + ",en,null")
                    .optJSONArray("logos");
            String best = null;
            int bestRank = 9;
            for (int i = 0; logos != null && i < logos.length(); i++) {
                JSONObject l = logos.optJSONObject(i);
                String path = l.optString("file_path");
                if (!path.endsWith(".png")) continue;
                String lang = l.optString("iso_639_1");
                int rank = lang.equals(mine) ? 0 : lang.equals("en") ? 1 : 2;
                if (rank < bestRank) {
                    bestRank = rank;
                    best = path;
                }
            }
            return best == null ? null : "https://image.tmdb.org/t/p/w500" + best;
        } catch (Exception e) {
            return null;
        }
    }

    /** The banner: picks for the person and what's big today (home), the top 10, or a service's top titles. */
    private List<Object[]> heroItems(String id) {
        List<Object[]> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try {
            if (id.equals(HOME)) {
                String picked = Ui.tr("Recomendado para você", "Picked for you");
                List<Tmdb.Title> mine = forYou();
                for (int i = 0; i < mine.size() && out.size() < 2; i++) addHero(out, seen, mine.get(i), picked);
                for (Tmdb.Title t : Tmdb.titles(Tmdb.get("/trending/all/day"), "movie")) {
                    if (out.size() >= 7) break;
                    addHero(out, seen, t, Ui.tr("Em alta hoje", "Trending today"));
                }
            } else if (id.equals(TOP)) {
                List<Tmdb.Title> tv = top(Tmdb.titles(Tmdb.get("/trending/tv/day"), "tv"));
                List<Tmdb.Title> movies = top(Tmdb.titles(Tmdb.get("/trending/movie/day"), "movie"));
                for (int i = 0; i < 4; i++) {
                    if (i < tv.size()) addHero(out, seen, tv.get(i), Ui.tr("Nº " + (i + 1) + " em séries hoje", "No. " + (i + 1) + " in shows today"));
                    if (i < movies.size()) addHero(out, seen, movies.get(i), Ui.tr("Nº " + (i + 1) + " em filmes hoje", "No. " + (i + 1) + " in movies today"));
                }
            } else {
                Service s = service(id);
                if (s != null) {
                    for (Tmdb.Title t : mix(discover("tv", "&with_watch_providers=" + s.ids + "&sort_by=popularity.desc"),
                            discover("movie", "&with_watch_providers=" + s.ids + "&sort_by=popularity.desc"))) {
                        if (out.size() >= 6) break;
                        addHero(out, seen, t, Ui.tr("Em alta na ", "Trending on ") + s.name);
                    }
                }
            }
        } catch (Exception e) {
            AppLog.w("explore banner: " + e.getMessage());
        }
        return out;
    }

    private static void addHero(List<Object[]> out, Set<String> seen, Tmdb.Title t, String tag) {
        if (t.backdrop.isEmpty() || "null".equals(t.backdrop) || !seen.add(t.key())) return;
        out.add(new Object[] {t, tag});
    }

    // MARK: - What the banner pages show

    private List<Section> sections(String id) {
        List<Section> out = new ArrayList<>();
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date());
        switch (id) {
            case TOP:
                out.add(new Section(Ui.tr("Top 10 séries hoje", "Top 10 shows today"), () -> top(Tmdb.titles(Tmdb.get("/trending/tv/day"), "tv"))).ranked());
                out.add(new Section(Ui.tr("Top 10 filmes hoje", "Top 10 movies today"), () -> top(Tmdb.titles(Tmdb.get("/trending/movie/day"), "movie"))).ranked());
                return out;
            case HOME: {
                out.add(new Section(Ui.tr("Minha lista", "My list"), () -> Tmdb.watchlist(this)).personal().more(() -> showPage(LIST)));
                out.add(new Section(Ui.tr("Recomendado para você", "Recommended for you"), this::forYou).personal());
                List<Tmdb.Title> seen = Tmdb.seen(this);
                Tmdb.Title last = seen.isEmpty() ? null : seen.get(0);
                if (last != null) {
                    out.add(new Section(Ui.tr("Porque você viu ", "Because you watched ") + last.name,
                            () -> fresh(Tmdb.titles(Tmdb.get("/" + last.kind + "/" + last.id + "/recommendations"), last.kind)))
                            .personal().more("/" + last.kind + "/" + last.id + "/recommendations", last.kind));
                }
                out.add(new Section(Ui.tr("Em alta hoje", "Trending today"), () -> Tmdb.titles(Tmdb.get("/trending/all/day"), "movie"))
                        .more("/trending/all/day", "movie"));
                out.add(new Section(Ui.tr("Séries em alta na semana", "Trending shows this week"), () -> mine("tv", Tmdb.titles(Tmdb.get("/trending/tv/week"), "tv")))
                        .more(() -> showPage(SHOWS)));
                out.add(new Section(Ui.tr("Filmes em alta na semana", "Trending movies this week"), () -> mine("movie", Tmdb.titles(Tmdb.get("/trending/movie/week"), "movie")))
                        .more(() -> showPage(MOVIES)));
                int n = 0;
                for (Service s : services()) {
                    if (n++ >= 6) break;
                    out.add(new Section(Ui.tr("Em alta na ", "Trending on ") + s.name, () -> mix(
                            discover("tv", "&with_watch_providers=" + s.ids + "&sort_by=popularity.desc"),
                            discover("movie", "&with_watch_providers=" + s.ids + "&sort_by=popularity.desc")))
                            .more(() -> showPage(SERVICE + s.key)));
                }
                out.add(new Section(Ui.tr("Animes em alta", "Trending anime"), () -> fetch(Filter.of("all", "anime"), 1)).more(() -> showPage(ANIME)));
                out.add(new Section(Ui.tr("Para as crianças", "For the kids"), () -> fetch(Filter.of("all", "infantil"), 1)).more(() -> showPage(KIDS)));
                out.add(new Section(Ui.tr("Favoritos", "Favorites"), () -> Tmdb.accountList(this, "favorite")).personal());
                out.add(new Section(Ui.tr("Suas notas", "Your ratings"), () -> Tmdb.accountList(this, "rated")).personal());
                out.add(new Section(Ui.tr("Pessoas em alta", "Trending people"), () -> Tmdb.titles(Tmdb.get("/trending/person/week"), "person"))
                        .more("/trending/person/week", "person"));
                return out;
            }
            default: {
                Service s = service(id);
                if (s == null) return out;
                String p = "&with_watch_providers=" + s.ids;
                out.add(new Section(Ui.tr("Séries em alta", "Trending shows"), () -> discover("tv", p + "&sort_by=popularity.desc"))
                        .more(discoverPath("tv", p + "&sort_by=popularity.desc"), "tv"));
                out.add(new Section(Ui.tr("Filmes em alta", "Trending movies"), () -> discover("movie", p + "&sort_by=popularity.desc"))
                        .more(discoverPath("movie", p + "&sort_by=popularity.desc"), "movie"));
                String fresh = p + "&sort_by=primary_release_date.desc&primary_release_date.lte=" + today + "&vote_count.gte=20";
                out.add(new Section(Ui.tr("Filmes recém-chegados", "New movies"), () -> discover("movie", fresh)).more(discoverPath("movie", fresh), "movie"));
                String newShows = p + "&sort_by=first_air_date.desc&first_air_date.lte=" + today + "&vote_count.gte=10";
                out.add(new Section(Ui.tr("Séries novas", "New shows"), () -> discover("tv", newShows)).more(discoverPath("tv", newShows), "tv"));
                String best = p + "&sort_by=vote_average.desc&vote_count.gte=500";
                out.add(new Section(Ui.tr("Séries mais bem avaliadas", "Top rated shows"), () -> discover("tv", best)).more(discoverPath("tv", best), "tv"));
                out.add(new Section(Ui.tr("Filmes mais bem avaliados", "Top rated movies"), () -> discover("movie", best)).more(discoverPath("movie", best), "movie"));
                return out;
            }
        }
    }

    private static List<Tmdb.Title> top(List<Tmdb.Title> list) {
        return list.size() > 10 ? new ArrayList<>(list.subList(0, 10)) : list;
    }

    // MARK: - Services and TMDB helpers

    /** The services with an app (or all, with "show all"), one per app, most watched first. Asked once. */
    private synchronized List<Service> services() {
        if (services != null) return services;
        List<Service> out = new ArrayList<>();
        try {
            Map<String, Service> byApp = new LinkedHashMap<>();
            Map<String, StringBuilder> ids = new HashMap<>();
            for (Providers.Provider p : Providers.usable(this)) {
                String app = Providers.app(this, p.id, p.name);
                String key = app != null ? app : "p" + p.id;
                if (!byApp.containsKey(key)) {
                    byApp.put(key, new Service(key, p.name, "", p.logo));
                    ids.put(key, new StringBuilder());
                }
                StringBuilder b = ids.get(key);
                b.append(b.length() == 0 ? "" : "|").append(p.id);
            }
            for (Service s : byApp.values()) out.add(new Service(s.key, s.name, ids.get(s.key).toString(), s.logo));
        } catch (Exception e) {
            AppLog.w("explore services: " + e.getMessage());
            return out;   // not kept: the next page asks again
        }
        if (out.size() > 12) out = new ArrayList<>(out.subList(0, 12));
        services = out;
        return out;
    }

    private Service service(String pageId) {
        if (services == null) return null;
        String key = pageId.startsWith(SERVICE) ? pageId.substring(SERVICE.length()) : pageId;
        for (Service s : services) if (s.key.equals(key)) return s;
        return null;
    }

    /** Only the person's services, as discover parameters ("" when there are none; region only with "show all"). */
    private String servicesParam() {
        if (Prefs.showAllProviders()) return "&watch_region=" + Tmdb.region();
        StringBuilder ids = new StringBuilder();
        for (Service s : services()) ids.append(ids.length() == 0 ? "" : "|").append(s.ids);
        return ids.length() == 0 ? "" : "&watch_region=" + Tmdb.region() + "&with_watch_providers=" + ids
                + "&with_watch_monetization_types=flatrate";
    }

    private static String discoverPath(String kind, String params) {
        String region = params.contains("watch_region") ? "" : "&watch_region=" + Tmdb.region();
        return "/discover/" + kind + "?include_adult=false" + region + params;
    }

    private List<Tmdb.Title> discover(String kind, String params) throws Exception {
        return Tmdb.titles(Tmdb.get(discoverPath(kind, params)), kind);
    }

    /** Trending, kept to the person's services unless "show all" is on. */
    private List<Tmdb.Title> mine(String kind, List<Tmdb.Title> trending) throws Exception {
        if (Prefs.showAllProviders()) return trending;
        String services = servicesParam();
        if (services.isEmpty()) return trending;
        Set<Integer> ok = new HashSet<>();
        for (int page = 1; page <= 2; page++) {
            for (Tmdb.Title t : Tmdb.titles(Tmdb.get("/discover/" + kind + "?sort_by=popularity.desc&page=" + page + services), kind)) ok.add(t.id);
        }
        List<Tmdb.Title> out = new ArrayList<>();
        for (Tmdb.Title t : trending) if (ok.contains(t.id)) out.add(t);
        return out.size() >= 5 ? out : trending;
    }

    /** From what was seen and saved: each one's recommendations, the ones that come up most often first. */
    private List<Tmdb.Title> forYou() throws Exception {
        List<Tmdb.Title> seeds = new ArrayList<>(Tmdb.seen(this));
        try { seeds.addAll(Tmdb.watchlist(this)); } catch (Exception ignored) {}
        Set<String> skip = new HashSet<>();
        for (Tmdb.Title t : seeds) skip.add(t.key());
        Map<String, Tmdb.Title> byKey = new LinkedHashMap<>();
        Map<String, Integer> count = new HashMap<>();
        int used = 0;
        for (Tmdb.Title s : seeds) {
            if (used++ >= 8) break;
            for (Tmdb.Title t : Tmdb.titles(Tmdb.get("/" + s.kind + "/" + s.id + "/recommendations"), s.kind)) {
                if (skip.contains(t.key())) continue;
                byKey.putIfAbsent(t.key(), t);
                count.merge(t.key(), 1, Integer::sum);
            }
        }
        List<Tmdb.Title> out = new ArrayList<>(byKey.values());
        out.sort((a, b) -> {
            int byCount = Integer.compare(count.get(b.key()), count.get(a.key()));
            return byCount != 0 ? byCount : Double.compare(b.vote, a.vote);
        });
        return out.size() > 40 ? new ArrayList<>(out.subList(0, 40)) : out;
    }

    private List<Tmdb.Title> fresh(List<Tmdb.Title> list) {
        Set<String> seen = new HashSet<>();
        for (Tmdb.Title t : Tmdb.seen(this)) seen.add(t.key());
        List<Tmdb.Title> out = new ArrayList<>();
        for (Tmdb.Title t : list) if (!seen.contains(t.key())) out.add(t);
        return out;
    }

    private static List<Tmdb.Title> mix(List<Tmdb.Title> a, List<Tmdb.Title> b) {
        List<Tmdb.Title> out = new ArrayList<>();
        for (int i = 0; i < Math.max(a.size(), b.size()); i++) {
            if (i < a.size()) out.add(a.get(i));
            if (i < b.size()) out.add(b.get(i));
        }
        return out;
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
