package io.cleanyourtv.ui;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Settings changed on the TV itself (CleanYourTV UI > Settings) or by a pushed config.json (TvConfig). config.properties
 * only gives the first values of a personal build; a release APK has none. Read from the :home process (home screen, screensaver, settings).
 */
final class Prefs {
    private Prefs() {}

    /** Bumped by every change: screens compare it to know when to redraw. */
    static volatile int version;

    private static Context app;

    static void init(Context c) {
        app = c.getApplicationContext();
        Ui.applyLanguage();   // the language picked on the TV, now that it can be read
    }

    private static SharedPreferences sp() {
        return app.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    private static void put(String key, String value) {
        sp().edit().putString(key, value).apply();
        version++;
    }

    private static void put(String key, boolean value) {
        sp().edit().putBoolean(key, value).apply();
        version++;
    }

    // MARK: - Language and units

    /** "auto", "pt", "en", "es", "de", "ru", "fr", "tr" or "he". */
    static String language() {
        String v = app == null ? Config.LANGUAGE : sp().getString("language", Config.LANGUAGE);
        v = v.trim().toLowerCase(Locale.ROOT);
        return v.startsWith("ru") ? "ru" : v.startsWith("pt") ? "pt" : v.startsWith("es") ? "es" : v.startsWith("de") ? "de"
                : v.startsWith("fr") ? "fr" : v.startsWith("tr") ? "tr" : v.startsWith("he") || v.startsWith("iw") ? "he" : v.startsWith("en") ? "en" : "auto";
    }

    static void setLanguage(String v) { put("language", v); Ui.applyLanguage(); }

    /** "auto", "C" or "F". */
    static String temperature() {
        String v = sp().getString("temperature", Config.TEMPERATURE).trim().toUpperCase(Locale.ROOT);
        return v.startsWith("F") ? "F" : v.startsWith("C") ? "C" : "auto";
    }

    static void setTemperature(String v) { put("temperature", v); }

    /** "auto" (dark from sunset to sunrise), "light" or "dark". */
    static String theme() {
        String v = sp().getString("theme", "auto");
        return v.equals("light") || v.equals("dark") ? v : "auto";
    }

    static void setTheme(String v) { put("theme", v); }

    /** The screensaver: "photos" (the picks, as before) or "aurora" (the clock over the sky, without photos). */
    static String screensaverStyle() {
        return "aurora".equals(sp().getString("screensaver", "photos")) ? "aurora" : "photos";
    }

    static void setScreensaverStyle(String v) { put("screensaver", v); }
    }

    /** "auto" (12-hour in English, 24-hour otherwise, as before), "24h" or "12h". */
    static String clockFormat() {
        String v = sp().getString("clock", "auto");
        return v.equals("24h") || v.equals("12h") ? v : "auto";
    }

    static void setClockFormat(String v) { put("clock", v); }

    /**
     * Reads the settings again from disk. On Android 11 the screensaver runs in the main process while the settings
     * are changed in :home, and each process keeps its own copy in memory; this one would miss the change.
     */
    @SuppressWarnings("deprecation")
    static void reload() {
        if (app == null) return;
        app.getSharedPreferences("settings", Context.MODE_MULTI_PROCESS);
        Ui.applyLanguage();
    }

    // MARK: - City

    static String city() { return sp().getString("city", Config.CITY).trim(); }
    static String latitude() { return sp().getString("latitude", Config.LATITUDE); }
    static String longitude() { return sp().getString("longitude", Config.LONGITUDE); }

    static void setCity(String name, String latitude, String longitude) {
        sp().edit().putString("city", name).putString("latitude", latitude).putString("longitude", longitude).apply();
        version++;
    }

    // MARK: - TMDB and currency (config.json or the TV's Settings; config.properties only for a personal build)

    /** The TMDB API key or read access token; empty means no picks and no Explore. */
    static String tmdbKey() {
        return app == null ? Config.TMDB_KEY.trim() : sp().getString("tmdbKey", Config.TMDB_KEY).trim();
    }

    static void setTmdbKey(String key) { put("tmdbKey", key.trim()); }

    /** Two letters (US, BR, GB...), or "" for the TV's own country. */
    static String tmdbRegion() {
        String v = app == null ? Config.TMDB_REGION : sp().getString("tmdbRegion", Config.TMDB_REGION);
        return v.trim().toUpperCase(Locale.ROOT);
    }

    static void setTmdbRegion(String region) { put("tmdbRegion", region.trim().toUpperCase(Locale.ROOT)); }

    /** The quotes' currency: USD, BRL, EUR, TRY... */
    static String currency() {
        String v = (app == null ? Config.CURRENCY : sp().getString("currency", Config.CURRENCY)).trim().toUpperCase(Locale.ROOT);
        return v.matches("[A-Z]{3}") ? v : "USD";
    }

