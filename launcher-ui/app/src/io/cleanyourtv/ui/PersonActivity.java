package io.cleanyourtv.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** A person's page: photo, what they do, biography, and their movies and shows, the best known first. */
public class PersonActivity extends Activity {
    private static final String ID = "id";

    static void open(Context c, int id) {
        Intent i = new Intent(c, PersonActivity.class).putExtra(ID, id);
        if (!(c instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(i);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private float k;
    private LinearLayout content;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        k = TvUi.k(this);
        int id = getIntent().getIntExtra(ID, 0);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(TvUi.BG);
        TvUi.Scroll scroll = new TvUi.Scroll(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        content.setPaddingRelative(Math.round(96 * k), Math.round(80 * k), 0, Math.round(80 * k));
        content.setClipToPadding(false);
        TextView loading = TvUi.text(this, k, 30, Ui.regular, TvUi.DIM);
        loading.setText(Ui.tr("Carregando…", "Loading…"));
        content.addView(loading);
        scroll.addView(content);
        root.addView(scroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        new Thread(() -> {
            try {
                JSONObject o = Tmdb.get("/person/" + id + "?append_to_response=combined_credits");
                String bio = o.optString("biography");
                if (bio.isEmpty()) bio = Tmdb.get("/person/" + id + "?language=en-US").optString("biography");
                String biography = bio;
                main.post(() -> { if (!isFinishing()) show(o, biography); });
            } catch (Exception e) {
                main.post(() -> loading.setText(Ui.tr("Não deu para abrir esta pessoa: ", "Couldn't open this person: ") + e.getMessage()));
            }
        }).start();
    }

    private void show(JSONObject o, String biography) {
        content.removeAllViews();
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        ImageView photo = new ImageView(this);
        photo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        photo.setBackground(TvUi.round(TvUi.CARD, 20 * k));
        photo.setClipToOutline(true);
        String face = o.optString("profile_path");
        Images.load(photo, face.isEmpty() || "null".equals(face) ? null : Tmdb.POSTER + face);
        top.addView(photo, new LinearLayout.LayoutParams(Math.round(300 * k), Math.round(450 * k)));
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPaddingRelative(Math.round(56 * k), 0, Math.round(96 * k), 0);
        TextView name = TvUi.text(this, k, 68, Ui.displaySemibold, TvUi.INK);
        name.setText(o.optString("name"));
        text.addView(name);
        List<String> meta = new ArrayList<>();
        String dept = o.optString("known_for_department");
        if (!dept.isEmpty()) meta.add(department(dept));
        String birth = o.optString("birthday");
        if (birth.length() >= 4 && !"null".equals(birth)) meta.add(Ui.tr("Nascimento: ", "Born: ") + date(birth));
        String place = o.optString("place_of_birth");
        if (!place.isEmpty() && !"null".equals(place)) meta.add(place);
        String death = o.optString("deathday");
        if (death.length() >= 4 && !"null".equals(death)) meta.add(Ui.tr("Morte: ", "Died: ") + date(death));
        TextView m = TvUi.text(this, k, 27, Ui.medium, TvUi.DIM);
        m.setText(TextUtils.join("  ·  ", meta));
        m.setPaddingRelative(0, Math.round(16 * k), 0, Math.round(20 * k));
        text.addView(m);
        TextView bio = TvUi.text(this, k, 26, Ui.regular, TvUi.DIM);
        bio.setText(biography.isEmpty() ? Ui.tr("Sem biografia no TMDB.", "No biography on TMDB.") : biography);
        bio.setMaxLines(9);
        bio.setEllipsize(TextUtils.TruncateAt.END);
        bio.setLineSpacing(8 * k, 1);
        text.addView(bio);
        top.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        content.addView(top);

        JSONObject credits = o.optJSONObject("combined_credits");
        List<Tmdb.Title> movies = new ArrayList<>(), shows = new ArrayList<>(), crew = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        collect(credits == null ? null : credits.optJSONArray("cast"), movies, shows, seen);
        collect(credits == null ? null : credits.optJSONArray("crew"), crew, crew, seen);
        TvUi.OnTitle open = t -> DetailActivity.open(this, t.kind, t.id);
        if (!movies.isEmpty()) content.addView(TvUi.row(this, k, Ui.tr("Filmes", "Movies"), movies, open, null, Math.round(96 * k)));
        if (!shows.isEmpty()) content.addView(TvUi.row(this, k, Ui.tr("Séries", "Shows"), shows, open, null, Math.round(96 * k)));
        if (!crew.isEmpty()) content.addView(TvUi.row(this, k, Ui.tr("Por trás das câmeras", "Behind the camera"), crew, open, null, Math.round(96 * k)));
        View first = content.findFocus();
        if (first == null && content.getChildCount() > 1) content.getChildAt(1).requestFocus();
    }

    /** Credits as titles, most popular first, each title once. */
    private static void collect(JSONArray list, List<Tmdb.Title> movies, List<Tmdb.Title> shows, Set<String> seen) {
        if (list == null) return;
        List<JSONObject> all = new ArrayList<>();
        for (int i = 0; i < list.length(); i++) all.add(list.optJSONObject(i));
        all.sort((a, b) -> Double.compare(b.optDouble("popularity"), a.optDouble("popularity")));
        for (JSONObject o : all) {
            String kind = o.optString("media_type");
            String poster = o.optString("poster_path");
            if (poster.isEmpty() || "null".equals(poster) || !seen.add(kind + ":" + o.optInt("id"))) continue;
            boolean movie = kind.equals("movie");
            String date = o.optString(movie ? "release_date" : "first_air_date");
            Tmdb.Title t = new Tmdb.Title(kind, o.optInt("id"), o.optString(movie ? "title" : "name"), poster, "",
                    date.length() >= 4 ? date.substring(0, 4) : "", o.optDouble("vote_average"));
            (movie ? movies : shows).add(t);
            if (movies.size() + shows.size() > 80) break;
        }
    }

    private static String department(String d) {
        switch (d) {
            case "Acting": return Ui.tr("Atuação", "Acting");
            case "Directing": return Ui.tr("Direção", "Directing");
            case "Writing": return Ui.tr("Roteiro", "Writing");
            case "Production": return Ui.tr("Produção", "Production");
            default: return d;
        }
    }

    private static String date(String iso) {
        String[] p = iso.split("-");
        return p.length == 3 ? p[2] + "/" + p[1] + "/" + p[0] : iso;
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
