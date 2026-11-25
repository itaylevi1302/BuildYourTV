package io.cleanyourtv.ui;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * TMDB's streaming services in the region and the TV app that opens each one: the person's pick (Settings >
 * Services, or config.json "providers"), else the official app when it is installed. "off" keeps a service out.
 */
final class Providers {
    private Providers() {}

    static final class Provider {
        final int id;
        final String name, logo;
        final int priority;

        Provider(int id, String name, String logo, int priority) {
            this.id = id;
            this.name = name;
            this.logo = logo;
            this.priority = priority;
        }
    }

    /** Part of a TMDB service name (lowercase) → the official TV apps, first installed wins. */
    private static final String[][] APPS = {
        {"netflix", "com.netflix.ninja"},
        {"amazon prime video", "com.amazon.amazonvideo.livingroom"},
        {"prime video", "com.amazon.amazonvideo.livingroom"},
        {"disney plus", "com.disney.disneyplus"},
        {"disney+", "com.disney.disneyplus"},
        {"max", "com.wbd.stream", "com.hbo.hbonow"},
        {"apple tv", "com.apple.atve.androidtv.appletv"},
        {"globoplay", "com.globo.globotv"},
        {"paramount", "com.cbs.ott", "com.cbs.ca"},
        {"crunchyroll", "com.crunchyroll.crunchyroid"},
        {"mubi", "com.mubi"},
        {"youtube", "com.google.android.youtube.tv"},
        {"pluto tv", "tv.pluto.android"},
        {"claro tv", "com.clarotvmais.android"},
        {"looke", "br.com.looke.androidtv"},
        {"telecine", "com.telecine.telecineplay"},
        {"univer", "br.com.univervideo.tv"},
    };

    private static List<Provider> cached;
    private static long cachedAt;

    /** The region's services, most watched first (TMDB's display priority), movies and shows merged. */
    static synchronized List<Provider> region() throws Exception {
        if (cached != null && System.currentTimeMillis() - cachedAt < 6 * 3600_000L) return cached;
        Map<Integer, Provider> byId = new LinkedHashMap<>();
        for (String kind : new String[] {"tv", "movie"}) {
            JSONArray results = Tmdb.get("/watch/providers/" + kind + "?watch_region=" + Tmdb.region()).optJSONArray("results");
            for (int i = 0; results != null && i < results.length(); i++) {
                JSONObject o = results.getJSONObject(i);
                int id = o.getInt("provider_id");
                if (byId.containsKey(id)) continue;
                JSONObject prio = o.optJSONObject("display_priorities");
                int priority = prio != null && prio.has(Tmdb.region()) ? prio.getInt(Tmdb.region()) : o.optInt("display_priority", 999);
                byId.put(id, new Provider(id, o.optString("provider_name"), o.optString("logo_path"), priority));
            }
        }
        List<Provider> out = new ArrayList<>(byId.values());
        Collections.sort(out, (a, b) -> Integer.compare(a.priority, b.priority));
        cached = out;
        cachedAt = System.currentTimeMillis();
        return out;
    }

    /** The services' names as last fetched, never from the network: empty until the list was loaded once. */
    static synchronized List<String> cachedNames() {
        List<String> out = new ArrayList<>();
        if (cached != null) for (Provider p : cached) out.add(p.name);
        return out;
    }

    /** The official app for a service name when it is installed, or null. */
    static String officialApp(Context c, String name) {
        String n = name.toLowerCase(Locale.ROOT);
        for (String[] row : APPS) {
            boolean match = row[0].equals("max") ? n.matches(".*\\bmax\\b.*") : n.contains(row[0]);
            if (!match) continue;
            for (int i = 1; i < row.length; i++) if (installed(c, row[i])) return row[i];
        }
        return null;
    }

    /** The app that opens this service now, or null (none installed, or turned off). */
    static String app(Context c, int id, String name) {
        String pick = Prefs.provider(String.valueOf(id));
        if ("off".equals(pick)) return null;
        if (!pick.isEmpty() && installed(c, pick)) return pick;
        return officialApp(c, name);
    }

    static boolean off(int id) {
        return "off".equals(Prefs.provider(String.valueOf(id)));
    }

    /** The services Explore and the picks use: the ones with an app, or all but the turned-off ones. */
    static List<Provider> usable(Context c) throws Exception {
        List<Provider> out = new ArrayList<>();
        boolean all = Prefs.showAllProviders();
        for (Provider p : region()) {
            if (off(p.id)) continue;
            if (all || app(c, p.id, p.name) != null) out.add(p);
        }
        return out;
    }

    static boolean installed(Context c, String pkg) {
        return pkg != null && HomeApps.launchIntent(c.getPackageManager(), pkg) != null;
    }

    static String label(Context c, String pkg) {
        try {
            return String.valueOf(c.getPackageManager().getApplicationLabel(c.getPackageManager().getApplicationInfo(pkg, 0)));
        } catch (Exception e) {
            return pkg;
        }
    }

    /**
     * Apps that specifically handle a YouTube watch URL - the official app, SmartTube, NewPipe-style clients and
     * the rest - found by diffing the handlers of a youtube.com link against the handlers of an unrelated https
     * link, so a general-purpose browser (which answers both) is never offered as a "trailer player".
     */
    static List<String> youtubePlayers(Context c) {
        PackageManager pm = c.getPackageManager();
        Set<String> browsers = new HashSet<>();
        for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/")), 0)) {
            browsers.add(ri.activityInfo.packageName);
        }
        List<String> out = new ArrayList<>();
        Intent yt = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=dQw4w9WgXcQ"));
        for (ResolveInfo ri : pm.queryIntentActivities(yt, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (!browsers.contains(pkg) && !out.contains(pkg)) out.add(pkg);
        }
        return out;
    }
}
