package io.cleanyourtv.ui;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The TMDB API for Explore, the title page and the account (watchlist, ratings). Answers are kept for ten minutes.
 * The account is TMDB's v3 session: a request token the person approves on their phone, then a session id kept in
 * the app's settings. Without an account, "My list" and "Seen" are kept on the TV.
 */
final class Tmdb {
    private Tmdb() {}

    static final String API = "https://api.themoviedb.org/3";
    static final String POSTER = "https://image.tmdb.org/t/p/w342", BACKDROP = "https://image.tmdb.org/t/p/w1280",
            LOGO = "https://image.tmdb.org/t/p/w92", PROFILE = "https://image.tmdb.org/t/p/w185";
    /** In memory: the answers of the last half hour, a few dozen; on disk (DISK_MAX): for as long as ttl() says. */
    private static final long TTL = 30 * 60_000L, DISK_MAX = 24L * 1024 * 1024;

    private static final Map<String, Object[]> CACHE = new LinkedHashMap<String, Object[]>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Object[]> e) { return size() > 60; }
    };
    private static java.io.File disk;

    /** The disk cache's folder (every process calls this; it only remembers the folder). */
    static synchronized void init(Context c) {
        if (disk != null) return;
        disk = new java.io.File(c.getApplicationContext().getCacheDir(), "tmdb");
        //noinspection ResultOfMethodCallIgnored
        disk.mkdirs();
        new Thread(Tmdb::prune, "tmdb-prune").start();
    }

    static boolean hasKey() { return !Prefs.tmdbKey().isEmpty(); }

    /** Country for "what streams where": config.json "tmdb_region", else the TV's own country, else US. */
    static String region() {
        String v = Prefs.tmdbRegion();
        if (v.isEmpty() || v.equals("AUTO")) v = Locale.getDefault().getCountry().toUpperCase(Locale.ROOT);
        return v.matches("[A-Z]{2}") ? v : "US";
    }

    /** TMDB's language for the app's: titles, overviews and logos in it when TMDB has them. */
    static String lang() {
        switch (Ui.language) {
            case "pt": return "pt-BR";
            case "es": return "es-ES";
            case "de": return "de-DE";
            case "ru": return "ru-RU";
            case "fr": return "fr-FR";
            case "tr": return "tr-TR";
            case "he": return "he-IL";
            default: return "en-US";
        }
    }

    /**
     * How long an answer stays good on disk: lists of what's trending or new for hours, a title's page for half a
     * day, what hardly changes (pictures, people, seasons, collections, the region's services) for a week.
     */
    private static long ttl(String path) {
        if (path.contains("session_id")) return 0;
        if (path.startsWith("/search/")) return 3600_000L;
        if (path.startsWith("/trending/") || path.startsWith("/discover/") || path.contains("/now_playing")
                || path.contains("/upcoming") || path.contains("/airing_today") || path.contains("/on_the_air")
                || path.contains("/recommendations")) return 3 * 3600_000L;
        if (path.contains("/images") || path.startsWith("/person/") || path.startsWith("/collection/")
                || path.contains("/season/") || path.startsWith("/watch/providers") || path.contains("/top_rated")) return 7 * 24 * 3600_000L;
        return 12 * 3600_000L;
    }

    /**
     * GET a v3 path ("/trending/all/day?…"); language is added when the path has none. From memory, else from disk
     * while fresh, else from TMDB (and kept). Without internet, an old copy on disk is better than nothing.
     */
    static JSONObject get(String path) throws IOException, JSONException {
        String full = path + (path.contains("language=") ? "" : (path.contains("?") ? "&" : "?") + "language=" + lang());
        long now = System.currentTimeMillis();
        synchronized (CACHE) {
            Object[] hit = CACHE.get(full);
            if (hit != null && now - (Long) hit[0] < TTL) return (JSONObject) hit[1];
        }
        long ttl = ttl(full);
        java.io.File f = disk == null || ttl == 0 ? null : new java.io.File(disk, Integer.toHexString(full.hashCode()) + "-" + full.length() + ".json");
        if (f != null && f.isFile() && now - f.lastModified() < ttl) {
            try {
                JSONObject o = new JSONObject(readAll(new java.io.FileInputStream(f)));
                synchronized (CACHE) { CACHE.put(full, new Object[] {now, o}); }
                return o;
            } catch (IOException | JSONException ignored) {
            }
        }
        String text;
        try {
            text = call("GET", full, null);
        } catch (IOException e) {
            if (f != null && f.isFile()) {
                try { return new JSONObject(readAll(new java.io.FileInputStream(f))); } catch (IOException | JSONException ignored) {}
            }
            throw e;
        }
        JSONObject o = new JSONObject(text);
        synchronized (CACHE) { CACHE.put(full, new Object[] {now, o}); }
        if (f != null) {
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) {
            }
        }
        return o;
    }

    /** The app left the screen: the parsed answers go (the disk keeps them). */
    static void trimMemory() {
        synchronized (CACHE) { CACHE.clear(); }
    }

    /** Files a week past their time go; then the oldest, until the folder fits in DISK_MAX. */
    private static void prune() {
        java.io.File[] files = disk.listFiles();
        if (files == null) return;
        long now = System.currentTimeMillis(), total = 0;
        java.util.List<java.io.File> kept = new java.util.ArrayList<>();
        for (java.io.File f : files) {
            //noinspection ResultOfMethodCallIgnored
            if (now - f.lastModified() > 14L * 24 * 3600_000) f.delete();
            else {
                kept.add(f);
                total += f.length();
            }
        }
        if (total <= DISK_MAX) return;
        kept.sort((a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (java.io.File f : kept) {
            if (total <= DISK_MAX * 3 / 4) break;
            total -= f.length();
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    static JSONObject post(String path, JSONObject body) throws IOException, JSONException {
        return new JSONObject(call("POST", path, body == null ? "{}" : body.toString()));
    }

    private static String call(String method, String path, String body) throws IOException {
        String key = Prefs.tmdbKey();
        if (key.isEmpty()) throw new IOException(Ui.tr("sem chave do TMDB", "no TMDB key"));
        boolean bearer = key.startsWith("eyJ");
        String url = API + path + (bearer ? "" : (path.contains("?") ? "&" : "?") + "api_key=" + URLEncoder.encode(key, "UTF-8"));
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        c.setRequestMethod(method);
        c.setRequestProperty("Accept", "application/json");
        if (bearer) c.setRequestProperty("Authorization", "Bearer " + key);
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json;charset=utf-8");
            try (OutputStream out = c.getOutputStream()) { out.write(body.getBytes(StandardCharsets.UTF_8)); }
        }
        int code = c.getResponseCode();
        InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
        String text = in == null ? "" : readAll(in);
        c.disconnect();
        if (code >= 400) throw new IOException("TMDB " + code + (text.isEmpty() ? "" : ": " + new JSONObjectSafe(text).message()));
        return text;
    }

    static String readAll(InputStream in) throws IOException {
        return new String(readBytes(in), StandardCharsets.UTF_8);
    }

    static byte[] readBytes(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toByteArray();
    }

    private static final class JSONObjectSafe {
        private final String text;
        JSONObjectSafe(String text) { this.text = text; }
        String message() {
            try { return new JSONObject(text).optString("status_message", ""); } catch (JSONException e) { return ""; }
        }
    }

    // MARK: - Titles

    /** One poster in a row: a movie or a show. */
    static final class Title {
        final String kind;   // "movie" or "tv"
        final int id;
        final String name, poster, backdrop, year;
        final double vote;
        String overview = "", lang = "", date = "";
        int[] genres = new int[0];

        Title(String kind, int id, String name, String poster, String backdrop, String year, double vote) {
            this.kind = kind;
            this.id = id;
            this.name = name;
            this.poster = poster;
            this.backdrop = backdrop;
            this.year = year;
            this.vote = vote;
        }

        String key() { return kind + ":" + id; }
    }

    /** The titles of a list answer; `kind` is used when the items don't say ("movie"/"tv" endpoints). */
    static List<Title> titles(JSONObject page, String kind) {
        return titles(page, "results", kind);
    }

    static List<Title> titles(JSONObject page, String key, String kind) {
        List<Title> out = new ArrayList<>();
        JSONArray results = page.optJSONArray(key);
        for (int i = 0; results != null && i < results.length(); i++) {
            JSONObject o = results.optJSONObject(i);
            if (o == null) continue;
            String k = o.optString("media_type", kind);
            if ("person".equals(k)) {
                String face = o.optString("profile_path", "");
                if (face.isEmpty() || "null".equals(face)) continue;
                out.add(new Title("person", o.optInt("id"), o.optString("name"), face, "", o.optString("known_for_department", ""), o.optDouble("popularity", 0)));
                continue;
            }
            if (!"movie".equals(k) && !"tv".equals(k)) continue;
            String poster = o.optString("poster_path", "");
            if (poster.isEmpty() || "null".equals(poster)) continue;
            String date = o.optString(k.equals("movie") ? "release_date" : "first_air_date", "");
            Title t = new Title(k, o.optInt("id"), o.optString(k.equals("movie") ? "title" : "name"), poster,
                    o.optString("backdrop_path", ""), date.length() >= 4 ? date.substring(0, 4) : "", o.optDouble("vote_average", 0));
            t.overview = o.optString("overview", "");
            t.lang = o.optString("original_language", "");
            t.date = date;
            JSONArray g = o.optJSONArray("genre_ids");
            if (g != null) {
                t.genres = new int[g.length()];
                for (int j = 0; j < g.length(); j++) t.genres[j] = g.optInt(j);
            }
            out.add(t);
        }
        return out;
    }

    // MARK: - Account

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("tmdb", Context.MODE_PRIVATE);
    }

    static String session(Context c) { return sp(c).getString("session", ""); }
    static int account(Context c) { return sp(c).getInt("account", 0); }
    static String accountName(Context c) { return sp(c).getString("name", ""); }
    static boolean signedIn(Context c) { return !session(c).isEmpty() && account(c) != 0; }

    /** Step 1: a request token the person approves at themoviedb.org/authenticate/<token>. */
    static String requestToken() throws IOException, JSONException {
        return new JSONObject(call("GET", "/authentication/token/new", null)).getString("request_token");
    }

    /** Step 2, polled: a session once the token is approved; false while it isn't yet. */
    static boolean finishSignIn(Context c, String token) throws IOException, JSONException {
        String session;
        try {
            session = post("/authentication/session/new", new JSONObject().put("request_token", token)).optString("session_id");
        } catch (IOException notYet) {
            return false;
        }
        if (session.isEmpty()) return false;
        JSONObject acc = new JSONObject(call("GET", "/account?session_id=" + session, null));
        sp(c).edit().putString("session", session).putInt("account", acc.getInt("id"))
                .putString("name", acc.optString("username")).apply();
        return true;
    }

    static void signOut(Context c) {
        String session = session(c);
        sp(c).edit().clear().apply();
        if (session.isEmpty()) return;
        new Thread(() -> {
            try { call("DELETE", "/authentication/session", new JSONObject().put("session_id", session).toString()); }
            catch (Exception ignored) {}
        }).start();
    }

    // MARK: - My list and seen (the account's, or kept on the TV)

    static List<Title> watchlist(Context c) throws IOException, JSONException {
        if (!signedIn(c)) return local(c, "list");
        List<Title> out = new ArrayList<>();
        for (String kind : new String[] {"tv", "movies"}) {
            JSONObject page = new JSONObject(call("GET", "/account/" + account(c) + "/watchlist/" + kind
                    + "?session_id=" + session(c) + "&language=" + lang() + "&sort_by=created_at.desc", null));
            out.addAll(titles(page, kind.equals("tv") ? "tv" : "movie"));
        }
        return out;
    }

    static boolean inWatchlist(Context c, String kind, int id) {
        if (!signedIn(c)) return localHas(c, "list", kind + ":" + id);
        try {
            JSONObject st = new JSONObject(call("GET", "/" + kind + "/" + id + "/account_states?session_id=" + session(c), null));
            return st.optBoolean("watchlist");
        } catch (Exception e) {
            return false;
        }
    }

    static void setWatchlist(Context c, Title t, boolean on) throws IOException, JSONException {
        if (!signedIn(c)) {
            localSet(c, "list", t, on);
            return;
        }
        post("/account/" + account(c) + "/watchlist?session_id=" + session(c),
                new JSONObject().put("media_type", t.kind).put("media_id", t.id).put("watchlist", on));
    }

    /** A rating from 1 to 10 on the account (needs the account). */
    static void rate(Context c, Title t, int value) throws IOException, JSONException {
        post("/" + t.kind + "/" + t.id + "/rating?session_id=" + session(c), new JSONObject().put("value", value));
    }

    /** {favorite, rated value (0 = none), watchlist} for a title on the account. */
    static JSONObject states(Context c, String kind, int id) {
        if (!signedIn(c)) return new JSONObject();
        try {
            return new JSONObject(call("GET", "/" + kind + "/" + id + "/account_states?session_id=" + session(c), null));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    static int ratingOf(JSONObject states) {
        JSONObject rated = states.optJSONObject("rated");
        return rated == null ? 0 : (int) Math.round(rated.optDouble("value"));
    }

    static void setFavorite(Context c, Title t, boolean on) throws IOException, JSONException {
        post("/account/" + account(c) + "/favorite?session_id=" + session(c),
                new JSONObject().put("media_type", t.kind).put("media_id", t.id).put("favorite", on));
    }

    static void unrate(Context c, Title t) throws IOException {
        call("DELETE", "/" + t.kind + "/" + t.id + "/rating?session_id=" + session(c), null);
    }

    static void rateEpisode(Context c, int series, int season, int episode, int value) throws IOException, JSONException {
        post("/tv/" + series + "/season/" + season + "/episode/" + episode + "/rating?session_id=" + session(c), new JSONObject().put("value", value));
    }

    /** The account's favorites, or its rated titles ("favorite" or "rated"), shows first. */
    static List<Title> accountList(Context c, String which) throws IOException, JSONException {
        List<Title> out = new ArrayList<>();
        if (!signedIn(c)) return out;
        for (String kind : new String[] {"tv", "movies"}) {
            JSONObject page = new JSONObject(call("GET", "/account/" + account(c) + "/" + which + "/" + kind
                    + "?session_id=" + session(c) + "&language=" + lang() + "&sort_by=created_at.desc", null));
            out.addAll(titles(page, kind.equals("tv") ? "tv" : "movie"));
        }
        return out;
    }

    /** The account's own lists: {id, name, item count}. */
    static List<String[]> lists(Context c) throws IOException, JSONException {
        List<String[]> out = new ArrayList<>();
        JSONArray results = new JSONObject(call("GET", "/account/" + account(c) + "/lists?session_id=" + session(c), null)).optJSONArray("results");
        for (int i = 0; results != null && i < results.length(); i++) {
            JSONObject o = results.getJSONObject(i);
            out.add(new String[] {String.valueOf(o.getInt("id")), o.optString("name"), String.valueOf(o.optInt("item_count"))});
        }
        return out;
    }

    static String createList(Context c, String name) throws IOException, JSONException {
        return String.valueOf(post("/list?session_id=" + session(c), new JSONObject().put("name", name).put("description", "")
                .put("language", "pt")).getInt("list_id"));
    }

    /** Lists hold movies only in TMDB's v3 API. */
    static void addToList(Context c, String list, int movie) throws IOException, JSONException {
        post("/list/" + list + "/add_item?session_id=" + session(c), new JSONObject().put("media_id", movie));
    }

    static List<Title> seen(Context c) { return local(c, "seen"); }

    /** Episodes marked as seen on the TV, as "series:season:episode". */
    static boolean episodeSeen(Context c, int series, int season, int episode) {
        return sp(c).getStringSet("episodes", new java.util.HashSet<>()).contains(series + ":" + season + ":" + episode);
    }

    static void setEpisodeSeen(Context c, int series, int season, int episode, boolean on) {
        java.util.Set<String> all = new java.util.HashSet<>(sp(c).getStringSet("episodes", new java.util.HashSet<>()));
        String key = series + ":" + season + ":" + episode;
        if (on) all.add(key); else all.remove(key);
        sp(c).edit().putStringSet("episodes", all).apply();
    }
    private static String seenRaw;
    private static final java.util.Set<String> SEEN = new java.util.HashSet<>();

    /** Asked for every poster: the list is parsed again only when it changed. */
    static boolean isSeen(Context c, String kind, int id) {
        String raw = sp(c).getString("seen", "[]");
        synchronized (SEEN) {
            if (!raw.equals(seenRaw)) {
                SEEN.clear();
                for (Title t : local(c, "seen")) SEEN.add(t.key());
                seenRaw = raw;
            }
            return SEEN.contains(kind + ":" + id);
        }
    }
    static void setSeen(Context c, Title t, boolean on) { localSet(c, "seen", t, on); }

    private static List<Title> local(Context c, String list) {
        List<Title> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp(c).getString(list, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new Title(o.getString("kind"), o.getInt("id"), o.optString("name"), o.optString("poster"),
                        o.optString("backdrop"), o.optString("year"), o.optDouble("vote", 0)));
            }
        } catch (JSONException ignored) {
        }
        return out;
    }

    private static boolean localHas(Context c, String list, String key) {
        for (Title t : local(c, list)) if (t.key().equals(key)) return true;
        return false;
    }

    private static void localSet(Context c, String list, Title t, boolean on) {
        List<Title> all = local(c, list);
        all.removeIf(x -> x.key().equals(t.key()));
        if (on) all.add(0, t);
        JSONArray a = new JSONArray();
        try {
            for (Title x : all.subList(0, Math.min(all.size(), 200))) {
                a.put(new JSONObject().put("kind", x.kind).put("id", x.id).put("name", x.name).put("poster", x.poster)
                        .put("backdrop", x.backdrop).put("year", x.year).put("vote", x.vote));
            }
        } catch (JSONException ignored) {
        }
        sp(c).edit().putString(list, a.toString()).apply();
    }
}
