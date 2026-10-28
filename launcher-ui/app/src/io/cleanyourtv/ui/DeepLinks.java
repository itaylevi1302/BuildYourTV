package io.cleanyourtv.ui;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;
import java.text.Normalizer;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * "Watch on X" straight to the title, not just the app's home. TMDB doesn't know the services' own ids, Wikidata
 * does: its item for the TMDB id carries the Netflix, Disney+, Apple TV, Max and Prime Video ids, which make each
 * app's links. Answers are kept for the session; no link means the app opens on its home as before.
 */
final class DeepLinks {
    private DeepLinks() {}

    private static final String UA = "BuildYourTV/1.0 (https://github.com/itaylevi1302/BuildYourTV)";
    private static final Map<String, Map<String, String>> CACHE = new HashMap<>();

    /** App package → link to the title in it ("tv" or "movie", TMDB id); empty when Wikidata has none. */
    static Map<String, String> find(Context c, String kind, int id, String name) {
        String key = kind + ":" + id;
        synchronized (CACHE) {
            if (CACHE.containsKey(key)) return CACHE.get(key);
        }
        android.content.SharedPreferences sp = c.getSharedPreferences("deeplinks", Context.MODE_PRIVATE);
        String kept = sp.getString(key, null);
        if (kept != null) {   // "time|json": a month, then asked again (Wikidata gains ids over time)
            int bar = kept.indexOf('|');
            try {
                if (System.currentTimeMillis() - Long.parseLong(kept.substring(0, bar)) < 30L * 24 * 3600_000) {
                    Map<String, String> out = new HashMap<>();
                    JSONObject o = new JSONObject(kept.substring(bar + 1));
                    for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
                        String app = it.next();
                        out.put(app, o.getString(app));
                    }
                    synchronized (CACHE) { CACHE.put(key, out); }
                    return out;
                }
            } catch (Exception ignored) {
            }
        }
        Map<String, String> out = new HashMap<>();
        try {
            String prop = kind.equals("movie") ? "P4947" : "P4983";
            JSONArray hits = new JSONObject(get("https://www.wikidata.org/w/api.php?action=query&list=search&format=json&srsearch=haswbstatement:"
                    + prop + "=" + id)).getJSONObject("query").getJSONArray("search");
            if (hits.length() > 0) {
                String q = hits.getJSONObject(0).getString("title");
                JSONObject claims = new JSONObject(get("https://www.wikidata.org/wiki/Special:EntityData/" + q + ".json"))
                        .getJSONObject("entities").getJSONObject(q).getJSONObject("claims");
                String slug = slug(name);
                boolean movie = kind.equals("movie");
                String v;
                if ((v = value(claims, "P1874")) != null) out.put("com.netflix.ninja", "https://www.netflix.com/title/" + v);
                if ((v = value(claims, movie ? "P7595" : "P7596")) != null) {
                    out.put("com.disney.disneyplus", "https://www.disneyplus.com/" + (movie ? "movies/" : "series/") + slug + "/" + v);
                }
                if ((v = value(claims, movie ? "P9586" : "P9751")) != null) {
                    out.put("com.apple.atve.androidtv.appletv", "https://tv.apple.com/" + (movie ? "movie/" : "show/") + slug + "/" + v);
                }
                if ((v = value(claims, "P8298")) != null) {
                    out.put("com.wbd.stream", "https://play.max.com/" + v);
                    out.put("com.hbo.hbonow", "https://play.hbomax.com/" + v);
                }
                if ((v = value(claims, "P8055")) != null) out.put("com.amazon.amazonvideo.livingroom", "https://app.primevideo.com/detail?gti=" + v);
            }
        } catch (Exception e) {
            AppLog.w("deep link " + key + ": " + e.getMessage());
            return out;    // not cached: the next try may reach Wikidata
        }
        synchronized (CACHE) { CACHE.put(key, out); }
        sp.edit().putString(key, System.currentTimeMillis() + "|" + new JSONObject(out)).apply();
        return out;
    }

    /** Opens the title in the app when there's a link it takes; otherwise false (the caller opens the app). */
    static boolean open(Context c, String pkg, String url) {
        if (url == null) return false;
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (pkg.equals("com.netflix.ninja")) i.putExtra("source", "30");   // Netflix for TV wants to know it's a deep link
        if (i.resolveActivity(c.getPackageManager()) == null) return false;
        try {
            c.startActivity(i);
            AppLog.w("deep link -> " + url);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String value(JSONObject claims, String prop) {
        JSONArray list = claims.optJSONArray(prop);
        if (list == null || list.length() == 0) return null;
        Object v = list.optJSONObject(0).optJSONObject("mainsnak").optJSONObject("datavalue") == null ? null
                : list.optJSONObject(0).optJSONObject("mainsnak").optJSONObject("datavalue").opt("value");
        return v instanceof String ? (String) v : null;
    }

    private static String slug(String name) {
        String s = Normalizer.normalize(name == null ? "" : name, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return s.isEmpty() ? "x" : s;
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(8000);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept", "application/json");
        try {
            return Tmdb.readAll(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }
}
