package io.cleanyourtv.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A movie's or a show's page: what it is (rating, certification, director or creators), where it streams in the
 * region, what to do with it (watch, My list, seen, favorite, rate, add to a list, trailer), cast, seasons,
 * collection, keywords, reviews and similar titles.
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

    private final Handler main = new Handler(Looper.getMainLooper());
    private float k;
    private String kind;
    private int id;
    private Tmdb.Title title;
    private LinearLayout content;
    private TextView listButton, seenButton, favButton, rateButton;
    private boolean inList, favorite;
    private int myRating;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        k = TvUi.k(this);
        kind = getIntent().getStringExtra(KIND);
        id = getIntent().getIntExtra(ID, 0);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(TvUi.BG);
        ImageView backdrop = new ImageView(this);
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        backdrop.setAlpha(0.55f);
        root.addView(backdrop, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[] {0xF20B0D12, 0xB30B0D12, 0x330B0D12}));
        root.addView(shade, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        TvUi.Scroll scroll = new TvUi.Scroll(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        content.setPadding(Math.round(96 * k), Math.round(90 * k), 0, Math.round(80 * k));
        content.setClipToPadding(false);
        TextView loading = TvUi.text(this, k, 30, Ui.regular, TvUi.DIM);
        loading.setText(Strings.get(R.string.loading));
        content.addView(loading);
        scroll.addView(content);
        root.addView(scroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        new Thread(() -> {
            try {
                boolean movie = "movie".equals(kind);
                String extra = movie ? "release_dates,credits" : "content_ratings,aggregate_credits";
                JSONObject o = Tmdb.get("/" + kind + "/" + id + "?append_to_response=watch/providers,recommendations,videos,reviews,keywords," + extra);
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
                boolean saved = Tmdb.signedIn(this) ? states.optBoolean("watchlist") : Tmdb.inWatchlist(this, kind, id);
                JSONObject col = collection;
                JSONArray rev = reviews;
                main.post(() -> {
                    if (isFinishing()) return;
                    inList = saved;
                    favorite = states.optBoolean("favorite");
                    myRating = Tmdb.ratingOf(states);
                    String b = o.optString("backdrop_path");
                    Images.load(backdrop, b.isEmpty() || "null".equals(b) ? null : Tmdb.BACKDROP + b);
                    show(o, col, rev);
                });
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

        TextView h = TvUi.text(this, k, 72, Ui.displaySemibold, TvUi.INK);
        h.setText(name);
        h.setMaxWidth(Math.round(1300 * k));
        content.addView(h);

        List<String> meta = new ArrayList<>();
        String cert = certification(o);
        if (!cert.isEmpty()) meta.add(cert);
        meta.add(movie ? Strings.get(R.string.movie) : Strings.get(R.string.show));
        if (!year.isEmpty()) meta.add(year);
        JSONArray genres = o.optJSONArray("genres");
        for (int i = 0; genres != null && i < genres.length() && i < 3; i++) meta.add(genres.optJSONObject(i).optString("name"));
        int runtime = o.optInt("runtime");
        if (movie && runtime > 0) meta.add(Strings.duration(runtime));
        int seasons = o.optInt("number_of_seasons");
        if (!movie && seasons > 0) meta.add(Strings.quantity(R.plurals.seasons, seasons));
        double vote = o.optDouble("vote_average");
        if (vote > 0) meta.add(String.format(Ui.PT, "★ %.1f (%d)", vote, o.optInt("vote_count")));
        TextView m = TvUi.text(this, k, 28, Ui.medium, TvUi.DIM);
        m.setText(TextUtils.join("  ·  ", meta));
        m.setPadding(0, Math.round(18 * k), 0, 0);
        content.addView(m);

        String people = people(o, movie);
        if (!people.isEmpty()) {
            TextView p = TvUi.text(this, k, 25, Ui.regular, TvUi.DIM);
            p.setText(people);
            p.setPadding(0, Math.round(10 * k), 0, 0);
            content.addView(p);
        }
        String status = status(o, movie);
        if (!status.isEmpty()) {
            TextView st = TvUi.text(this, k, 25, Ui.medium, TvUi.ACCENT);
            st.setText(status);
            st.setPadding(0, Math.round(10 * k), 0, 0);
            content.addView(st);
        }
        String tagline = o.optString("tagline");
        if (!tagline.isEmpty()) {
            TextView t = TvUi.text(this, k, 28, Ui.medium, TvUi.INK);
            t.setText(tagline);
            t.setPadding(0, Math.round(22 * k), 0, 0);
            content.addView(t);
        }
        String overview = o.optString("overview");
        if (!overview.isEmpty()) {
            TextView ov = TvUi.text(this, k, 27, Ui.regular, TvUi.DIM);
            ov.setText(overview);
            ov.setMaxWidth(Math.round(1150 * k));
            ov.setMaxLines(5);
            ov.setEllipsize(TextUtils.TruncateAt.END);
            ov.setLineSpacing(8 * k, 1);
            ov.setPadding(0, Math.round(16 * k), 0, 0);
            content.addView(ov);
        }

        JSONObject region = o.optJSONObject("watch/providers") == null ? null
                : o.optJSONObject("watch/providers").optJSONObject("results") == null ? null
                : o.optJSONObject("watch/providers").optJSONObject("results").optJSONObject(Tmdb.region());
        String[] watchApp = firstApp(region);
        TvUi.Flow actions = new TvUi.Flow(this, Math.round(20 * k), Math.round(20 * k));
        actions.setPadding(Math.round(14 * k), Math.round(36 * k), Math.round(96 * k), Math.round(16 * k));
        if (watchApp != null) {
            actions.addView((TvUi.button(this, k, "▶  " + Strings.get(R.string.watch_on_prefix) + watchApp[1], v -> watch(watchApp[0]))));
        }
        listButton = TvUi.button(this, k, "", v -> toggleList());
        actions.addView((listButton));
        seenButton = TvUi.button(this, k, "", v -> toggleSeen());
        actions.addView((seenButton));
        boolean account = Tmdb.signedIn(this);
        if (account) {
            favButton = TvUi.button(this, k, "", v -> toggleFavorite());
            actions.addView((favButton));
            rateButton = TvUi.button(this, k, "", v -> rate());
            actions.addView((rateButton));
            if (movie) actions.addView((TvUi.button(this, k, "≡  " + Strings.get(R.string.list), v -> addToList())));
        }
        String trailer = trailer(o);
        if (trailer != null) actions.addView((TvUi.button(this, k, Strings.get(R.string.trailer), v -> playTrailer(trailer))));
        content.addView(actions, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        labels();

        content.addView(header(Strings.get(R.string.where_to_watch)));
        content.addView(whereToWatch(region), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TvUi.OnTitle open = t -> DetailActivity.open(this, t.kind, t.id);
        if (!movie) {
            List<Tmdb.Title> seasonCards = seasons(o);
            if (!seasonCards.isEmpty()) {
                TvUi.OnTitle season = t -> SeasonActivity.open(this, id, t.id, name);
                content.addView(TvUi.row(this, k, Strings.get(R.string.seasons), seasonCards, season,
                        () -> GridActivity.show(this, name + " · " + Strings.get(R.string.seasons), seasonCards, season), Math.round(96 * k)));
            }
        }
        List<Tmdb.Title> cast = cast(o, movie);
        if (!cast.isEmpty()) content.addView(TvUi.row(this, k, Strings.get(R.string.cast), cast, open,
                () -> GridActivity.show(this, name + " · " + Strings.get(R.string.cast), cast, open), Math.round(96 * k)));
        if (collection != null) {
            List<Tmdb.Title> parts = Tmdb.titles(collection, "parts", "movie");
            parts.sort((a, b) -> a.year.compareTo(b.year));
            if (parts.size() > 1) content.addView(TvUi.row(this, k, collection.optString("name"), parts, open, null, Math.round(96 * k)));
        }
        View keywords = keywords(o, movie);
        if (keywords != null) {
            content.addView(header(Strings.get(R.string.keywords)));
            content.addView(keywords, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        if (reviews != null && reviews.length() > 0) {
            content.addView(header(Strings.get(R.string.reviews)));
            for (int i = 0; i < reviews.length() && i < 2; i++) content.addView(review(reviews.optJSONObject(i)));
        }
        JSONObject recs = o.optJSONObject("recommendations");
        List<Tmdb.Title> similar = recs == null ? new ArrayList<>() : Tmdb.titles(recs, kind);
        if (!similar.isEmpty()) content.addView(TvUi.row(this, k, Strings.get(R.string.you_may_also_like), similar, open,
                () -> GridActivity.open(this, Strings.get(R.string.like_prefix) + name, "/" + kind + "/" + id + "/recommendations", kind), Math.round(96 * k)));
        View first = actions.getChildAt(0);
        if (first != null) first.requestFocus();
        new Thread(() -> {
            java.util.Map<String, String> links = DeepLinks.find(this, kind, id, name);
            main.post(() -> { if (!isFinishing()) linksKnown(links); });
        }).start();
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

    private static String people(JSONObject o, boolean movie) {
        List<String> names = new ArrayList<>();
        if (movie) {
            JSONArray crew = o.optJSONObject("credits") == null ? null : o.optJSONObject("credits").optJSONArray("crew");
            for (int i = 0; crew != null && i < crew.length(); i++) {
                JSONObject c = crew.optJSONObject(i);
                if ("Director".equals(c.optString("job")) && !names.contains(c.optString("name"))) names.add(c.optString("name"));
            }
            return names.isEmpty() ? "" : Strings.get(R.string.directed_by_prefix) + TextUtils.join(", ", names);
        }
        JSONArray created = o.optJSONArray("created_by");
        for (int i = 0; created != null && i < created.length(); i++) names.add(created.optJSONObject(i).optString("name"));
        JSONArray networks = o.optJSONArray("networks");
        String net = networks != null && networks.length() > 0 ? networks.optJSONObject(0).optString("name") : "";
        String who = names.isEmpty() ? "" : Strings.get(R.string.created_by_prefix) + TextUtils.join(", ", names);
        return net.isEmpty() ? who : who.isEmpty() ? net : who + "  ·  " + net;
    }

    /** For a show still running: the next episode's date, or that it ended. */
    private static String status(JSONObject o, boolean movie) {
        if (movie) return "";
        JSONObject next = o.optJSONObject("next_episode_to_air");
        if (next != null) {
            String d = next.optString("air_date");
            String when = d.length() == 10 ? d.substring(8) + "/" + d.substring(5, 7) : d;
            return Strings.get(R.string.next_episode_prefix) + "T" + next.optInt("season_number") + "E" + next.optInt("episode_number") + " · " + when;
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
        line.setPadding(Math.round(14 * k), Math.round(14 * k), Math.round(96 * k), Math.round(14 * k));
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
        box.setPadding(Math.round(28 * k), Math.round(22 * k), Math.round(28 * k), Math.round(22 * k));
        JSONObject details = r.optJSONObject("author_details");
        double rating = details == null ? 0 : details.optDouble("rating", 0);
        TextView who = TvUi.text(this, k, 25, Ui.medium, TvUi.INK);
        who.setText(r.optString("author") + (rating > 0 ? String.format(Ui.PT, "  ·  ★ %.0f", rating) : ""));
        TextView body = TvUi.text(this, k, 23, Ui.regular, TvUi.DIM);
        body.setText(r.optString("content").replaceAll("\\s+", " "));
        body.setMaxLines(4);
        body.setEllipsize(TextUtils.TruncateAt.END);
        body.setPadding(0, Math.round(10 * k), 0, 0);
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
        t.setPadding(0, Math.round(40 * k), 0, Math.round(12 * k));
        return t;
    }

    private void labels() {
        listButton.setText(inList ? "✓  " + Strings.get(R.string.in_my_list) : "+  " + Strings.get(R.string.my_list));
        boolean seen = Tmdb.isSeen(this, kind, id);
        seenButton.setText(seen ? "✓  " + Strings.get(R.string.seen) : Strings.get(R.string.ive_seen_it));
        if (favButton != null) favButton.setText(favorite ? "♥  " + Strings.get(R.string.favorite) : "♡  " + Strings.get(R.string.mark_favorite));
        if (rateButton != null) rateButton.setText(myRating > 0 ? "★  " + Strings.get(R.string.your_rating_prefix) + myRating : "★  " + Strings.get(R.string.rate));
    }

    /** The first subscription or free service with an app on the TV: {package, service name}. */
    private String[] firstApp(JSONObject region) {
        if (region == null) return null;
        for (String type : new String[] {"flatrate", "free", "ads"}) {
            JSONArray list = region.optJSONArray(type);
            for (int i = 0; list != null && i < list.length(); i++) {
                JSONObject p = list.optJSONObject(i);
                String app = Providers.app(this, p.optInt("provider_id"), p.optString("provider_name"));
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
        whereLine.setPadding(0, Math.round(14 * k), Math.round(96 * k), Math.round(14 * k));
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
        s.setPadding(0, Math.round(6 * k), 0, 0);
        return s;
    }

    /** The shape every "Onde assistir" chip shares: a logo, the name, and a dim line under it. */
    private LinearLayout chipBox(ImageView img, String name, TextView sub) {
        LinearLayout chip = new LinearLayout(this);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(Math.round(16 * k), Math.round(16 * k), Math.round(30 * k), Math.round(16 * k));
        img.setClipToOutline(true);
        img.setBackground(TvUi.round(0x00000000, 12 * k));
        int size = Math.round(64 * k);
        chip.addView(img, new LinearLayout.LayoutParams(size, size));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(Math.round(18 * k), 0, 0, 0);
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
            main.post(() -> { if (!DeepLinks.open(this, pkg, url)) RemoteKeys.run(this, pkg); });
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
        Tmdb.setSeen(this, title, !Tmdb.isSeen(this, kind, id));
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
        String[] items = new String[myRating > 0 ? 11 : 10];
        for (int i = 0; i < 10; i++) items[i] = "★ " + (10 - i);
        if (myRating > 0) items[10] = Strings.get(R.string.remove_my_rating);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(Strings.get(R.string.your_rating_for_prefix) + title.name)
                .setItems(items, (d, which) -> background(Strings.get(R.string.couldnt_save_the_rating_prefix), () -> {
                    if (which == 10) {
                        Tmdb.unrate(this, title);
                        myRating = 0;
                    } else {
                        Tmdb.rate(this, title, 10 - which);
                        Tmdb.setSeen(this, title, true);
                        myRating = 10 - which;
                    }
                }))
                .show();
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
        stopped = false;
    }
}
