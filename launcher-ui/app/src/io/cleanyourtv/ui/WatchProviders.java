package io.cleanyourtv.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentProviderClient;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.TextUtils;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Watch providers (docs/specs/provider.md, #106): apps on the TV that say whether they have the title a page shows, and
 * open it. Finding them, the person's answer and order, asking and reading the answer, opening an item. Nothing here
 * touches the network, and none of it runs on the main thread except open(): found(), ask() and allowed() are for a
 * background thread. A provider is asked only after the person allowed it; nothing from a response is kept.
 */
final class WatchProviders {
    private WatchProviders() {}

    static final String ACTION = "io.cleanyourtv.action.WATCH_PROVIDER", META = "io.cleanyourtv.watch_provider", METHOD = "availability";
    static final int TIMEOUT_MS = 1500, MAX_FOUND = 16, MAX_ASKED = 8, MAX_ITEMS = 5, URI_MAX = 2048, RESPONSE_MAX = 32 * 1024, MAX_EVENTS = 64;
    private static final int APP_LABEL_MAX = 40;

    /** NEW: never answered, or answered for another signing key. */
    enum State { NEW, ON, OFF }

    static final class Provider {
        final String pkg, authority, label, appLabel, digest;
        final State state;

        Provider(String pkg, String authority, String label, String appLabel, String digest, State state) {
            this.pkg = pkg;
            this.authority = authority;
            this.label = label;
            this.appLabel = appLabel;
            this.digest = digest;
            this.state = state;
        }
    }

    static final class Item {
        final Provider provider;
        final String label, detail, uri;   // label and detail may be ""
        final boolean play;
        final int position, duration;      // -1 when there is no progress

        Item(Provider provider, String label, String detail, String uri, boolean play, int position, int duration) {
            this.provider = provider;
            this.label = label;
            this.detail = detail;
            this.uri = uri;
            this.play = play;
            this.position = position;
            this.duration = duration;
        }
    }

    private static final ThreadPoolExecutor POOL = new ThreadPoolExecutor(0, 16, 30, TimeUnit.SECONDS, new SynchronousQueue<Runnable>(), r -> {
        Thread t = new Thread(r, "watch-provider");
        t.setDaemon(true);
        return t;
    });

    // MARK: - Finding

    /** Every usable provider on the TV, in the person's order. Background only. */
    static List<Provider> found(Context c) {
        PackageManager pm = c.getPackageManager();
        List<ResolveInfo> infos;
        try {
            infos = pm.queryIntentContentProviders(new Intent(ACTION), PackageManager.GET_META_DATA);
        } catch (Exception e) {
            AppLog.w("watch providers: query failed");
            return new ArrayList<>();
        }
        Set<String> extra = reservedNames();
        Set<String> seen = new HashSet<>();
        List<Provider> out = new ArrayList<>();
        for (int i = 0; infos != null && i < infos.size() && i < MAX_FOUND; i++) {
            String pkg = "?";
            try {
                ProviderInfo pi = infos.get(i).providerInfo;
                if (pi == null) continue;
                pkg = pi.packageName;
                if (pkg.equals(c.getPackageName())) continue;
                if (!seen.add(pkg)) continue;
                if (!pi.exported || !pi.enabled || pi.applicationInfo == null || !pi.applicationInfo.enabled) {
                    AppLog.w("watch provider " + pkg + ": not exported or disabled");
                    continue;
                }
                if (pi.authority == null || pi.authority.split(";")[0].isEmpty()) {
                    AppLog.w("watch provider " + pkg + ": no authority");
                    continue;
                }
                String authority = pi.authority.split(";")[0];
                String label = declaredLabel(pm, pi);
                if (label == null) {
                    AppLog.w("watch provider " + pkg + ": no usable <watch-provider> declaration");
                    continue;
                }
                String reason = WatchProviderLabels.check(label);
                if (reason != null) {
                    AppLog.w("watch provider " + pkg + ": " + reason);
                    continue;
                }
                if (WatchProviderLabels.reserved(WatchProviderLabels.fold(label), extra)) {
                    AppLog.w("watch provider " + pkg + ": label is reserved");
                    continue;
                }
                String digest = digest(pm, pkg);
                String appLabel = WatchProviderLabels.clean(String.valueOf(pm.getApplicationLabel(pi.applicationInfo)), APP_LABEL_MAX);
                if (appLabel.isEmpty()) appLabel = pkg;
                out.add(new Provider(pkg, authority, label, appLabel, digest, stateOf(pkg, digest)));
            } catch (Exception e) {
                AppLog.w("watch provider " + pkg + ": " + e.getClass().getSimpleName());
            }
        }
        return clash(arrange(out));
    }

    /** The package names in the person's order, then the rest by app name. */
    private static List<Provider> arrange(List<Provider> found) {
        List<Provider> byName = new ArrayList<>(found);
        Collections.sort(byName, (a, b) -> a.appLabel.toLowerCase(Locale.ROOT).compareTo(b.appLabel.toLowerCase(Locale.ROOT)));
        Map<String, Provider> byPkg = new HashMap<>();
        List<String> pkgs = new ArrayList<>();
        for (Provider p : byName) {
            byPkg.put(p.pkg, p);
            pkgs.add(p.pkg);
        }
        List<Provider> out = new ArrayList<>();
        for (String pkg : VisibleOrder.arrange(Prefs.watchProvidersOrder(), new ArrayList<String>(), pkgs)) out.add(byPkg.get(pkg));
        return out;
    }

    /** Two providers with one label: the one already decided keeps it (the earlier in the order when both are); neither
     *  decided, neither is shown. */
    private static List<Provider> clash(List<Provider> ordered) {
        Map<String, List<Provider>> groups = new LinkedHashMap<>();
        for (Provider p : ordered) {
            String key = WatchProviderLabels.fold(p.label);
            List<Provider> g = groups.get(key);
            if (g == null) groups.put(key, g = new ArrayList<>());
            g.add(p);
        }
        Set<Provider> keep = new HashSet<>();
        for (List<Provider> g : groups.values()) {
            if (g.size() == 1) {
                keep.add(g.get(0));
                continue;
            }
            Provider winner = null;
            for (Provider p : g) {
                if (p.state != State.NEW && winner == null) winner = p;
            }
            for (Provider p : g) {
                if (p != winner) AppLog.w("watch provider " + p.pkg + ": label is also used by another app");
            }
            if (winner != null) keep.add(winner);
        }
        List<Provider> out = new ArrayList<>();
        for (Provider p : ordered) if (keep.contains(p)) out.add(p);
        return out;
    }

    /** The label of the provider's res/xml file, or null. A bounded walk: at most MAX_EVENTS events, two levels deep. */
    private static String declaredLabel(PackageManager pm, ProviderInfo pi) throws Exception {
        XmlPullParser p = pi.loadXmlMetaData(pm, META);
        if (p == null) return null;
        try {
            int events = 0;
            String found = null;
            boolean root = false;
            for (int type = p.getEventType(); type != XmlPullParser.END_DOCUMENT; type = p.next()) {
                if (++events > MAX_EVENTS) return null;
                if (type != XmlPullParser.START_TAG) continue;
                if (p.getDepth() > 2) return null;
                if (p.getDepth() != 1) continue;
                if (root || !"watch-provider".equals(p.getName())) return null;
                root = true;
                String spec = null, label = null;
                for (int i = 0; i < p.getAttributeCount() && i < 16; i++) {
                    String ns = p.getAttributeNamespace(i);
                    if (ns != null && !ns.isEmpty()) continue;
                    String name = p.getAttributeName(i);
                    if ("spec".equals(name)) spec = p.getAttributeValue(i);
                    else if ("label".equals(name)) label = p.getAttributeValue(i);
                }
                if (!isSpec(spec)) return null;
                found = label;
            }
            return found;
        } finally {
            if (p instanceof AutoCloseable) ((AutoCloseable) p).close();
        }
    }

    /** One to three digits, one or more: a spec the provider speaks, this launcher's or a later one. */
    private static boolean isSpec(String spec) {
        if (spec == null || spec.isEmpty() || spec.length() > 3) return false;
        for (int i = 0; i < spec.length(); i++) if (spec.charAt(i) < '0' || spec.charAt(i) > '9') return false;
        return Integer.parseInt(spec) >= 1;
    }

    /** The names a provider may not take: the launcher's own screens and the streaming services' names, folded. */
    private static Set<String> reservedNames() {
        Set<String> out = new HashSet<>();
        int[] ids = {R.string.settings, R.string.home_settings, R.string.inputs, R.string.live_tv, R.string.all_apps, R.string.explore,
                R.string.continue_watching, R.string.services, R.string.where_to_watch};
        for (int id : ids) {
            try {
                out.add(WatchProviderLabels.fold(Strings.get(id)));
            } catch (Exception ignored) {
            }
        }
        for (String name : Providers.cachedNames()) out.add(WatchProviderLabels.fold(name));
        out.remove("");
        return out;
    }

    /** The SHA-256 of the package's one signing certificate (what apksigner prints), lower case hex. A package with more
     *  than one signer is refused: the order of its certificates is not defined. */
    private static String digest(PackageManager pm, String pkg) throws Exception {
        PackageInfo info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES);
        SigningInfo signing = info.signingInfo;
        if (signing == null) throw new Exception("no signing certificate");
        if (signing.hasMultipleSigners()) throw new Exception("more than one signer");
        Signature[] sigs = signing.getApkContentsSigners();
        if (sigs == null || sigs.length != 1) throw new Exception("not exactly one signing certificate");
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(sigs[0].toByteArray());
        StringBuilder b = new StringBuilder(hash.length * 2);
        for (byte x : hash) b.append(Character.forDigit((x >> 4) & 15, 16)).append(Character.forDigit(x & 15, 16));
        return b.toString();
    }

    // MARK: - The person's answer and order

    private static State stateOf(String pkg, String digest) {
        String v = Prefs.watchProvider(pkg);
        if (v.equals("on|" + digest)) return State.ON;
        if (v.equals("off|" + digest)) return State.OFF;
        return State.NEW;
    }

    /** The providers switched on, in order, at most MAX_ASKED. */
    static List<Provider> allowed(List<Provider> found) {
        List<Provider> out = new ArrayList<>();
        for (Provider p : found) if (p.state == State.ON && out.size() < MAX_ASKED) out.add(p);
        return out;
    }

    /** Records the person's answer for this app and this signing key, and gives it a place in the order. */
    static void decide(Provider p, boolean on) {
        Prefs.setWatchProvider(p.pkg, (on ? "on|" : "off|") + p.digest);
        List<String> order = Prefs.watchProvidersOrder();
        order = order == null ? new ArrayList<String>() : new ArrayList<>(order);
        if (!order.contains(p.pkg)) {
            order.add(p.pkg);
            Prefs.setWatchProvidersOrder(order);
        }
    }

    /** Moves p one place (dir -1 or 1) among the providers shown; the saved places of the ones not shown stay. */
    static void move(List<Provider> shown, Provider p, int dir) {
        List<String> pkgs = new ArrayList<>();
        for (Provider s : shown) pkgs.add(s.pkg);
        int i = pkgs.indexOf(p.pkg), j = i + dir;
        if (i < 0 || j < 0 || j >= pkgs.size()) return;
        Collections.swap(pkgs, i, j);
        List<String> saved = Prefs.watchProvidersOrder();
        List<String> base = saved == null ? new ArrayList<String>() : saved;
        for (String pkg : pkgs) if (!base.contains(pkg)) base = append(base, pkg);
        Prefs.setWatchProvidersOrder(VisibleOrder.replace(base, pkgs));
    }

    private static List<String> append(List<String> list, String pkg) {
        List<String> out = new ArrayList<>(list);
        out.add(pkg);
        return out;
    }

    /** Changes when an answer or the order does. */
    static int stamp() { return Prefs.watchProvidersStamp(); }

    // MARK: - Asking

    /** The request of spec 1 for a title (season and episode only when episode is above 0). */
    static JSONObject request(String type, int tmdb, String title, String original, int year, JSONObject ext, int season, int episode) {
        JSONObject r = new JSONObject();
        try {
            r.put("v", 1);
            r.put("type", type);
            JSONObject ids = new JSONObject();
            ids.put("tmdb", tmdb);
            if (ext != null) {
                String imdb = text(ext, "imdb_id");
                if (!imdb.isEmpty()) ids.put("imdb", imdb);
                int tvdb = ext.isNull("tvdb_id") ? 0 : ext.optInt("tvdb_id");
                if (tvdb != 0) ids.put("tvdb", tvdb);
                String wikidata = text(ext, "wikidata_id");
                if (!wikidata.isEmpty()) ids.put("wikidata", wikidata);
            }
            r.put("ids", ids);
            r.put("title", title == null ? "" : title);
            if (original != null && !original.isEmpty() && !original.equals(title)) r.put("original_title", original);
            if (year > 0) r.put("year", year);
            if (episode > 0) {
                r.put("season", season);
                r.put("episode", episode);
            }
            r.put("lang", Tmdb.lang());
            r.put("region", Tmdb.region());
        } catch (Exception ignored) {
        }
        return r;
    }

    private static String text(JSONObject o, String key) {
        if (o.isNull(key)) return "";
        String v = o.optString(key);
        return "null".equals(v) ? "" : v;
    }

    /** Asks every provider in `allowed` at once with one shared deadline; the answers in that order, only providers with
     *  at least one item. Background only. */
    static Map<Provider, List<Item>> ask(Context c, List<Provider> allowed, String requestJson) {
        Map<Provider, List<Item>> out = new LinkedHashMap<>();
        Map<Provider, Future<String>> futures = new LinkedHashMap<>();
        Context app = c.getApplicationContext();
        for (Provider p : allowed) {
            try {
                futures.put(p, POOL.submit((Callable<String>) () -> call(app, p, requestJson)));
            } catch (RejectedExecutionException e) {
                AppLog.w("watch provider " + p.pkg + ": too many questions open");
            }
        }
        long end = SystemClock.uptimeMillis() + TIMEOUT_MS;
        PackageManager pm = c.getPackageManager();
        for (Map.Entry<Provider, Future<String>> e : futures.entrySet()) {
            Provider p = e.getKey();
            Future<String> f = e.getValue();
            String text = null;
            try {
                text = f.get(Math.max(0, end - SystemClock.uptimeMillis()), TimeUnit.MILLISECONDS);
            } catch (Exception ex) {
                f.cancel(true);
                AppLog.w("watch provider " + p.pkg + ": no answer in time");
            }
            if (text == null) continue;
            List<Item> items = new ArrayList<>();
            for (Item it : parse(p, text)) {
                if (opens(pm, it) != null) items.add(it);
            }
            if (!items.isEmpty()) out.put(p, items);
        }
        return out;
    }

    /** One question, on a pool thread, through an unstable client: a provider that crashes cannot take this process down. */
    private static String call(Context c, Provider p, String json) {
        ContentProviderClient client = null;
        try {
            client = c.getContentResolver().acquireUnstableContentProviderClient(p.authority);
            if (client == null) return null;
            Bundle in = new Bundle();
            in.putString("request", json);
            Bundle out = client.call(METHOD, null, in);
            return out == null ? null : out.getString("response");
        } catch (Throwable t) {
            AppLog.w("watch provider " + p.pkg + ": " + t.getClass().getSimpleName());
            return null;
        } finally {
            if (client != null) client.close();
        }
    }

    /** The items of one response: none when it is too big, not JSON, or not v 1. */
    static List<Item> parse(Provider p, String text) {
        List<Item> out = new ArrayList<>();
        if (text == null || text.length() > RESPONSE_MAX) return out;
        try {
            JSONObject o = new JSONObject(text);
            if (o.optInt("v", -1) != 1) return out;
            JSONArray items = o.optJSONArray("items");
            if (items == null) return out;
            for (int i = 0; i < items.length() && i < MAX_ITEMS; i++) {
                JSONObject it = items.optJSONObject(i);
                if (it == null || it.isNull("uri")) continue;
                String uri = it.optString("uri");
                if (uri.isEmpty() || uri.length() > URI_MAX) continue;
                String scheme = Uri.parse(uri).getScheme();
                if (scheme == null || scheme.isEmpty() || "file".equalsIgnoreCase(scheme)) continue;
                String label = it.isNull("label") ? "" : WatchProviderLabels.clean(it.optString("label"), WatchProviderLabels.LABEL_MAX);
                String detail = it.isNull("detail") ? "" : WatchProviderLabels.clean(it.optString("detail"), WatchProviderLabels.DETAIL_MAX);
                boolean play = !it.isNull("action") && "play".equals(it.optString("action"));
                int position = -1, duration = -1;
                JSONObject progress = it.optJSONObject("progress");
                if (progress != null && !progress.isNull("position") && !progress.isNull("duration")) {
                    int pos = progress.optInt("position", -1), dur = progress.optInt("duration", -1);
                    if (dur > 0 && pos >= 0 && pos <= dur) {
                        position = pos;
                        duration = dur;
                    }
                }
                out.add(new Item(p, label, detail, uri, play, position, duration));
            }
        } catch (Exception e) {
            return new ArrayList<>();
        }
        return out;
    }

    // MARK: - Opening

    /** The pinned intent: only the provider's own package can answer it. Null when nothing in it opens the uri. */
    private static Intent opens(PackageManager pm, Item item) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(item.uri)).setPackage(item.provider.pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            return i.resolveActivity(pm) == null ? null : i;
        } catch (Exception e) {
            return null;
        }
    }

    /** Opens an item in its app. Main thread. Never falls back to anything else. */
    static void open(Activity a, Item item) {
        Intent i = opens(a.getPackageManager(), item);
        if (i != null) {
            try {
                a.startActivity(i);
                Recent.note(a, item.provider.pkg);   // the shelf's "Last opened first" counts it too (#63)
                return;
            } catch (Exception ignored) {
            }
        }
        Toast.makeText(a, Strings.get(R.string.watch_provider_couldnt_open), Toast.LENGTH_LONG).show();
    }

    /** Several apps have it: a list to pick from, each with what it says (label, detail, where you stopped). Main thread. */
    static void choose(Activity a, String title, List<Item> items) {
        String[] labels = new String[items.size()];
        for (int i = 0; i < labels.length; i++) {
            Item it = items.get(i);
            List<String> parts = new ArrayList<>();
            if (!it.label.isEmpty()) parts.add(it.label);
            if (!it.detail.isEmpty()) parts.add(it.detail);
            String resume = resumeText(it);
            if (resume != null) parts.add(resume);
            labels[i] = it.provider.label + (parts.isEmpty() ? "" : " · " + TextUtils.join("  ·  ", parts));
        }
        new AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(title)
                .setItems(labels, (d, which) -> open(a, items.get(which)))
                .show();
    }

    /** "Resume from 23:30" when the app says where the person stopped, else null. */
    static String resumeText(Item item) {
        return item.position > 0 ? Strings.get(R.string.resume_from, WatchProviderLabels.clock(item.position)) : null;
    }
}