    static void setCurrency(String currency) { put("currency", currency.trim().toUpperCase(Locale.ROOT)); }

    // MARK: - Widgets and coins

    static boolean weather() { return sp().getBoolean("weather", true); }
    static void setWeather(boolean on) { put("weather", on); }

    static boolean quotes() { return sp().getBoolean("quotes", Config.SHOW_CRYPTO); }
    static void setQuotes(boolean on) { put("quotes", on); }

    static boolean coin(int i) { return sp().getBoolean("coin" + i, true); }
    static void setCoin(int i, boolean on) { put("coin" + i, on); }

    // MARK: - Shelf

    /** Packages on the home shelf, in order; null until the user edits it (then the built-in list applies). */
    static List<String> shelf() {
        String v = sp().getString("shelf", null);
        if (v == null) return null;
        List<String> out = new ArrayList<>();
        for (String p : v.split(",")) if (!p.isEmpty()) out.add(p);
        return out;
    }

    static void setShelf(List<String> packages) {
        StringBuilder sb = new StringBuilder();
        for (String p : packages) sb.append(sb.length() == 0 ? "" : ",").append(p);
        put("shelf", sb.toString());
    }

    // MARK: - Hidden apps

    /** Packages kept out of All Apps (config.json "hidden_apps"), on top of HomeApps' own list. */
    static java.util.Set<String> hidden() {
        return new java.util.HashSet<>(java.util.Arrays.asList(sp().getString("hidden", "").split(",")));
    }

    static void setHidden(List<String> packages) { put("hidden", String.join(",", packages)); }

    // MARK: - Custom tiles

    /** "logo source|#RRGGBB" for a styled shelf tile, or "" (CustomTiles). */
    static String tile(String pkg) { return sp().getString("tile." + pkg, ""); }

    static void setTile(String pkg, String logo, String color) {
        boolean none = (logo == null || logo.trim().isEmpty()) && (color == null || color.trim().isEmpty());
        put("tile." + pkg, none ? "" : (logo == null ? "" : logo.trim()) + "|" + (color == null ? "" : color.trim()));
    }

    // MARK: - Recommendations

    /** What a TMDB watch provider opens: a package, "off", or "" (the built-in match). */
    static String provider(String id) { return sp().getString("provider." + id, ""); }
    static void setProvider(String id, String value) { put("provider." + id, value == null ? "" : value); }

    /** Changes whenever a service's app changes, so the picks are fetched again. */
    static int providersStamp() {
        int h = 0;
        for (java.util.Map.Entry<String, ?> e : sp().getAll().entrySet()) {
            if (e.getKey().startsWith("provider.")) h = h * 31 + (e.getKey() + "=" + e.getValue()).hashCode();
        }
        return h;
    }

    /** Picks from every provider in the region, not only the ones with an app on the TV. */
    static boolean showAllProviders() { return sp().getBoolean("showAllProviders", false); }
    static void setShowAllProviders(boolean on) { put("showAllProviders", on); }

    // MARK: - Remote buttons

    // The key service runs in its own small process (:keys), so that a busy home screen can never delay the TV's keys.
    // SharedPreferences are not shared between processes, so the button choices live in a tiny file both read.
    // A line is "<button>=<action>": the button is "s<scancode>" or "k<keycode>"; RemoteKeys lists the actions.
    private static File keysFile() {
        return new File(app.getFilesDir(), "remote-keys.txt");
    }

    private static final java.util.Map<String, String> buttons = new java.util.HashMap<>();
    private static long keysStamp = Long.MIN_VALUE;

    private static boolean writeKeys(String text) {
        File tmp = new File(app.getFilesDir(), "remote-keys." + android.os.Process.myPid() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return false;
        }
        return tmp.renameTo(keysFile());
    }

    /** Every choice made for a button, re-read only when the file changed. */
    static java.util.Map<String, String> buttons() {
        synchronized (buttons) {
            File f = keysFile();
            long stamp = f.lastModified() * 31 + f.length();
            if (stamp != keysStamp) {
                keysStamp = stamp;
                buttons.clear();
                if (f.isFile()) {
                    try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                        String line;
                        while ((line = r.readLine()) != null) {
                            int eq = line.indexOf('=');
                            if (eq > 1) buttons.put(line.substring(0, eq), line.substring(eq + 1));
                        }
                    } catch (IOException ignored) {
                    }
                }
            }
            return new java.util.HashMap<>(buttons);
        }
    }

    /** The action picked for a button, or null when the person never picked one (remote.json's default applies). */
    static String button(String id) { return buttons().get(id); }

    static void setButton(String id, String action) {
        java.util.Map<String, String> all = buttons();
        if (action == null) all.remove(id); else all.put(id, action);
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, String> e : all.entrySet()) sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        if (writeKeys(sb.toString())) version++;
    }
}
