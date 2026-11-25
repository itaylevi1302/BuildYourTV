package io.cleanyourtv.ui;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * TMDB v4 lists shared with tk-stream: tk:watching (with where you stopped, in each item's comment) and tk:watched.
 * This app has no player: the comments are read, never written. Everything here is network or disk work: never call
 * it on the main thread, except on(), lost() and status(), which only read the settings.
 */
final class TmdbLists {
    private TmdbLists() {}

    static final String WATCHING = "tk:watching", WATCHED = "tk:watched";
    static final String OLD_WATCHING = "testetv · Assistindo", OLD_WATCHED = "testetv · Assistidos";
    static final String APPROVE_V4 = "https://www.themoviedb.org/auth/access?request_token=";
    /** Shorter than Tmdb.LIST_TTL: another device moves these lists during the day. */
    static final long SYNC_TTL = 5 * 60_000L;
    private static final String API4 = "https://api.themoviedb.org/4";
    private static final String DESC_WATCHING = "Watching (tk-stream and CleanYourTV UI). Each item's comment keeps where you stopped.";
    private static final String DESC_WATCHED = "Watched (tk-stream and CleanYourTV UI).";
    private static final int MAX_PAGES = 10, MAX_CONTINUE = 40;

    /** Signed in with TMDB's v4 token: the lists can be synced. */
    static boolean on(Context c) { return Tmdb.signedIn(c) && !Tmdb.v4Token(c).isEmpty(); }

    /** TMDB refused the v4 token (401): the person has to reconnect. */
    static boolean lost(Context c) { return Tmdb.sp(c).getBoolean("tk_lost", false); }

    /** Where the person stopped: seconds in, of how many, and for a show the season and episode. */
    static final class Progress {
        int t, d, season, episode;
        long at;
        String ep = "";

        /** The comment of a tk:watching item ("tk:{...}", or "testetv:{...}" from the older app); null if it isn't one. */
        static Progress parse(String raw) {
            if (raw == null || raw.isEmpty() || raw.equals("null")) return null;
            try {
                JSONObject o = new JSONObject(raw.replaceFirst("^(tk|testetv):", ""));
                Progress p = new Progress();
                p.t = o.optInt("t");
                p.d = o.optInt("d");
                p.at = o.optLong("at");
                p.ep = o.optString("ep");
                p.season = o.optInt("s");
                p.episode = o.optInt("e");
                return p;
            } catch (JSONException e) {
                return null;
            }
        }
    }

    static final class Status {
        int watching, watched;
        long at;
        String error = "";
        boolean lost;
    }

    // MARK: - v4 HTTP

    static final class Http4Exception extends IOException {
        final int code;
        Http4Exception(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    private static JSONObject call4(String method, String pathAndQuery, JSONObject body, String bearer) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(API4 + pathAndQuery).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        c.setRequestMethod(method);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("Authorization", "Bearer " + bearer);
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json;charset=utf-8");
            try (OutputStream out = c.getOutputStream()) { out.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
        }
        int code = c.getResponseCode();
        InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
        String text = in == null ? "" : Tmdb.readAll(in);
        c.disconnect();
        JSONObject o;
        try {
            o = text.trim().isEmpty() ? new JSONObject() : new JSONObject(text);
        } catch (JSONException e) {
            o = new JSONObject();
        }
        if (code >= 400 || !o.optBoolean("success", true)) {
            String m = o.optString("status_message");
            throw new Http4Exception(code, "TMDB " + code + (m.isEmpty() ? "" : ": " + m));
        }
        return o;
    }

    private static JSONObject callUser(Context c, String method, String pathAndQuery, JSONObject body) throws IOException {
        return call4(method, pathAndQuery, body, Tmdb.v4Token(c));
    }

    // MARK: - Sign-in

    /** Step 1: a request token the person approves at themoviedb.org/auth/access?request_token=<token>. */
    static String requestToken() throws IOException, JSONException {
        return call4("POST", "/auth/request_token", new JSONObject(), Prefs.tmdbKey()).getString("request_token");
    }

    /** Step 2, polled: the v4 access token, converted to a v3 session; false while the token isn't approved yet. */
    static boolean finishSignIn(Context c, String requestToken) throws IOException, JSONException {
        JSONObject r;
        try {
            r = call4("POST", "/auth/access_token", new JSONObject().put("request_token", requestToken), Prefs.tmdbKey());
        } catch (IOException notYet) {
            return false;
        }
        String access = r.optString("access_token"), account = r.optString("account_id");
        if (access.isEmpty() || account.isEmpty()) return false;
        Tmdb.finishV4(c, access, account);
        try {
            listIds(c, true);
        } catch (Exception e) {
            AppLog.w("tk lists: " + e.getMessage());
        }
        return true;
    }

    /** Disconnect: the v4 token is revoked at TMDB, then everything kept on the TV for the account is cleared. */
    static void signOut(Context c) {
        String access = Tmdb.v4Token(c), read = Prefs.tmdbKey();
        if (!access.isEmpty() && read.startsWith("eyJ")) {
            new Thread(() -> {
                try { call4("DELETE", "/auth/access_token", new JSONObject().put("access_token", access), read); }
                catch (Exception e) { AppLog.w("tk sign-out: " + e.getMessage()); }
            }, "tk-signout").start();
        }
        Tmdb.signOut(c);
    }

    // MARK: - The two lists

    /** {tk:watching id, tk:watched id}, found on the account or created. */
    static synchronized String[] listIds(Context c, boolean fresh) throws IOException {
        SharedPreferences sp = Tmdb.sp(c);
        String account = Tmdb.v4Account(c);
        if (!fresh) {
            String[] cached = sp.getString("tk_lists", "").split("\\|");
            if (cached.length == 3 && cached[0].equals(account) && !cached[1].isEmpty() && !cached[2].isEmpty()) {
                return new String[] {cached[1], cached[2]};
            }
        }
        Map<String, String> byName = new HashMap<>();
        try {
            for (int page = 1; page <= MAX_PAGES; page++) {
                JSONObject r = callUser(c, "GET", "/account/" + account + "/lists?page=" + page, null);
                JSONArray results = r.optJSONArray("results");
                for (int i = 0; results != null && i < results.length(); i++) {
                    JSONObject o = results.getJSONObject(i);
                    String name = o.optString("name");
                    if (!byName.containsKey(name)) byName.put(name, String.valueOf(o.opt("id")));
                }
                if (page >= r.optInt("total_pages", 1)) break;
            }
            String watching = ensure(c, byName, WATCHING, OLD_WATCHING, DESC_WATCHING);
            String watched = ensure(c, byName, WATCHED, OLD_WATCHED, DESC_WATCHED);
            sp.edit().putString("tk_lists", account + "|" + watching + "|" + watched).apply();
            return new String[] {watching, watched};
        } catch (JSONException e) {
            throw new IOException(e.getMessage());
        }
    }

    private static String ensure(Context c, Map<String, String> byName, String name, String oldName, String description) throws IOException, JSONException {
        String id = byName.get(name);
        if (id != null) return id;
        id = byName.get(oldName);
        if (id != null) {
            callUser(c, "PUT", "/list/" + id, new JSONObject().put("name", name));
            return id;
        }
        JSONObject made = callUser(c, "POST", "/list", new JSONObject().put("name", name).put("description", description)
                .put("iso_639_1", Tmdb.lang().substring(0, 2)).put("iso_3166_1", Tmdb.region()).put("public", false));
        return String.valueOf(made.get("id"));
    }

    // MARK: - Sync

    static Status status(Context c) {
        SharedPreferences sp = Tmdb.sp(c);
        Status s = new Status();
        try {
            s.watching = new JSONArray(sp.getString("tk_watching", "[]")).length();
        } catch (JSONException ignored) {
        }
        s.watched = sp.getInt("tk_watched_n", 0);
        s.at = sp.getLong("tk_synced_at", 0);
        s.error = sp.getString("tk_error", "");
        s.lost = lost(c);
        return s;
    }

    /** Reads both lists when the copy is older than SYNC_TTL (or `force`); the previous copy stays if TMDB can't be reached. */
    static synchronized Status sync(Context c, boolean force) {
        if (!on(c)) return status(c);
        SharedPreferences sp = Tmdb.sp(c);
        long now = System.currentTimeMillis(), at = sp.getLong("tk_synced_at", 0);
        if (!force && now - at < SYNC_TTL && at >= Tmdb.listsSince()) return status(c);
        try {
            try {
                flushPending(c);
            } catch (Http4Exception e) {
                if (e.code == 401) throw e;
                AppLog.w("tk pending: " + e.getMessage());
            } catch (IOException e) {
                AppLog.w("tk pending: " + e.getMessage());
            }
            try {
                pull(c, listIds(c, false));
            } catch (Http4Exception e) {
                if (e.code != 404) throw e;
                pull(c, listIds(c, true));
            }
        } catch (Http4Exception e) {
            if (e.code == 401) {
                onLost(c);
            } else {
                sp.edit().putString("tk_error", String.valueOf(e.getMessage())).apply();
            }
        } catch (IOException | JSONException e) {
            sp.edit().putString("tk_error", String.valueOf(e.getMessage())).apply();
        }
        return status(c);
    }

    private static void onLost(Context c) {
        Tmdb.sp(c).edit().remove("v4_token").remove("tk_lists").remove("tk_watching").remove("tk_pending")
                .remove("seen_remote").remove("tk_watched_n").remove("tk_error").putBoolean("tk_lost", true).apply();
    }

    private static void pull(Context c, String[] ids) throws IOException, JSONException {
        List<JSONObject> watching = new ArrayList<>();
        Map<String, String> comments = new HashMap<>();
        List<Tmdb.Title> watched = new ArrayList<>();
        read(c, ids[0], watching, comments);
        List<JSONObject> done = new ArrayList<>();
        read(c, ids[1], done, new HashMap<String, String>());

        JSONArray pending = pending(c);
        Set<String> unseen = new HashSet<>(), seen = new HashSet<>();
        for (int i = 0; i < pending.length(); i++) {
            JSONObject p = pending.getJSONObject(i);
            (p.optString("op").equals("unseen") ? unseen : seen).add(p.optString("kind") + ":" + p.optInt("id"));
        }
        for (JSONObject o : done) {
            Tmdb.Title t = titleOf(o);
            if (t != null && !unseen.contains(t.key())) watched.add(t);
        }
        JSONArray keep = new JSONArray();
        for (JSONObject o : watching) {
            Tmdb.Title t = titleOf(o);
            if (t == null || seen.contains(t.key())) continue;
            keep.put(Tmdb.toJson(t).put("c", comments.containsKey(t.key()) ? comments.get(t.key()) : ""));
        }
        Tmdb.setSeenRemote(c, watched);
        Tmdb.sp(c).edit().putString("tk_watching", keep.toString()).putInt("tk_watched_n", watched.size())
                .putLong("tk_synced_at", System.currentTimeMillis()).remove("tk_error").apply();
    }

    /** Every item of a list (up to MAX_PAGES pages) and the comments, keyed "movie:ID" / "tv:ID". */
    private static void read(Context c, String id, List<JSONObject> items, Map<String, String> comments) throws IOException, JSONException {
        for (int page = 1; page <= MAX_PAGES; page++) {
            JSONObject r = callUser(c, "GET", "/list/" + id + "?page=" + page + "&language=" + Tmdb.lang(), null);
            JSONArray results = r.optJSONArray("results");
            for (int i = 0; results != null && i < results.length(); i++) items.add(results.getJSONObject(i));
            JSONObject cm = r.optJSONObject("comments");
            if (cm != null) {
                java.util.Iterator<String> keys = cm.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    if (!cm.isNull(k)) comments.put(k, cm.optString(k));
                }
            }
            if (page >= r.optInt("total_pages", 1)) break;
        }
    }

    private static String clean(String v) { return v == null || v.equals("null") ? "" : v; }

    /** A list item as a title: movies and shows only. Titles without a poster are kept (a seen mark must not get lost). */
    private static Tmdb.Title titleOf(JSONObject o) {
        String kind = o.optString("media_type");
        if (!kind.equals("movie") && !kind.equals("tv")) return null;
        int id = o.optInt("id");
        if (id == 0) return null;
        String name = o.optString(kind.equals("tv") ? "name" : "title");
        if (name.isEmpty()) name = o.optString(kind.equals("tv") ? "title" : "name");
        String date = o.optString(kind.equals("tv") ? "first_air_date" : "release_date");
        String year = date.length() >= 4 ? date.substring(0, 4) : "";
        return new Tmdb.Title(kind, id, clean(name), clean(o.optString("poster_path")), clean(o.optString("backdrop_path")),
                year, o.optDouble("vote_average", 0));
    }

    // MARK: - Continue watching

    private static List<Tmdb.Title> cachedWatching(Context c, Map<String, String> commentsOut) {
        List<Tmdb.Title> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(Tmdb.sp(c).getString("tk_watching", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Tmdb.Title t = Tmdb.fromJson(o);
                out.add(t);
                commentsOut.put(t.key(), o.optString("c"));
            }
        } catch (JSONException ignored) {
        }
        return out;
    }

    /** What is on tk:watching and not finished, the most recently watched first. */
    static List<Tmdb.Title> continueTitles(Context c) {
        List<Tmdb.Title> out = new ArrayList<>();
        if (!on(c)) return out;
        try { sync(c, false); } catch (Exception ignored) {}
        Map<String, String> comments = new HashMap<>();
        final Map<String, Long> when = new HashMap<>();
        for (Tmdb.Title t : cachedWatching(c, comments)) {
            if (t.poster.isEmpty() || Tmdb.isSeenRemote(c, t.key())) continue;
            Progress p = Progress.parse(comments.get(t.key()));
            when.put(t.key(), p == null ? 0L : p.at);
            out.add(t);
        }
        Collections.sort(out, (a, b) -> Long.compare(when.get(b.key()), when.get(a.key())));
        return out.size() > MAX_CONTINUE ? new ArrayList<>(out.subList(0, MAX_CONTINUE)) : out;
    }

    /** Where the person stopped in this title; null when it isn't on tk:watching (or is already watched). */
    static Progress progress(Context c, String kind, int id) {
        if (!on(c)) return null;
        try { sync(c, false); } catch (Exception ignored) {}
        String key = kind + ":" + id;
        if (Tmdb.isSeenRemote(c, key)) return null;
        Map<String, String> comments = new HashMap<>();
        for (Tmdb.Title t : cachedWatching(c, comments)) {
            if (!t.key().equals(key)) continue;
            Progress p = Progress.parse(comments.get(key));
            return p == null ? new Progress() : p;
        }
        return null;
    }

    // MARK: - Seen (the explicit toggle)

    /** The Seen toggle: on the TV at once; on the account (tk:watching to tk:watched) in the background. */
    static void markSeen(Context c, Tmdb.Title t, boolean on) {
        Tmdb.setSeen(c, t, on);
        if (!on(c)) return;
        synchronized (TmdbLists.class) {
            try {
                JSONArray pending = pending(c);
                JSONArray next = new JSONArray();
                for (int i = 0; i < pending.length(); i++) {
                    JSONObject p = pending.getJSONObject(i);
                    if (!(p.optString("kind").equals(t.kind) && p.optInt("id") == t.id)) next.put(p);
                }
                next.put(new JSONObject().put("op", on ? "seen" : "unseen").put("kind", t.kind).put("id", t.id));
                SharedPreferences.Editor e = Tmdb.sp(c).edit().putString("tk_pending", next.toString());
                if (on) {
                    JSONArray keep = new JSONArray();
                    JSONArray cached = new JSONArray(Tmdb.sp(c).getString("tk_watching", "[]"));
                    for (int i = 0; i < cached.length(); i++) {
                        JSONObject o = cached.getJSONObject(i);
                        if (!(o.optString("kind").equals(t.kind) && o.optInt("id") == t.id)) keep.put(o);
                    }
                    e.putString("tk_watching", keep.toString());
                }
                e.apply();
            } catch (JSONException ignored) {
            }
        }
        new Thread(() -> {
            try { flushPending(c); } catch (Exception e) { AppLog.w("tk seen: " + e.getMessage()); }
        }, "tk-lists").start();
    }

    private static JSONArray pending(Context c) {
        try {
            return new JSONArray(Tmdb.sp(c).getString("tk_pending", "[]"));
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    private static JSONObject items(String kind, int id) throws JSONException {
        return new JSONObject().put("items", new JSONArray().put(new JSONObject().put("media_type", kind).put("media_id", id)));
    }

    /** Sends what the person marked while offline (or just now); stops at the first network error, keeps the rest. */
    static synchronized void flushPending(Context c) throws IOException {
        if (!on(c)) return;
        JSONArray all = pending(c);
        if (all.length() == 0) return;
        try {
            String[] ids = listIds(c, false);
            while (all.length() > 0) {
                JSONObject p = all.getJSONObject(0);
                String kind = p.optString("kind");
                int id = p.optInt("id");
                try {
                    if (p.optString("op").equals("seen")) {
                        try { callUser(c, "DELETE", "/list/" + ids[0] + "/items", items(kind, id)); }
                        catch (Http4Exception e) { if (e.code == 401 || e.code == 429 || e.code >= 500) throw e; }
                        try { callUser(c, "POST", "/list/" + ids[1] + "/items", items(kind, id)); }
                        catch (Http4Exception e) { if (e.code == 401 || e.code == 429 || e.code >= 500) throw e; }
                    } else {
                        try { callUser(c, "DELETE", "/list/" + ids[1] + "/items", items(kind, id)); }
                        catch (Http4Exception e) { if (e.code == 401 || e.code == 429 || e.code >= 500) throw e; }
                    }
                } catch (Http4Exception e) {
                    if (e.code == 401) onLost(c);
                    throw e;
                }
                JSONArray rest = new JSONArray();
                for (int i = 1; i < all.length(); i++) rest.put(all.get(i));
                all = rest;
                Tmdb.sp(c).edit().putString("tk_pending", all.toString()).apply();
            }
        } catch (JSONException e) {
            throw new IOException(e.getMessage());
        }
    }
}
