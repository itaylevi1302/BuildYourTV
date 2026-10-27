package io.cleanyourtv.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
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
        content.setPaddingRelative(Math.round(96 * k), Math.round(90 * k), 0, Math.round(80 * k));
        content.setClipToPadding(false);
        TextView loading = TvUi.text(this, k, 30, Ui.regular, TvUi.DIM);
        loading.setText(Ui.tr("Carregando…", "Loading…"));
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
                main.post(() -> loading.setText(Ui.tr("Não deu para abrir este título: ", "Couldn't open this title: ") + e.getMessage()));
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
        meta.add(movie ? Ui.tr("Filme", "Movie") : Ui.tr("Série", "Show"));
        if (!year.isEmpty()) meta.add(year);
        JSONArray genres = o.optJSONArray("genres");
        for (int i = 0; genres != null && i < genres.length() && i < 3; i++) meta.add(genres.optJSONObject(i).optString("name"));
        int runtime = o.optInt("runtime");
        if (movie && runtime > 0 && Ui.he) meta.add(runtime >= 60 ? (runtime / 60) + " שע' " + (runtime % 60) + " דק'" : runtime + " דק'");
        else if (movie && runtime > 0) meta.add(runtime >= 60 ? (runtime / 60) + " h " + (runtime % 60) + " min" : runtime + " min");
        int seasons = o.optInt("number_of_seasons");
        if (!movie && seasons > 0) meta.add(seasons == 1 ? Ui.tr("1 temporada", "1 season") : seasons + Ui.tr(" temporadas", " seasons"));
        double vote = o.optDouble("vote_average");
        if (vote > 0) meta.add(String.format(Ui.PT, "★ %.1f (%d)", vote, o.optInt("vote_count")));
        TextView m = TvUi.text(this, k, 28, Ui.medium, TvUi.DIM);
        m.setText(TextUtils.join("  ·  ", meta));
        m.setPaddingRelative(0, Math.round(18 * k), 0, 0);
        content.addView(m);

        String people = people(o, movie);
        if (!people.isEmpty()) {
            TextView p = TvUi.text(this, k, 25, Ui.regular, TvUi.DIM);
            p.setText(people);
            p.setPaddingRelative(0, Math.round(10 * k), 0, 0);
            content.addView(p);
        }
        String status = status(o, movie);
        if (!status.isEmpty()) {
            TextView st = TvUi.text(this, k, 25, Ui.medium, TvUi.ACCENT);
            st.setText(status);
            st.setPaddingRelative(0, Math.round(10 * k), 0, 0);
            content.addView(st);
        }
        String tagline = o.optString("tagline");
        if (!tagline.isEmpty()) {
            TextView t = TvUi.text(this, k, 28, Ui.medium, TvUi.INK);
            t.setText(tagline);
            t.setPaddingRelative(0, Math.round(22 * k), 0, 0);
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
            ov.setPaddingRelative(0, Math.round(16 * k), 0, 0);
            content.addView(ov);
        }

        JSONObject region = o.optJSONObject("watch/providers") == null ? null
                : o.optJSONObject("watch/providers").optJSONObject("results") == null ? null
                : o.optJSONObject("watch/providers").optJSONObject("results").optJSONObject(Tmdb.region());
        String[] watchApp = firstApp(region);
        TvUi.Flow actions = new TvUi.Flow(this, Math.round(20 * k), Math.round(20 * k));
        actions.setPaddingRelative(Math.round(14 * k), Math.round(36 * k), Math.round(96 * k), Math.round(16 * k));
        if (watchApp != null) {
            actions.addView((TvUi.button(this, k, "▶  " + Ui.tr("Assistir em ", "Watch on ") + watchApp[1], v -> watch(watchApp[0]))));
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
            if (movie) actions.addView((TvUi.button(this, k, "≡  " + Ui.tr("Lista", "List"), v -> addToList())));
        }
        String trailer = trailer(o);
        if (trailer != null) actions.addView((TvUi.button(this, k, Ui.tr("Trailer", "Trailer"), v -> playTrailer(trailer))));
        content.addView(actions, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        labels();

        content.addView(header(Ui.tr("Onde assistir", "Where to watch")));
        content.addView(whereToWatch(region), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TvUi.OnTitle open = t -> DetailActivity.open(this, t.kind, t.id);
        if (!movie) {
            List<Tmdb.Title> seasonCards = seasons(o);
            if (!seasonCards.isEmpty()) {
                TvUi.OnTitle season = t -> SeasonActivity.open(this, id, t.id, name);
                content.addView(TvUi.row(this, k, Ui.tr("Temporadas", "Seasons"), seasonCards, season,
                        () -> GridActivity.show(this, name + " · " + Ui.tr("Temporadas", "Seasons"), seasonCards, season), Math.round(96 * k)));
            }
        }
        List<Tmdb.Title> cast = cast(o, movie);
        if (!cast.isEmpty()) content.addView(TvUi.row(this, k, Ui.tr("Elenco", "Cast"), cast, open,
                () -> GridActivity.show(this, name + " · " + Ui.tr("Elenco", "Cast"), cast, open), Math.round(96 * k)));
        if (collection != null) {
            List<Tmdb.Title> parts = Tmdb.titles(collection, "parts", "movie");
            parts.sort((a, b) -> a.year.compareTo(b.year));
            if (parts.size() > 1) content.addView(TvUi.row(this, k, collection.optString("name"), parts, open, null, Math.round(96 * k)));
        }
        View keywords = keywords(o, movie);
        if (keywords != null) {
            content.addView(header(Ui.tr("Palavras-chave", "Keywords")));
            content.addView(keywords, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        if (reviews != null && reviews.length() > 0) {
            content.addView(header(Ui.tr("Resenhas", "Reviews")));
            for (int i = 0; i < reviews.length() && i < 2; i++) content.addView(review(reviews.optJSONObject(i)));
        }
        JSONObject recs = o.optJSONObject("recommendations");
        List<Tmdb.Title> similar = recs == null ? new ArrayList<>() : Tmdb.titles(recs, kind);
        if (!similar.isEmpty()) content.addView(TvUi.row(this, k, Ui.tr("Você também pode gostar", "You may also like"), similar, open,
                () -> GridActivity.open(this, Ui.tr("Parecidos com ", "Like ") + name, "/" + kind + "/" + id + "/recommendations", kind), Math.round(96 * k)));
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
                        if (!c.isEmpty()) return c.equals("L") ? Ui.tr("Livre", "All ages") : c + "+";
                    }
                }
            } else {
                JSONArray results = o.getJSONObject("content_ratings").getJSONArray("results");
                for (int i = 0; i < results.length(); i++) {
                    JSONObject r = results.getJSONObject(i);
                    if (Tmdb.region().equals(r.optString("iso_3166_1"))) {
                        String c = r.optString("rating");
                        return c.equals("L") ? Ui.tr("Livre", "All ages") : c.isEmpty() ? "" : c + "+";
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
            return names.isEmpty() ? "" : Ui.tr("Direção: ", "Directed by ") + TextUtils.join(", ", names);
        }
        JSONArray created = o.optJSONArray("created_by");
        for (int i = 0; created != null && i < created.length(); i++) names.add(created.optJSONObject(i).optString("name"));
        JSONArray networks = o.optJSONArray("networks");
        String net = networks != null && networks.length() > 0 ? networks.optJSONObject(0).optString("name") : "";
        String who = names.isEmpty() ? "" : Ui.tr("Criação: ", "Created by ") + TextUtils.join(", ", names);
        return net.isEmpty() ? who : who.isEmpty() ? net : who + "  ·  " + net;
    }

    /** For a show still running: the next episode's date, or that it ended. */
    private static String status(JSONObject o, boolean movie) {
        if (movie) return "";
        JSONObject next = o.optJSONObject("next_episode_to_air");
        if (next != null) {
            String d = next.optString("air_date");
            String when = d.length() == 10 ? d.substring(8) + "/" + d.substring(5, 7) : d;
            return Ui.tr("Próximo episódio: ", "Next episode: ") + "T" + next.optInt("season_number") + "E" + next.optInt("episode_number") + " · " + when;
        }
        String s = o.optString("status");
        if (s.equals("Ended")) return Ui.tr("Série encerrada", "Ended");
        if (s.equals("Canceled")) return Ui.tr("Série cancelada", "Canceled");
        return "";
    }

    private static List<Tmdb.Title> seasons(JSONObject o) {
        List<Tmdb.Title> out = new ArrayList<>();
        JSONArray list = o.optJSONArray("seasons");
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject s = list.optJSONObject(i);
            int n = s.optInt("season_number");
            String label = n == 0 ? Ui.tr("Especiais", "Specials") : Ui.tr("Temporada ", "Season ") + n;
            String poster = s.optString("poster_path");
            out.add(new Tmdb.Title("season", n, label + " · " + s.optInt("episode_count") + Ui.tr(" ep.", " ep."),
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
            line.addView(TvUi.button(this, k, word, v -> GridActivity.open(this, Ui.tr("Com o tema “", "Tagged “") + word + "”",
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
        listButton.setText(inList ? "✓  " + Ui.tr("Na minha lista", "In my list") : "+  " + Ui.tr("Minha lista", "My list"));
        boolean seen = Tmdb.isSeen(this, kind, id);
        seenButton.setText(seen ? "✓  " + Ui.tr("Assistido", "Seen") : Ui.tr("Já assisti", "I've seen it"));
        if (favButton != null) favButton.setText(favorite ? "♥  " + Ui.tr("Favorito", "Favorite") : "♡  " + Ui.tr("Favoritar", "Favorite"));
        if (rateButton != null) rateButton.setText(myRating > 0 ? "★  " + Ui.tr("Sua nota: ", "Your rating: ") + myRating : "★  " + Ui.tr("Avaliar", "Rate"));
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

    private View whereToWatch(JSONObject region) {
        TvUi.Flow line = new TvUi.Flow(this, Math.round(18 * k), Math.round(18 * k));
        line.setPaddingRelative(0, Math.round(14 * k), Math.round(96 * k), Math.round(14 * k));
        String[][] types = {{"flatrate", Ui.tr("Assinatura", "Subscription")}, {"free", Ui.tr("Grátis", "Free")},
                {"ads", Ui.tr("Grátis com anúncios", "Free with ads")}, {"rent", Ui.tr("Aluguel", "Rent")}, {"buy", Ui.tr("Compra", "Buy")}};
        Set<String> added = new HashSet<>();
        for (String[] type : types) {
            JSONArray list = region == null ? null : region.optJSONArray(type[0]);
            for (int i = 0; list != null && i < list.length(); i++) {
                JSONObject p = list.optJSONObject(i);
                int pid = p.optInt("provider_id");
                if (!added.add(pid + type[0])) continue;
                line.addView(chip(pid, p.optString("provider_name"), p.optString("logo_path"), type[1]));
            }
        }
        if (line.getChildCount() == 0) {
            TextView none = TvUi.text(this, k, 26, Ui.regular, TvUi.DIM);
            none.setText(Ui.tr("Não está em nenhum serviço de streaming no Brasil agora.", "Not on any streaming service in the region right now."));
            return none;
        }
        return line;
    }

    private View chip(int pid, String name, String logo, String type) {
        String app = Providers.app(this, pid, name);
        LinearLayout chip = new LinearLayout(this);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPaddingRelative(Math.round(16 * k), Math.round(16 * k), Math.round(30 * k), Math.round(16 * k));
        ImageView img = new ImageView(this);
        img.setClipToOutline(true);
        img.setBackground(TvUi.round(0x00000000, 12 * k));
        Images.load(img, logo.isEmpty() ? null : Tmdb.LOGO + logo);
        int size = Math.round(64 * k);
        chip.addView(img, new LinearLayout.LayoutParams(size, size));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPaddingRelative(Math.round(18 * k), 0, 0, 0);
        TextView n = TvUi.text(this, k, 25, Ui.medium, TvUi.INK);
        n.setText(name);
        TextView s = TvUi.text(this, k, 21, Ui.regular, TvUi.DIM);
        s.setText(type + "  ·  " + (app != null ? Ui.tr("abre ", "opens ") + Providers.label(this, app) : Ui.tr("sem app na TV", "no app on the TV")));
        if (app != null) chipNotes.add(new Object[] {app, s, type});
        s.setPaddingRelative(0, Math.round(6 * k), 0, 0);
        texts.addView(n);
        texts.addView(s);
        chip.addView(texts);
        TvUi.softFocus(chip, k, 18 * k);
        chip.setOnClickListener(v -> {
            if (app != null) watch(app);
            else Toast.makeText(this, Ui.tr("Nenhum app para " + name + ". Escolha um em Ajustes › Serviços.",
                    "No app for " + name + ". Pick one in Settings › Services."), Toast.LENGTH_LONG).show();
        });
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
            if (links.containsKey(app)) ((TextView) n[1]).setText(n[2] + "  ·  " + Ui.tr("abre o título em ", "opens the title in ") + Providers.label(this, app));
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
        background(Ui.tr("Não deu para salvar: ", "Couldn't save: "), () -> {
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
        background(Ui.tr("Não deu para favoritar: ", "Couldn't favorite: "), () -> {
            Tmdb.setFavorite(this, title, want);
            favorite = want;
        });
    }

    private void rate() {
        String[] items = new String[myRating > 0 ? 11 : 10];
        for (int i = 0; i < 10; i++) items[i] = "★ " + (10 - i);
        if (myRating > 0) items[10] = Ui.tr("Remover minha nota", "Remove my rating");
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(Ui.tr("Sua nota para ", "Your rating for ") + title.name)
                .setItems(items, (d, which) -> background(Ui.tr("Não deu para salvar a nota: ", "Couldn't save the rating: "), () -> {
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
        new Thread(() -> {
            try {
                List<String[]> lists = Tmdb.lists(this);
                main.post(() -> pickList(lists));
            } catch (Exception e) {
                main.post(() -> Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void pickList(List<String[]> lists) {
        String[] items = new String[lists.size() + 1];
        for (int i = 0; i < lists.size(); i++) items[i] = lists.get(i)[1] + "  (" + lists.get(i)[2] + ")";
        items[lists.size()] = "+  " + Ui.tr("Nova lista…", "New list…");
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(Ui.tr("Adicionar a uma lista", "Add to a list"))
                .setItems(items, (d, which) -> {
                    if (which < lists.size()) {
                        String list = lists.get(which)[0], name = lists.get(which)[1];
                        background(Ui.tr("Não deu para adicionar: ", "Couldn't add: "), () -> {
                            Tmdb.addToList(this, list, id);
                            main.post(() -> Toast.makeText(this, Ui.tr("Adicionado a ", "Added to ") + name, Toast.LENGTH_SHORT).show());
                        });
                        return;
                    }
                    EditText field = new EditText(this);
                    field.setSingleLine(true);
                    field.setHint(Ui.tr("Nome da lista", "List name"));
                    new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                            .setTitle(Ui.tr("Nova lista", "New list"))
                            .setView(field)
                            .setPositiveButton(Ui.tr("Criar", "Create"), (d2, w2) -> background(Ui.tr("Não deu para criar: ", "Couldn't create: "), () -> {
                                String list = Tmdb.createList(this, field.getText().toString().trim());
                                Tmdb.addToList(this, list, id);
                                main.post(() -> Toast.makeText(this, Ui.tr("Lista criada", "List created"), Toast.LENGTH_SHORT).show());
                            }))
                            .setNegativeButton(Ui.tr("Cancelar", "Cancel"), null)
                            .show();
                })
                .show();
    }

    private static String trailer(JSONObject o) {
        JSONArray videos = o.optJSONObject("videos") == null ? null : o.optJSONObject("videos").optJSONArray("results");
        for (int i = 0; videos != null && i < videos.length(); i++) {
            JSONObject v = videos.optJSONObject(i);
            if ("YouTube".equals(v.optString("site")) && "Trailer".equals(v.optString("type"))) return v.optString("key");
        }
        return null;
    }

    /** YouTube for TV in its own task (started as part of ours, it closes as soon as the video opens). */
    private void playTrailer(String key) {
        Intent youtube = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=" + key))
                .setPackage("com.google.android.youtube.tv")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        try {
            startActivity(youtube);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=" + key)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) {
                Toast.makeText(this, Ui.tr("O YouTube não está instalado.", "YouTube isn't installed."), Toast.LENGTH_SHORT).show();
            }
        }
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
