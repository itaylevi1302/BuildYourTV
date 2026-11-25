package io.cleanyourtv.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
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

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A movie's or a show's page in the tk-stream style: a hero (poster, title, pills, overview, credits and the actions),
 * where it streams in the region, the episodes of a season picked from a dropdown, similar titles, collection, cast,
 * keywords, reviews and the facts. The page is not mirrored in Hebrew: the hero is a picture, laid out as drawn.
 */
public class DetailActivity extends Activity {
    private static final String KIND = "kind", ID = "id";

    static void open(Context c, String kind, int id) {
        if ("person".equals(kind)) {
            PersonActivity.open(c, id);
            return;
        }
        Intent i = new Intent(c, DetailActivity.class).putExtra(KIND, kind).putExtra(ID, id);
        if (!(c instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(i);
    }

    /** The page's one TMDB request; TitleMenu's Watch asks the same, so the page opens from the cache. */
    static String path(String kind, int id) {
        String extra = "movie".equals(kind) ? "release_dates,credits" : "content_ratings,aggregate_credits";
        return "/" + kind + "/" + id + "?append_to_response=watch/providers,recommendations,videos,reviews,keywords,external_ids," + extra;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private float k;
    private String kind;
    private int id;
    private Tmdb.Title title;
    private LinearLayout content;
    private TvUi.Action listButton, seenButton, favButton, rateButton, primaryButton, startOverButton, back;
    private View initialFocus, beforeBack, providerStartOver;
    private TextView actionHint;
    private boolean inList, favorite;
    private int myRating;
    // Watch providers (#106): the request for this title, what came back, and what must be redone when it changes.
    private JSONObject baseRequest;
    private TvUi.Flow actions;
    private final List<View> providerChips = new ArrayList<>();
    private View providerWatchButton;
    private TextView noneText;
    private boolean streamingWatch, keyPressed;
    private int providerStamp;
    private int askGeneration;
    // Continue watching (#110): where tk:watching says the person stopped, the Continue button and what it falls back to.
    private TmdbLists.Progress progress;
    private String[] streamingApp;
    private WatchProviders.Item firstProviderItem;
    // Episodes: the season picker and the list under it.
    private List<Tmdb.Title> seasonCards = new ArrayList<>();
    private int seasonIndex;
    private TvUi.Action seasonButton;
    private EpisodeList episodeList;
    private static final Set<String> askedThisRun = new HashSet<>();   // answered with Back: asked again next launch, not on every page

    private int px(float v) {
        return Math.round(v * k);
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        k = TvUi.k(this);
        kind = getIntent().getStringExtra(KIND);
        id = getIntent().getIntExtra(ID, 0);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(TvUi.BG);
        ImageView backdrop = new ImageView(this);   // one small blurred copy of the backdrop, fixed behind the page
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        backdrop.setAlpha(0f);
        root.addView(backdrop, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[] {0xFF0B0D12, 0xDB0B0D12, 0x730B0D12, 0x400B0D12}));
        root.addView(shade, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View floor = new View(this);
        floor.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[] {0xE60B0D12, 0x000B0D12}));
        root.addView(floor, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Math.round(getResources().getDisplayMetrics().heightPixels * 0.55f), Gravity.BOTTOM));
        TvUi.Scroll scroll = new TvUi.Scroll(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        content.setPaddingRelative(px(96), px(120), 0, px(80));
        content.setClipToPadding(false);
        TextView loading = TvUi.text(this, k, 30, Ui.regular, TvUi.DIM);
        loading.setText(Strings.get(R.string.loading));
        content.addView(loading);
        scroll.addView(content);
        root.addView(scroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        back = TvUi.action(this, k, R.drawable.ic_arrow_back, null, v -> finish()).rest(0x73000000);
        back.setContentDescription(Strings.get(R.string.back));
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        bp.setMarginStart(px(28));
        bp.topMargin = px(28);
        root.addView(back, bp);
        setContentView(root);
        root.getViewTreeObserver().addOnGlobalFocusChangeListener((was, now) -> hint());
        new Thread(() -> {
            try {
                JSONObject o = Tmdb.get(path(kind, id));
                JSONObject collection = null;
                JSONObject belongs = o.optJSONObject("belongs_to_collection");
                if (belongs != null) {
                    try { collection = Tmdb.get("/collection/" + belongs.getInt("id")); } catch (Exception ignored) {}
                }
                JSONArray reviews = o.optJSONObject("reviews") == null ? null : o.optJSONObject("reviews").optJSONArray("results");
                if (reviews == null || reviews.length() == 0) {
                    try { reviews = Tmdb.get("/" + kind + "/" + id + "/reviews?language=en-US").optJSONArray("results"); } catch (Exception ignored) {}
                }
                JSONObject states = Tmdb.states(this, kind, id);
                TmdbLists.Progress prog = TmdbLists.progress(this, kind, id);
                boolean saved = Tmdb.signedIn(this) ? states.optBoolean("watchlist") : Tmdb.inWatchlist(this, kind, id);
                JSONObject col = collection;
                JSONArray rev = reviews;
                main.post(() -> {
                    if (isFinishing()) return;
                    inList = saved;
                    progress = prog;
                    favorite = states.optBoolean("favorite");
                    myRating = Tmdb.ratingOf(states);
                    show(o, col, rev);
                });
                String b = o.optString("backdrop_path");
                if (!b.isEmpty() && !"null".equals(b)) {
                    Bitmap big = Images.fetch("https://image.tmdb.org/t/p/w300" + b);
                    if (big != null) {
                        Bitmap soft = ExploreActivity.blur(Bitmap.createScaledBitmap(big, 160, Math.max(1, Math.round(160f * big.getHeight() / big.getWidth())), true), 3);
                        main.post(() -> {
                            if (isFinishing()) return;
                            backdrop.setImageBitmap(soft);
                            backdrop.animate().alpha(1).setDuration(400).start();
                        });
                    }
                }
            } catch (Exception e) {
                main.post(() -> loading.setText(Strings.get(R.string.couldnt_open_this_title_prefix) + e.getMessage()));
            }
        }).start();
    }

    private void show(JSONObject o, JSONObject collection, JSONArray reviews) {
        content.removeAllViews();
        boolean movie = kind.equals("movie");
        String name = o.optString(movie ? "title" : "name");
        String date = o.optString(movie ? "release_date" : "first_air_date");
        String year = date.length() >= 4 ? date.substring(0, 4) : "";
        title = new Tmdb.Title(kind, id, name, o.optString("poster_path"), o.optString("backdrop_path"), year, o.optDouble("vote_average"));
        int yearNumber = 0;
        try { yearNumber = Integer.parseInt(year); } catch (NumberFormatException ignored) {}
        baseRequest = WatchProviders.request(kind, id, name, o.optString(movie ? "original_title" : "original_name"), yearNumber,
                o.optJSONObject("external_ids"), 0, 0);

        JSONObject region = region(o);
        String[] watchApp = firstApp(this, region);
        providerWatchButton = null;
        primaryButton = null;
        startOverButton = null;
        providerStartOver = null;
        streamingWatch = watchApp != null;
        streamingApp = watchApp;
        content.addView(hero(o, movie, name, year, watchApp), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        labels();

        content.addView(header(Strings.get(R.string.where_to_watch)));
        content.addView(whereToWatch(region), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TvUi.OnTitle open = t -> DetailActivity.open(this, t.kind, t.id);
        if (!movie) episodes(o);
        JSONObject recs = o.optJSONObject("recommendations");
        List<Tmdb.Title> similar = recs == null ? new ArrayList<>() : Tmdb.titles(recs, kind);
        if (!similar.isEmpty()) content.addView(TvUi.row(this, k, Strings.get(R.string.more_like_this), similar, open,
                () -> GridActivity.open(this, Strings.get(R.string.like_prefix) + name, "/" + kind + "/" + id + "/recommendations", kind), px(96)));
        if (collection != null) {
            List<Tmdb.Title> parts = Tmdb.titles(collection, "parts", "movie");
            parts.sort((a, b) -> a.year.compareTo(b.year));
            if (parts.size() > 1) content.addView(TvUi.row(this, k, collection.optString("name"), parts, open, null, px(96)));
        }
        List<Tmdb.Title> cast = cast(o, movie);
        if (!cast.isEmpty()) content.addView(TvUi.row(this, k, Strings.get(R.string.cast), cast, open,
                () -> GridActivity.show(this, name + " · " + Strings.get(R.string.cast), cast, open), px(96)));
        View keywords = keywords(o, movie);
        if (keywords != null) {
            content.addView(header(Strings.get(R.string.keywords)));
            content.addView(keywords, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        if (reviews != null && reviews.length() > 0) {
            content.addView(header(Strings.get(R.string.reviews)));
            for (int i = 0; i < reviews.length() && i < 2; i++) content.addView(review(reviews.optJSONObject(i)));
        }
        View facts = facts(o, movie);
        if (facts != null) {
            content.addView(header(Strings.get(R.string.facts)));
            content.addView(facts);
        }
        initialFocus = actions.getChildAt(0);
        if (initialFocus != null) initialFocus.requestFocus();
        new Thread(() -> {
            java.util.Map<String, String> links = DeepLinks.find(this, kind, id, name);
            main.post(() -> { if (!isFinishing()) linksKnown(links); });
        }).start();
        askProviders(true);
    }

    private static JSONObject region(JSONObject o) {
        JSONObject wp = o.optJSONObject("watch/providers");
        JSONObject results = wp == null ? null : wp.optJSONObject("results");
        return results == null ? null : results.optJSONObject(Tmdb.region());
    }

    private TextView pill(String text, boolean soft) {
        TextView t = TvUi.text(this, k, 24, Ui.medium, soft ? TvUi.DIM : TvUi.INK);
        t.setText(text);
        t.setPaddingRelative(px(14), px(8), px(14), px(8));
        GradientDrawable d = TvUi.round(0x4D0B0D12, 10 * k);
        d.setStroke(Math.max(1, Math.round(1.5f * k)), 0x29FFFFFF);
        t.setBackground(d);
        return t;
    }

    /** The top of the page: the poster at the right; at the left the eyebrow, title, pills, overview, credits and actions. */
    private View hero(JSONObject o, boolean movie, String name, String year, String[] watchApp) {
        FrameLayout hero = new FrameLayout(this);
        hero.setClipChildren(false);
        hero.setMinimumHeight(getResources().getDisplayMetrics().heightPixels - px(140));
        String poster = o.optString("poster_path");
        if (!poster.isEmpty() && !"null".equals(poster)) {
            ImageView img = new ImageView(this);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            img.setBackground(TvUi.round(TvUi.CARD, 14 * k));
            img.setClipToOutline(true);
            img.setElevation(24 * k);
            Images.load(img, Tmdb.POSTER + poster);
            FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(px(300), px(450), Gravity.TOP | Gravity.END);
            pp.setMarginEnd(px(96));
            hero.addView(img, pp);
        }
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setClipChildren(false);
        JSONArray genres = o.optJSONArray("genres");
        String genre = genres != null && genres.length() > 0 ? genres.optJSONObject(0).optString("name") : "";
        TextView eyebrow = TvUi.text(this, k, 22, Ui.medium, TvUi.DIM);
        eyebrow.setText((movie ? Strings.get(R.string.movie) : Strings.get(R.string.show)).toUpperCase(Ui.locale())
                + (genre.isEmpty() ? "" : "  ·  " + genre.toUpperCase(Ui.locale())));
        eyebrow.setLetterSpacing(0.2f);
        left.addView(eyebrow);
        TextView h = TvUi.text(this, k, 84, Ui.displaySemibold, TvUi.INK);
        h.setText(name);
        h.setMaxLines(2);
        h.setEllipsize(TextUtils.TruncateAt.END);
        h.setShadowLayer(20 * k, 0, 2 * k, 0x66000000);
        h.setLetterSpacing(-0.02f);
        h.setPaddingRelative(0, px(10), 0, 0);
        left.addView(h);

        TvUi.Flow pills = new TvUi.Flow(this, px(10), px(10));
        pills.setPaddingRelative(0, px(22), 0, 0);
        String cert = certification(o);
        if (!cert.isEmpty()) pills.addView(pill(cert, false));
        if (!year.isEmpty()) pills.addView(pill(year, false));
        double vote = o.optDouble("vote_average");
        if (vote > 0) pills.addView(pill(String.format(Ui.PT, "★ %.1f", vote), false));
        int runtime = o.optInt("runtime");
        int seasons = o.optInt("number_of_seasons");
        if (movie && runtime > 0) pills.addView(pill(Strings.duration(runtime), false));
        if (!movie && seasons > 0) pills.addView(pill(Strings.quantity(R.plurals.seasons, seasons), false));
        for (int i = 1; genres != null && i < genres.length() && i < 4; i++) pills.addView(pill(genres.optJSONObject(i).optString("name"), true));
        left.addView(pills, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        String status = status(o, movie);
        if (!status.isEmpty()) {
            TextView st = TvUi.text(this, k, 25, Ui.medium, TvUi.ACCENT);
            st.setText(status);
            st.setPaddingRelative(0, px(18), 0, 0);
            left.addView(st);
        }
        String tagline = o.optString("tagline");
        if (!tagline.isEmpty()) {
            TextView t = TvUi.text(this, k, 28, Ui.medium, TvUi.INK);
            t.setText(tagline);
            t.setPaddingRelative(0, px(20), 0, 0);
            left.addView(t);
        }
        String overview = o.optString("overview");
        if (!overview.isEmpty()) {
            TextView ov = TvUi.text(this, k, 28, Ui.regular, 0xFFD8D8DD);
            ov.setText(overview);
            ov.setMaxWidth(px(1150));
            ov.setMaxLines(4);
            ov.setEllipsize(TextUtils.TruncateAt.END);
            ov.setLineSpacing(8 * k, 1);
            ov.setShadowLayer(8 * k, 0, k, 0x99000000);
            ov.setPaddingRelative(0, px(16), 0, 0);
            left.addView(ov);
        }
        View credits = credits(o, movie);
        if (credits != null) left.addView(credits);

        actions = new TvUi.Flow(this, px(16), px(16));
        actions.setPaddingRelative(0, px(30), 0, px(16));
        boolean account = Tmdb.signedIn(this);
        if (progress != null) {
            primaryButton = TvUi.action(this, k, R.drawable.ic_play, resumeLabel(), v -> resume());
            actions.addView(primaryButton);
        }
        if (watchApp != null) {
            actions.addView(TvUi.action(this, k, R.drawable.ic_play, Strings.get(R.string.watch_on_prefix) + watchApp[1], v -> watch(watchApp[0])));
            if (progress == null) primaryButton = (TvUi.Action) actions.getChildAt(0);
            if (progress != null && progress.t > 30) {
                // CleanYourTV can't seek and never writes tk:watching: the app decides where to start.
                startOverButton = TvUi.action(this, k, R.drawable.ic_replay, Strings.get(R.string.start_over), v -> watch(watchApp[0]));
                actions.addView(startOverButton);
            }
        }
        String trailer = trailer(o);
        if (trailer != null) actions.addView(TvUi.action(this, k, R.drawable.ic_trailer, Strings.get(R.string.trailer), v -> playTrailer(trailer)));
        listButton = round(R.drawable.ic_bookmark_border, TvUi.ACCENT, () -> inList ? Strings.get(R.string.in_my_list) : Strings.get(R.string.add_to_my_list), v -> toggleList());
        actions.addView(listButton);
        if (account) {
            favButton = round(R.drawable.ic_favorite_border, 0xFFFF5A5F, () -> favorite ? Strings.get(R.string.favorite) : Strings.get(R.string.mark_favorite), v -> toggleFavorite());
            actions.addView(favButton);
        } else {
            favButton = null;
        }
        seenButton = round(R.drawable.ic_check, 0xFF4ADE80, () -> Tmdb.isSeen(this, kind, id) ? Strings.get(R.string.seen) : Strings.get(R.string.ive_seen_it), v -> toggleSeen());
        actions.addView(seenButton);
        if (account) {
            rateButton = new TvUi.Action(this, k, R.drawable.ic_star_border, Strings.get(R.string.rate), 0xFFFDE047, v -> rate());
            actions.addView(rateButton);
            if (movie) actions.addView(round(R.drawable.ic_playlist_add, TvUi.INK, () -> Strings.get(R.string.add_to_a_list), v -> addToList()));
        } else {
            rateButton = null;
        }
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        left.addView(actions, ap);
        actionHint = TvUi.text(this, k, 22, Ui.regular, TvUi.DIM);
        actionHint.setPaddingRelative(0, px(12), 0, 0);
        left.addView(actionHint);
        if (progress != null && progress.d > 0) left.addView(progressLine());
        hero.addView(left, new FrameLayout.LayoutParams(px(1250), ViewGroup.LayoutParams.WRAP_CONTENT));
        return hero;
    }

    /** A round action: its label is shown under the row while it has the focus (the page's hint line). */
    private TvUi.Action round(int icon, int onColor, java.util.function.Supplier<String> hint, View.OnClickListener click) {
        TvUi.Action a = new TvUi.Action(this, k, icon, null, onColor, click);
        a.setTag(hint);
        return a;
    }

    @SuppressWarnings("unchecked")
    private void hint() {
        if (actionHint == null) return;
        View f = getCurrentFocus();
        actionHint.setText(f != null && f.getTag() instanceof java.util.function.Supplier ? ((java.util.function.Supplier<String>) f.getTag()).get() : "");
    }

    /** How far the person got: a bar and the time left. */
    private View progressLine() {
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPaddingRelative(0, px(8), 0, 0);
        FrameLayout track = new FrameLayout(this);
        track.setBackground(TvUi.round(0x33FFFFFF, 3 * k));
        float fraction = Math.max(0f, Math.min(1f, progress.t / (float) progress.d));
        View fill = new View(this);
        fill.setBackground(TvUi.round(TvUi.ACCENT, 3 * k));
        track.addView(fill, new FrameLayout.LayoutParams(Math.round(px(280) * fraction), ViewGroup.LayoutParams.MATCH_PARENT));
        line.addView(track, new LinearLayout.LayoutParams(px(280), px(6)));
        TextView left = TvUi.text(this, k, 22, Ui.regular, TvUi.DIM);
        left.setText(Strings.get(R.string.watch_time_left, Strings.duration(Math.max(1, (progress.d - progress.t) / 60))));
        left.setPaddingRelative(px(16), 0, 0, 0);
        line.addView(left);
        return line;
    }

    /** Label, value rows under the overview: director or creators, the main cast, the network. */
    private View credits(JSONObject o, boolean movie) {
        List<String[]> rows = new ArrayList<>();
        List<String> names = new ArrayList<>();
        if (movie) {
            JSONArray crew = o.optJSONObject("credits") == null ? null : o.optJSONObject("credits").optJSONArray("crew");
            for (int i = 0; crew != null && i < crew.length(); i++) {
                JSONObject c = crew.optJSONObject(i);
                if ("Director".equals(c.optString("job")) && !names.contains(c.optString("name"))) names.add(c.optString("name"));
            }
            if (!names.isEmpty()) rows.add(new String[] {Strings.get(R.string.credit_director), TextUtils.join(", ", names)});
        } else {
            JSONArray created = o.optJSONArray("created_by");
            for (int i = 0; created != null && i < created.length(); i++) names.add(created.optJSONObject(i).optString("name"));
            if (!names.isEmpty()) rows.add(new String[] {Strings.get(R.string.credit_creators), TextUtils.join(", ", names)});
        }
        List<String> cast = new ArrayList<>();
        JSONObject credits = o.optJSONObject(movie ? "credits" : "aggregate_credits");
        JSONArray list = credits == null ? null : credits.optJSONArray("cast");
        for (int i = 0; list != null && i < list.length() && cast.size() < 4; i++) cast.add(list.optJSONObject(i).optString("name"));
        if (!cast.isEmpty()) rows.add(new String[] {Strings.get(R.string.cast), TextUtils.join(", ", cast)});
        if (!movie) {
            JSONArray networks = o.optJSONArray("networks");
            String net = networks != null && networks.length() > 0 ? networks.optJSONObject(0).optString("name") : "";
            if (!net.isEmpty()) rows.add(new String[] {Strings.get(R.string.credit_network), net});
        }
        if (rows.isEmpty()) return null;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPaddingRelative(0, px(18), 0, 0);
        for (String[] r : rows) {
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setPaddingRelative(0, px(4), 0, px(4));
            TextView label = TvUi.text(this, k, 24, Ui.regular, TvUi.DIM);
            label.setText(r[0]);
            line.addView(label, new LinearLayout.LayoutParams(px(220), ViewGroup.LayoutParams.WRAP_CONTENT));
            TextView value = TvUi.text(this, k, 24, Ui.regular, 0xFFE4E4E7);
            value.setText(r[1]);
            value.setMaxLines(1);
            value.setEllipsize(TextUtils.TruncateAt.END);
            line.addView(value, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            box.addView(line);
        }
        return box;
    }

    /** The season picker (a dropdown) and that season's episodes, on the page. */
    private void episodes(JSONObject o) {
        seasonCards = seasons(o);
        if (seasonCards.isEmpty()) return;
        int at = -1;
        if (progress != null && progress.season > 0) at = seasonIndexOf(progress.season);
        if (at < 0) at = seasonIndexOf(Tmdb.lastSeenSeason(this, id));
        for (int i = 0; at < 0 && i < seasonCards.size(); i++) if (seasonCards.get(i).id > 0) at = i;
        seasonIndex = Math.max(0, at);
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPaddingRelative(0, px(40), 0, px(12));
        TextView h = TvUi.text(this, k, 32, Ui.semibold, TvUi.INK);
        h.setText(Strings.get(R.string.episodes));
        head.addView(h);
        seasonButton = TvUi.action(this, k, R.drawable.ic_expand_more, seasonCards.get(seasonIndex).name, v -> pickSeason());
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.setMarginStart(px(24));
        head.addView(seasonButton, sp);
        content.addView(head);
        episodeList = new EpisodeList(this, k, id, baseRequest == null ? null : baseRequest.toString());
        episodeList.setPaddingRelative(0, 0, px(96), 0);
        episodeList.setProgress(progress);
        content.addView(episodeList, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        episodeList.load(seasonCards.get(seasonIndex).id);
    }

    private int seasonIndexOf(int season) {
        for (int i = 0; i < seasonCards.size(); i++) if (seasonCards.get(i).id == season && season >= 0) return i;
        return -1;
    }

    private void pickSeason() {
        String[] names = new String[seasonCards.size()];
        for (int i = 0; i < names.length; i++) names[i] = seasonCards.get(i).name;
        TvUi.dropdown(this, getWindow().getDecorView(), TvUi.rectOf(seasonButton), Strings.get(R.string.seasons), names, seasonIndex, which -> {
            seasonIndex = which;
            Tmdb.Title t = seasonCards.get(which);
            seasonButton.set(R.drawable.ic_expand_more, t.name, false);
            episodeList.load(t.id);
            seasonButton.requestFocus();
        });
    }

    /** The page's last box: one focusable card of label, value rows (what TMDB knows beyond the hero). */
    private View facts(JSONObject o, boolean movie) {
        List<String[]> rows = new ArrayList<>();
        String name = o.optString(movie ? "title" : "name"), original = o.optString(movie ? "original_title" : "original_name");
        if (!original.isEmpty() && !original.equals(name)) rows.add(new String[] {Strings.get(R.string.original_title), original});
        String date = o.optString(movie ? "release_date" : "first_air_date");
        if (date.length() == 10) rows.add(new String[] {Strings.get(R.string.released), date.substring(8) + "/" + date.substring(5, 7) + "/" + date.substring(0, 4)});
        String last = o.optString("last_air_date");
        if (!movie && last.length() == 10) rows.add(new String[] {Strings.get(R.string.last_aired), last.substring(8) + "/" + last.substring(5, 7) + "/" + last.substring(0, 4)});
        int runtime = o.optInt("runtime");
        JSONArray each = o.optJSONArray("episode_run_time");
        if (!movie && each != null && each.length() > 0) runtime = each.optInt(0);
        if (runtime > 0) rows.add(new String[] {Strings.get(R.string.runtime), Strings.duration(runtime)});
        int seasons = o.optInt("number_of_seasons"), episodes = o.optInt("number_of_episodes");
        if (!movie && episodes > 0) rows.add(new String[] {Strings.get(R.string.episodes), episodes + (seasons > 0 ? "  ·  " + Strings.quantity(R.plurals.seasons, seasons) : "")});
        String lang = o.optString("original_language");
        if (!lang.isEmpty()) {
            String language = new Locale(lang).getDisplayLanguage(Ui.locale());
            rows.add(new String[] {Strings.get(R.string.original_language), language.isEmpty() ? lang : language});
        }
        JSONArray companies = o.optJSONArray("production_companies");
        List<String> made = new ArrayList<>();
        for (int i = 0; companies != null && i < companies.length() && made.size() < 3; i++) made.add(companies.optJSONObject(i).optString("name"));
        if (!made.isEmpty()) rows.add(new String[] {Strings.get(R.string.production), TextUtils.join(", ", made)});
        NumberFormat money = NumberFormat.getCurrencyInstance(Locale.US);
        money.setMaximumFractionDigits(0);
        if (movie && o.optLong("budget") > 0) rows.add(new String[] {Strings.get(R.string.budget), money.format(o.optLong("budget"))});
        if (movie && o.optLong("revenue") > 0) rows.add(new String[] {Strings.get(R.string.revenue), money.format(o.optLong("revenue"))});
        rows.add(new String[] {"TMDB", String.valueOf(id)});
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPaddingRelative(px(28), px(22), px(28), px(22));
        for (String[] r : rows) {
            if (r[1].isEmpty()) continue;
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setPaddingRelative(0, px(6), 0, px(6));
            TextView label = TvUi.text(this, k, 24, Ui.regular, TvUi.DIM);
            label.setText(r[0]);
            line.addView(label, new LinearLayout.LayoutParams(px(300), ViewGroup.LayoutParams.WRAP_CONTENT));
            TextView value = TvUi.text(this, k, 24, Ui.medium, TvUi.INK);
            value.setText(r[1]);
            line.addView(value, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            box.addView(line);
        }
        TvUi.softFocus(box, k, 18 * k);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(1500), ViewGroup.LayoutParams.WRAP_CONTENT);
        box.setLayoutParams(lp);
        return box;
    }

    private String certification(JSONObject o) {
        try {
            if (kind.equals("movie")) {
                JSONArray results = o.getJSONObject("release_dates").getJSONArray("results");
                for (int i = 0; i < results.length(); i++) {
                    JSONObject r = results.getJSONObject(i);
                    if (!Tmdb.region().equals(r.optString("iso_3166_1"))) continue;
                    JSONArray dates = r.getJSONArray("release_dates");
                    for (int j = 0; j < dates.length(); j++) {
                        String c = dates.getJSONObject(j).optString("certification");
                        if (!c.isEmpty()) return c.equals("L") ? Strings.get(R.string.all_ages) : c + "+";
                    }
                }
            } else {
                JSONArray results = o.getJSONObject("content_ratings").getJSONArray("results");
                for (int i = 0; i < results.length(); i++) {
                    JSONObject r = results.getJSONObject(i);
                    if (Tmdb.region().equals(r.optString("iso_3166_1"))) {
                        String c = r.optString("rating");
                        return c.equals("L") ? Strings.get(R.string.all_ages) : c.isEmpty() ? "" : c + "+";
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    /** For a show still running: the next episode's date, or that it ended. */
    private static String status(JSONObject o, boolean movie) {
        if (movie) return "";
        JSONObject next = o.optJSONObject("next_episode_to_air");
        if (next != null) {
            String d = next.optString("air_date");
            String when = d.length() == 10 ? d.substring(8) + "/" + d.substring(5, 7) : d;
            String ep = Ui.he ? "עונה " + next.optInt("season_number") + ", פרק " + next.optInt("episode_number")
                    : "T" + next.optInt("season_number") + "E" + next.optInt("episode_number");
            return Strings.get(R.string.next_episode_prefix) + ep + " · " + when;
        }
        String s = o.optString("status");
        if (s.equals("Ended")) return Strings.get(R.string.ended);
        if (s.equals("Canceled")) return Strings.get(R.string.canceled);
        return "";
    }

    private static List<Tmdb.Title> seasons(JSONObject o) {
        List<Tmdb.Title> out = new ArrayList<>();
        JSONArray list = o.optJSONArray("seasons");
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject s = list.optJSONObject(i);
            int n = s.optInt("season_number");
            String label = n == 0 ? Strings.get(R.string.specials) : Strings.get(R.string.season_prefix) + n;
            String poster = s.optString("poster_path");
            out.add(new Tmdb.Title("season", n, label + " · " + s.optInt("episode_count") + Strings.get(R.string.ep_suffix),
                    "null".equals(poster) ? "" : poster, "", "", 0));
        }
        out.sort((a, b) -> a.id == 0 ? 1 : b.id == 0 ? -1 : Integer.compare(a.id, b.id));
        return out;
    }

    private static List<Tmdb.Title> cast(JSONObject o, boolean movie) {
        List<Tmdb.Title> out = new ArrayList<>();
        JSONObject credits = o.optJSONObject(movie ? "credits" : "aggregate_credits");
        JSONArray list = credits == null ? null : credits.optJSONArray("cast");
        for (int i = 0; list != null && i < list.length() && out.size() < 60; i++) {
            JSONObject p = list.optJSONObject(i);
            String face = p.optString("profile_path");
            String role = movie ? p.optString("character") : roles(p);
            out.add(new Tmdb.Title("person", p.optInt("id"), p.optString("name") + (role.isEmpty() ? "" : " · " + role),
                    "null".equals(face) ? "" : face, "", role, 0));
        }
        return out;
    }

    private static String roles(JSONObject p) {
        JSONArray roles = p.optJSONArray("roles");
        return roles != null && roles.length() > 0 ? roles.optJSONObject(0).optString("character") : "";
    }

    private View keywords(JSONObject o, boolean movie) {
        JSONObject kw = o.optJSONObject("keywords");
        JSONArray list = kw == null ? null : kw.optJSONArray(movie ? "keywords" : "results");
        if (list == null || list.length() == 0) return null;
        TvUi.Flow line = new TvUi.Flow(this, Math.round(16 * k), Math.round(16 * k));
        line.setPaddingRelative(Math.round(14 * k), Math.round(14 * k), Math.round(96 * k), Math.round(14 * k));
        for (int i = 0; i < list.length() && i < 12; i++) {
            JSONObject w = list.optJSONObject(i);
            String word = w.optString("name");
            int wid = w.optInt("id");
            line.addView(TvUi.button(this, k, word, v -> GridActivity.open(this, Strings.get(R.string.tagged) + word + "”",
                    "/discover/" + kind + "?with_keywords=" + wid + "&sort_by=popularity.desc", kind)));
        }
        return line;
    }

    private View review(JSONObject r) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPaddingRelative(Math.round(28 * k), Math.round(22 * k), Math.round(28 * k), Math.round(22 * k));
        JSONObject details = r.optJSONObject("author_details");
        double rating = details == null ? 0 : details.optDouble("rating", 0);
        TextView who = TvUi.text(this, k, 25, Ui.medium, TvUi.INK);
        who.setText(r.optString("author") + (rating > 0 ? String.format(Ui.PT, "  ·  ★ %.0f", rating) : ""));
        TextView body = TvUi.text(this, k, 23, Ui.regular, TvUi.DIM);
        body.setText(r.optString("content").replaceAll("\\s+", " "));
        body.setMaxLines(4);
        body.setEllipsize(TextUtils.TruncateAt.END);
        body.setPaddingRelative(0, Math.round(10 * k), 0, 0);
        box.addView(who);
        box.addView(body);
        TvUi.softFocus(box, k, 18 * k);
        box.setOnClickListener(v -> new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(r.optString("author")).setMessage(r.optString("content")).setPositiveButton("OK", null).show());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Math.round(1500 * k), ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Math.round(14 * k);
        box.setLayoutParams(lp);
        return box;
    }

    private TextView header(String text) {
        TextView t = TvUi.text(this, k, 32, Ui.semibold, TvUi.INK);
        t.setText(text);
        t.setPaddingRelative(0, Math.round(40 * k), 0, Math.round(12 * k));
        return t;
    }

    private void labels() {
        listButton.set(inList ? R.drawable.ic_bookmark : R.drawable.ic_bookmark_border, null, inList);
        seenButton.set(R.drawable.ic_check, null, Tmdb.isSeen(this, kind, id));
        if (favButton != null) favButton.set(favorite ? R.drawable.ic_favorite : R.drawable.ic_favorite_border, null, favorite);
        if (rateButton != null) {
            rateButton.set(myRating > 0 ? R.drawable.ic_star : R.drawable.ic_star_border, myRating > 0 ? String.valueOf(myRating) : Strings.get(R.string.rate), myRating > 0);
        }
        hint();
    }

    /** The first subscription or free service with an app on the TV: {package, service name}. */
    static String[] firstApp(Context c, JSONObject region) {
        if (region == null) return null;
        for (String type : new String[] {"flatrate", "free", "ads"}) {
            JSONArray list = region.optJSONArray(type);
            for (int i = 0; list != null && i < list.length(); i++) {
                JSONObject p = list.optJSONObject(i);
                String app = Providers.app(c, p.optInt("provider_id"), p.optString("provider_name"));
                if (app != null) return new String[] {app, p.optString("provider_name")};
            }
        }
        return null;
    }

    private TvUi.Flow whereLine;
    private View iptvChip;
    private String iptvShown = "";

    private View whereToWatch(JSONObject region) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        whereLine = new TvUi.Flow(this, Math.round(18 * k), Math.round(18 * k));
        whereLine.setPaddingRelative(0, Math.round(14 * k), Math.round(96 * k), Math.round(14 * k));
        String[][] types = {{"flatrate", Strings.get(R.string.subscription)}, {"free", Strings.get(R.string.free)},
                {"ads", Strings.get(R.string.free_with_ads)}, {"rent", Strings.get(R.string.rent)}, {"buy", Strings.get(R.string.buy)}};
        Set<String> added = new HashSet<>();
        for (String[] type : types) {
            JSONArray list = region == null ? null : region.optJSONArray(type[0]);
            for (int i = 0; list != null && i < list.length(); i++) {
                JSONObject p = list.optJSONObject(i);
                int pid = p.optInt("provider_id");
                if (!added.add(pid + type[0])) continue;
                whereLine.addView(chip(pid, p.optString("provider_name"), p.optString("logo_path"), type[1]));
            }
        }
        if (whereLine.getChildCount() == 0) {
            TextView none = TvUi.text(this, k, 26, Ui.regular, TvUi.DIM);
            none.setText(Strings.get(R.string.not_on_any_streaming_service_in_the_region_right_now));
            noneText = none;
            box.addView(none);
        }
        box.addView(whereLine, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        showIptvChip();
        return box;
    }

    /** The IPTV app picked in Settings › Services leads the row. IPTV has no catalog TMDB can check, so the chip
     *  searches the title there rather than promising it. Called again on return, in case the pick changed. */
    private void showIptvChip() {
        if (iptvChip != null) whereLine.removeView(iptvChip);
        iptvShown = Prefs.iptvPlayer();
        iptvChip = null;
        if (!iptvShown.isEmpty() && Providers.installed(this, iptvShown)) {
            ImageView img = new ImageView(this);
            try {
                img.setImageDrawable(getPackageManager().getApplicationIcon(iptvShown));
            } catch (PackageManager.NameNotFoundException ignored) {
            }
            String pkg = iptvShown;
            iptvChip = chipBox(img, Providers.label(this, pkg), chipSub("movie".equals(kind)
                    ? Strings.get(R.string.search_movie_via_iptv) : Strings.get(R.string.search_show_via_iptv)));
            iptvChip.setOnClickListener(v -> searchOrOpen(pkg));
            whereLine.addView(iptvChip, 0);
        }
        whereLine.setVisibility(whereLine.getChildCount() == 0 ? View.GONE : View.VISIBLE);
    }

    /** MEDIA_PLAY_FROM_SEARCH, what voice search sends on Android TV: the app searches the title instead of
     *  opening cold. An app that doesn't answer it gets a heads-up a few times per pick, then just opens. */
    private void searchOrOpen(String pkg) {
        Intent i = new Intent("android.media.action.MEDIA_PLAY_FROM_SEARCH");
        i.setPackage(pkg);
        i.putExtra(android.app.SearchManager.QUERY, title.name);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (i.resolveActivity(getPackageManager()) == null) {
            if (Prefs.iptvFallbackWarnCount() < Prefs.IPTV_FALLBACK_WARN_LIMIT) warnFallback(pkg);
            else openIptv(pkg);
            return;
        }
        try {
            startActivity(i);
            Tmdb.setSeen(this, title, true);
        } catch (Exception e) {
            openIptv(pkg);
        }
    }

    /** IPTV apps have no per-title deep links, so this skips watch()'s lookup and just opens the app. */
    private void openIptv(String pkg) {
        Tmdb.setSeen(this, title, true);
        RemoteKeys.run(this, pkg);
    }

    /** Never says the pick is wrong: a real IPTV player without search support looks the same to Android. */
    private void warnFallback(String pkg) {
        Prefs.setIptvFallbackWarnCount(Prefs.iptvFallbackWarnCount() + 1);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(Providers.label(this, pkg))
                .setMessage(Strings.get(R.string.iptv_search_unavailable))
                .setPositiveButton(Strings.get(R.string.open_anyway), (d, w) -> openIptv(pkg))
                .setNegativeButton(Strings.get(R.string.go_to_settings), (d, w) -> startActivity(new Intent(this, ServicesActivity.class)))
                .show();
    }

    private View chip(int pid, String name, String logo, String type) {
        String app = Providers.app(this, pid, name);
        ImageView img = new ImageView(this);
        Images.load(img, logo.isEmpty() ? null : Tmdb.LOGO + logo);
        TextView s = chipSub(type + "  ·  " + (app != null ? Strings.get(R.string.opens_prefix) + Providers.label(this, app) : Strings.get(R.string.no_app_on_the_tv)));
        if (app != null) chipNotes.add(new Object[] {app, s, type});
        View chip = chipBox(img, name, s);
        chip.setOnClickListener(v -> {
            if (app != null) watch(app);
            else Toast.makeText(this, Strings.get(R.string.service_app_missing, name), Toast.LENGTH_LONG).show();
        });
        return chip;
    }

    private TextView chipSub(String text) {
        TextView s = TvUi.text(this, k, 21, Ui.regular, TvUi.DIM);
        s.setText(text);
        s.setPaddingRelative(0, Math.round(6 * k), 0, 0);
        return s;
    }

    /** The shape every "Onde assistir" chip shares: a logo, the name, and a dim line under it. */
    private LinearLayout chipBox(ImageView img, String name, TextView sub) {
        LinearLayout chip = new LinearLayout(this);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPaddingRelative(Math.round(16 * k), Math.round(16 * k), Math.round(30 * k), Math.round(16 * k));
        img.setClipToOutline(true);
        img.setBackground(TvUi.round(0x00000000, 12 * k));
        int size = Math.round(64 * k);
        chip.addView(img, new LinearLayout.LayoutParams(size, size));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPaddingRelative(Math.round(18 * k), 0, 0, 0);
        TextView n = TvUi.text(this, k, 25, Ui.medium, TvUi.INK);
        n.setText(name);
        texts.addView(n);
        texts.addView(sub);
        chip.addView(texts);
        TvUi.softFocus(chip, k, 18 * k);
        return chip;
    }

    /** Opens the title in the app (a Wikidata deep link), or else the app's home. */
    private void watch(String pkg) {
        if (title != null) Tmdb.setSeen(this, title, true);
        String name = title == null ? "" : title.name;
        new Thread(() -> {
            String url = DeepLinks.find(this, kind, id, name).get(pkg);
            main.post(() -> {
                if (!DeepLinks.open(this, pkg, url)) RemoteKeys.run(this, pkg);
                Recent.note(this, pkg);   // the shelf's "Last opened first" counts it too (#63)
            });
        }).start();
    }

    private final List<Object[]> chipNotes = new ArrayList<>();   // {app, subtitle view, kind of offer}

    /** Once the links are known: the chips that open the title itself say so. */
    private void linksKnown(java.util.Map<String, String> links) {
        for (Object[] n : chipNotes) {
            String app = (String) n[0];
            if (links.containsKey(app)) ((TextView) n[1]).setText(n[2] + "  ·  " + Strings.get(R.string.opens_the_title_in_prefix) + Providers.label(this, app));
        }
    }

    private void background(String failure, Task task) {
        new Thread(() -> {
            try {
                task.run();
                main.post(this::labels);
            } catch (Exception e) {
                main.post(() -> Toast.makeText(this, failure + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private interface Task { void run() throws Exception; }

    private void toggleList() {
        if (title == null) return;
        boolean want = !inList;
        background(Strings.get(R.string.couldnt_save_prefix), () -> {
            Tmdb.setWatchlist(this, title, want);
            inList = want;
        });
    }

    private void toggleSeen() {
        if (title == null) return;
        TmdbLists.markSeen(this, title, !Tmdb.isSeen(this, kind, id));
        labels();
    }

    private void toggleFavorite() {
        if (title == null) return;
        boolean want = !favorite;
        background(Strings.get(R.string.couldnt_favorite_prefix), () -> {
            Tmdb.setFavorite(this, title, want);
            favorite = want;
        });
    }

    private void rate() {
        if (title == null) return;
        TitleMenu.rate(this, title, myRating, v -> {
            myRating = v;
            labels();
        });
    }

    private void addToList() {
        TitleMenu.addToList(this, id);
    }

    private static String trailer(JSONObject o) {
        JSONArray videos = o.optJSONObject("videos") == null ? null : o.optJSONObject("videos").optJSONArray("results");
        for (int i = 0; videos != null && i < videos.length(); i++) {
            JSONObject v = videos.optJSONObject(i);
            if ("YouTube".equals(v.optString("site")) && "Trailer".equals(v.optString("type"))) return v.optString("key");
        }
        return null;
    }

    /** The player picked in Settings > Services > Trailers de Filmes e Séries, when it's still installed; otherwise the official
     *  YouTube (the project's current default), else whichever other YouTube-capable player is found (SmartTube and
     *  the like) - in its own task (started as part of ours, it closes as soon as the video opens). */
    private void playTrailer(String key) {
        Uri url = Uri.parse("https://www.youtube.com/watch?v=" + key);
        String picked = Prefs.trailerPlayer();
        String pkg = Providers.installed(this, picked) ? picked : automaticTrailerPlayer();
        Intent app = pkg == null ? new Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                : new Intent(Intent.ACTION_VIEW, url).setPackage(pkg)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        try {
            startActivity(app);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) {
                Toast.makeText(this, Strings.get(R.string.no_app_to_open_the_trailer), Toast.LENGTH_SHORT).show();
            }
        }
    }

    /** YouTube first (the project's current default), else the first other YouTube-capable player found, else null
     *  - which sends the caller to a plain view intent, with no package forced. */
    private String automaticTrailerPlayer() {
        if (Providers.installed(this, "com.google.android.youtube.tv")) return "com.google.android.youtube.tv";
        List<String> players = Providers.youtubePlayers(this);
        return players.isEmpty() ? null : players.get(0);
    }

    // MARK: - Continue watching (#110)

    private String resumeLabel() {
        boolean tv = "tv".equals(kind);
        if (tv && progress.season > 0 && progress.episode > 0) return Strings.get(R.string.continue_episode, progress.season, progress.episode);
        if (progress.t > 0) return Strings.get(R.string.resume_from, WatchProviderLabels.clock(progress.t));
        return Strings.get(R.string.continue_watching);
    }

    /** Opens the episode (or the title) where tk:watching says the person stopped: an app that has that episode, else the first app that has the title. */
    private void resume() {
        if (progress == null) return;
        if ("tv".equals(kind) && progress.season > 0 && progress.episode > 0 && baseRequest != null) {
            int s = progress.season, e = progress.episode;
            new Thread(() -> {
                List<WatchProviders.Item> items = new ArrayList<>();
                try {
                    JSONObject req = new JSONObject(baseRequest.toString()).put("season", s).put("episode", e);
                    List<WatchProviders.Provider> on = WatchProviders.allowed(WatchProviders.found(this));
                    for (List<WatchProviders.Item> got : WatchProviders.ask(this, on, req.toString()).values()) items.addAll(got);
                } catch (Exception ignored) {
                }
                main.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (items.size() == 1) openProviderItem(items.get(0));
                    else if (items.size() > 1) WatchProviders.choose(this, Strings.get(R.string.watch_episode, s, e), items);
                    else resumeFallback();
                });
            }).start();
            return;
        }
        resumeFallback();
    }

    private void resumeFallback() {
        if (firstProviderItem != null) {
            openProviderItem(firstProviderItem);
        } else if (streamingApp != null) {
            watch(streamingApp[0]);
        } else {
            Toast.makeText(this, Strings.get(R.string.not_on_any_streaming_service_in_the_region_right_now), Toast.LENGTH_LONG).show();
            if (whereLine != null && whereLine.getChildCount() > 0) whereLine.requestFocus();
        }
    }

    // MARK: - Watch providers (#106)

    /** Left at the page's edge goes to the pinned Back, and Right or Down from it returns to where the focus was. */
    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            keyPressed = true;   // from the first key on, focus stays where the person put it
            View f = getCurrentFocus();
            int code = e.getKeyCode();
            if (code == KeyEvent.KEYCODE_DPAD_LEFT && back != null && f != null && f != back) {
                View next = f.focusSearch(View.FOCUS_LEFT);
                if (next == null || next == back) {
                    beforeBack = f;
                    back.requestFocus();
                    return true;
                }
            } else if ((code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_DPAD_DOWN) && f == back) {
                if (beforeBack != null && beforeBack.isAttachedToWindow() && beforeBack.requestFocus()) return true;
                if (initialFocus != null && initialFocus.requestFocus()) return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    /** Finds the apps on the TV that offer results, asks the person about new ones (only when mayPrompt), then asks the
     *  allowed ones about this title. Nothing is sent before an app is allowed. */
    private void askProviders(boolean mayPrompt) {
        providerStamp = WatchProviders.stamp();
        JSONObject req = baseRequest;
        int generation = ++askGeneration;
        new Thread(() -> {
            List<WatchProviders.Provider> all = WatchProviders.found(this);
            main.post(() -> {
                if (isFinishing() || isDestroyed() || generation != askGeneration) return;
                List<WatchProviders.Provider> pending = new ArrayList<>();
                if (mayPrompt) {
                    for (WatchProviders.Provider p : all) if (p.state == WatchProviders.State.NEW && !askedThisRun.contains(p.pkg)) pending.add(p);
                }
                consentChain(pending, 0, () -> new Thread(() -> {
                    List<WatchProviders.Provider> on = WatchProviders.allowed(WatchProviders.found(this));
                    Map<WatchProviders.Provider, List<WatchProviders.Item>> res = on.isEmpty() ? new java.util.LinkedHashMap<>()
                            : WatchProviders.ask(this, on, req.toString());
                    main.post(() -> {
                        if (!isFinishing() && !isDestroyed() && generation == askGeneration) showProviders(res);
                    });
                }).start());
            });
        }).start();
    }

    /** One question per new app, one after the other; then done. */
    private void consentChain(List<WatchProviders.Provider> pending, int at, Runnable done) {
        if (at >= pending.size()) {
            done.run();
            return;
        }
        WatchProviders.Provider p = pending.get(at);
        boolean[] moved = {false};
        Runnable next = () -> {
            if (moved[0]) return;
            moved[0] = true;
            consentChain(pending, at + 1, done);
        };
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(p.label)
                .setMessage(Strings.get(R.string.watch_provider_consent, p.appLabel))
                .setPositiveButton(Strings.get(R.string.allow), (d, w) -> {
                    WatchProviders.decide(p, true);
                    next.run();
                })
                .setNegativeButton(Strings.get(R.string.not_now), (d, w) -> {
                    WatchProviders.decide(p, false);
                    next.run();
                })
                .setOnCancelListener(d -> {
                    askedThisRun.add(p.pkg);   // Back records nothing
                    next.run();
                })
                .show();
    }

    /** The providers' answers as chips after the IPTV one, and, when no streaming app has a link, as the Watch button. */
    private void showProviders(Map<WatchProviders.Provider, List<WatchProviders.Item>> res) {
        for (View v : providerChips) whereLine.removeView(v);
        providerChips.clear();
        if (providerWatchButton != null) actions.removeView(providerWatchButton);
        if (providerStartOver != null) actions.removeView(providerStartOver);
        providerWatchButton = null;
        providerStartOver = null;
        firstProviderItem = null;
        int at = iptvChip != null ? 1 : 0;
        WatchProviders.Item first = null;
        for (Map.Entry<WatchProviders.Provider, List<WatchProviders.Item>> e : res.entrySet()) {
            WatchProviders.Provider p = e.getKey();
            for (WatchProviders.Item item : e.getValue()) {
                if (first == null) first = item;
                ImageView img = new ImageView(this);
                try {
                    img.setImageDrawable(getPackageManager().getApplicationIcon(p.pkg));
                } catch (PackageManager.NameNotFoundException ignored) {
                }
                List<String> parts = new ArrayList<>();
                if (!item.label.isEmpty()) parts.add(item.label);
                if (!item.detail.isEmpty()) parts.add(item.detail);
                String resume = WatchProviders.resumeText(item);
                if (resume != null) parts.add(resume);
                String sub = TextUtils.join("  ·  ", parts);
                View chip = chipBox(img, p.label, chipSub(sub.isEmpty() ? Strings.get(R.string.opens_prefix) + p.appLabel : sub));
                chip.setOnClickListener(v -> openProviderItem(item));
                whereLine.addView(chip, at++);
                providerChips.add(chip);
            }
        }
        firstProviderItem = first;
        if (noneText != null) noneText.setVisibility(providerChips.isEmpty() ? View.VISIBLE : View.GONE);
        whereLine.setVisibility(whereLine.getChildCount() == 0 ? View.GONE : View.VISIBLE);
        if (first != null && !streamingWatch && actions != null) {
            WatchProviders.Item item = first;
            providerWatchButton = TvUi.action(this, k, R.drawable.ic_play, Strings.get(R.string.watch_on_prefix) + item.provider.label, v -> openProviderItem(item));
            boolean continuing = progress != null && primaryButton != null;
            actions.addView(providerWatchButton, continuing ? 1 : 0);
            if (progress != null && progress.t > 30) {
                // CleanYourTV can't seek and never writes tk:watching: the app decides where to start.
                providerStartOver = TvUi.action(this, k, R.drawable.ic_replay, Strings.get(R.string.start_over), v -> openProviderItem(item));
                actions.addView(providerStartOver, continuing ? 2 : 1);
            }
            if (!keyPressed && getCurrentFocus() == initialFocus) {
                providerWatchButton.requestFocus();
                initialFocus = providerWatchButton;
            }
        }
    }

    private void openProviderItem(WatchProviders.Item item) {
        if (title != null) Tmdb.setSeen(this, title, true);
        WatchProviders.open(this, item);
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
        if (stopped && whereLine != null && !Prefs.iptvPlayer().equals(iptvShown)) showIptvChip();   // back from Settings › Services
        if (stopped && baseRequest != null && WatchProviders.stamp() != providerStamp) askProviders(false);   // an app was switched on, off or moved
        stopped = false;
    }
}
