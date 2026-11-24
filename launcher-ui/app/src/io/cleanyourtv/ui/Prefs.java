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
        Ui.applyLanguage(app);   // the language picked on the TV, now that it can be read
        fixDreamComponent();
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

    /** "auto", "pt", "en", "es", "de", "ru", "uk", "fr", "tr" or "he". */
    static String language() {
        String v = app == null ? Config.LANGUAGE : sp().getString("language", Config.LANGUAGE);
        v = v.trim().toLowerCase(Locale.ROOT);
        return v.startsWith("ru") ? "ru" : v.startsWith("uk") ? "uk" : v.startsWith("pt") ? "pt" : v.startsWith("es") ? "es" : v.startsWith("de") ? "de"
                : v.startsWith("fr") ? "fr" : v.startsWith("tr") ? "tr" : v.startsWith("he") || v.startsWith("iw") ? "he" : v.startsWith("en") ? "en" : "auto";
    }

    static void setLanguage(String v) { put("language", v); Ui.applyLanguage(app); }

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

    static final String TOP_PICKS = "picks", TOP_CONTINUE = "continue";

    /**
     * What the top of the home screen shows: "picks" (TMDB's, the default) or "continue" (Continue Watching, from
     * Android TV's Watch Next list; it needs READ_TV_LISTINGS, granted from ADB).
     */
    static String topOfScreen() {
        return TOP_CONTINUE.equals(sp().getString("topOfScreen", TOP_PICKS)) ? TOP_CONTINUE : TOP_PICKS;
    }

    static void setTopOfScreen(String v) { put("topOfScreen", TOP_CONTINUE.equals(v) ? TOP_CONTINUE : TOP_PICKS); }

    static boolean continueWatching() { return TOP_CONTINUE.equals(topOfScreen()); }

    /**
     * The screensaver: "photos" (the picks, as before), "aurora" (the clock over the sky, without photos) or "google"
     * (Google TV's own screensaver, which can show a Google Photos album).
     */
    static String screensaverStyle() {
        String v = sp().getString("screensaver", "photos");
        return "aurora".equals(v) || "google".equals(v) ? v : "photos";
    }

    static final String GOOGLE_SCREENSAVER = "com.google.android.apps.tv.dreamx/.service.Backdrop";

    /** Google TV's screensaver is installed and on: a debloat list may have turned dreamx off, or the TV has none. */
    static boolean googleScreensaver() {
        if (app == null) return false;
        try {
            app.getPackageManager().getServiceInfo(android.content.ComponentName.unflattenFromString(GOOGLE_SCREENSAVER), 0);
            return true;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /**
     * Also points the system's screensaver at Google's, or back at ours when it was Google's; that needs
     * WRITE_SECURE_SETTINGS (pm grant). Any other screensaver_components (none, or one set by hand) is left alone.
     */
    static void setScreensaverStyle(String v) {
        boolean google = "google".equals(v);
        if (google && !googleScreensaver()) {
            AppLog.w("screensaver not switched: Google's screensaver (dreamx) isn't installed or is off");
            return;
        }
        put("screensaver", v);
        if (app == null) return;
        android.content.ContentResolver cr = app.getContentResolver();
        if (!google && !GOOGLE_SCREENSAVER.equals(android.provider.Settings.Secure.getString(cr, "screensaver_components"))) return;
        String own = ownDream();
        try {
            android.provider.Settings.Secure.putString(cr, "screensaver_components", google ? GOOGLE_SCREENSAVER : own);
        } catch (SecurityException e) {
            AppLog.w("screensaver not switched, no WRITE_SECURE_SETTINGS: " + e.getMessage());
        }
    }

    /** The screensaver component of this Android version: in the main process on 11 to 12L, in :home otherwise. */
    private static String ownDream() {
        return app.getPackageName() + "/." + (app.getResources().getBoolean(R.bool.dream_alone) ? "ScreensaverAlone" : "Screensaver");
    }

    /**
     * An in-place update across the Android 12 change (#39) leaves screensaver_components on the copy that is now
     * disabled: moves our own value to the right one. Google's or another app's screensaver is never touched.
     */
    private static void fixDreamComponent() {
        try {
            android.content.ContentResolver cr = app.getContentResolver();
            String now = android.provider.Settings.Secure.getString(cr, "screensaver_components");
            String pkg = app.getPackageName();
            String own = ownDream();
            if (now == null || now.equals(own)) return;
            if (!now.equals(pkg + "/.Screensaver") && !now.equals(pkg + "/.ScreensaverAlone")) return;
            android.provider.Settings.Secure.putString(cr, "screensaver_components", own);
        } catch (SecurityException e) {
            AppLog.w("screensaver not moved, no WRITE_SECURE_SETTINGS: " + e.getMessage());
        }
    }

    /** "auto" (12-hour in English, 24-hour otherwise, as before), "24h" or "12h". */
    static String clockFormat() {
        String v = sp().getString("clock", "auto");
        return v.equals("24h") || v.equals("12h") ? v : "auto";
    }

    static void setClockFormat(String v) { put("clock", v); }

    /**
     * Reads the settings again from disk. On Android 11 to 12L the screensaver runs in the main process while the settings
     * are changed in :home, and each process keeps its own copy in memory; this one would miss the change.
     */
    @SuppressWarnings("deprecation")
    static void reload() {
        if (app == null) return;
        app.getSharedPreferences("settings", Context.MODE_MULTI_PROCESS);
        Ui.applyLanguage(app);
    }

    // MARK: - At power-on

    /**
     * After the TV starts from off, never from standby (Home Settings > At power-on, #61; PowerOn): "tv" (the TV's
     * choice, nothing changes), "home" (the home screen, when another app takes the screen right after it came up) or
     * "live" (Live TV).
     */
    static final String[] POWER_ON = {"tv", "home", "live"};

    static String powerOn() {
        String v = sp().getString("powerOn", "tv");
        return v.equals("home") || v.equals("live") ? v : "tv";
    }

    static void setPowerOn(String v) { put("powerOn", v); }

    /** The boot (Settings.Global.BOOT_COUNT) whose first home screen PowerOn saw. Not a setting: no version bump. */
    static int powerOnBoot() { return sp().getInt("powerOnBoot", -1); }
    static void setPowerOnBoot(int boot) { sp().edit().putInt("powerOnBoot", boot).apply(); }

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

    /** Wi-Fi or Ethernet and a USB drive, as small icons by the home screen's round buttons. Off unless config.json sets it. */
    static boolean statusIcons() { return sp().getBoolean("statusIcons", false); }
    static void setStatusIcons(boolean on) { put("statusIcons", on); }
    /** The weather card's forecast: "hours" (the next 6 hours, as before) or "days" (the next 5 days). */
    static String forecast() { return "days".equals(sp().getString("forecast", "hours")) ? "days" : "hours"; }
    static void setForecast(String v) { put("forecast", v); }

    static boolean quotes() { return sp().getBoolean("quotes", Config.SHOW_CRYPTO); }
    static void setQuotes(boolean on) { put("quotes", on); }

    /**
     * Home Settings' Now Playing widget (#67). On by default because it shows nothing until the listener is allowed
     * (build-and-install.sh NOW_PLAYING=1, the explicit step); Off hides it and stops watching.
     */
    static boolean nowPlaying() { return sp().getBoolean("nowPlaying", true); }
    static void setNowPlaying(boolean on) { put("nowPlaying", on); }

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

    /** The shelf puts the app last opened from here first (Recent, #63) instead of the order set in Settings > Shelf. */
    static boolean shelfRecent() { return app != null && sp().getBoolean("shelfRecent", false); }

    /** Off ("My order") also deletes the list of apps opened: nothing is kept while the shelf doesn't use it. */
    static void setShelfRecent(boolean on) {
        put("shelfRecent", on);
        if (!on) Recent.clear(app);
    }

    // MARK: - Drawer inputs (Settings > Inputs, #60)

    /**
     * The drawer's inputs in the person's order: their keys ("live", "hdmi1"...), an input the TV no longer lists keeping
     * its place. null until Settings > Inputs is used: the TV's order then.
     */
    static List<String> inputsOrder() {
        String v = sp().getString("inputsOrder", null);
        return v == null ? null : keyList(v);
    }

    static void setInputsOrder(List<String> keys) { put("inputsOrder", String.join(",", keys)); }

    /** The inputs Settings > Inputs hides; none until it is used. */
    static List<String> inputsHidden() { return keyList(sp().getString("inputsHidden", "")); }

    static void setInputsHidden(List<String> keys) { put("inputsHidden", String.join(",", keys)); }

    private static List<String> keyList(String v) {
        List<String> out = new ArrayList<>();
        for (String p : v.split(",")) if (!p.isEmpty()) out.add(p);
        return out;
    }

    // MARK: - Top buttons

    /** The round buttons at the top right, in order (HomeApps.TOP_ALL keys); null until one is moved. */
    static List<String> topButtons() {
        String v = sp().getString("topButtons", null);
        if (v == null) return null;
        List<String> out = new ArrayList<>();
        for (String key : v.split(",")) if (!key.isEmpty()) out.add(key);
        return out;
    }

    static void setTopButtons(List<String> keys) { put("topButtons", String.join(",", keys)); }

    /** The App Store round button (Google Play, or Amazon Appstore on Fire TV): off until added. */
    static boolean storeButton() { return sp().getBoolean("storeButton", false); }

    static void setStoreButton(boolean on) { put("storeButton", on); }

    // MARK: - Hidden apps

    /** Packages kept out of All Apps (config.json "hidden_apps"), on top of HomeApps' own list. */
    static java.util.Set<String> hidden() {
        return new java.util.HashSet<>(java.util.Arrays.asList(sp().getString("hidden", "").split(",")));
    }

    static void setHidden(List<String> packages) { put("hidden", String.join(",", packages)); }

    // MARK: - Screensaver OK

    /**
     * OK on the screensaver opens the title on screen in its app (Free My TV PR 276). Off (the default): the screensaver
     * is as it always was, any key wakes it, with no hint and no link lookups.
     */
    static boolean screensaverOk() { return sp().getBoolean("screensaverOk", false); }
    static void setScreensaverOk(boolean on) { put("screensaverOk", on); }

    // MARK: - System apps

    /**
     * Also list the TV's own system apps that have a launcher entry (the maker's tools, Files...) in All Apps, the shelf
     * editor and the app pickers. Off (the default) keeps the lists as they were. Factory menus never show (HomeApps).
     */
    static boolean systemApps() { return sp().getBoolean("systemApps", false); }
    static void setSystemApps(boolean on) { put("systemApps", on); }

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

    // MARK: - Trailer player

    /** The app the Trailer button opens, or "" for the built-in pick (YouTube, then another YouTube-capable player). */
    static String trailerPlayer() { return sp().getString("trailerPlayer", ""); }
    static void setTrailerPlayer(String pkg) { put("trailerPlayer", pkg == null ? "" : pkg); }

    /** Explore's banner plays the focused title's trailer, muted, after the focus rests (#113). Off (the default): no WebView, nothing loads. */
    static boolean bannerTrailer() { return sp().getBoolean("bannerTrailer", false); }
    static void setBannerTrailer(boolean on) { sp().edit().putBoolean("bannerTrailer", on).apply(); }   // Explore-only: no version bump

    /** YouTube ids the embedded player refused, oldest first (at most 200). */
    static java.util.Set<String> badTrailers() { return new java.util.LinkedHashSet<>(keyList(sp().getString("badTrailers", ""))); }
    static void addBadTrailer(String id) {
        List<String> all = keyList(sp().getString("badTrailers", ""));
        all.remove(id);
        all.add(id);
        while (all.size() > 200) all.remove(0);
        sp().edit().putString("badTrailers", String.join(",", all)).apply();
    }

    // MARK: - IPTV player

    /** The app the "Onde assistir" row leads with, or "" for no IPTV chip (the default). */
    static String iptvPlayer() { return sp().getString("iptvPlayer", ""); }
    static void setIptvPlayer(String pkg) {
        put("iptvPlayer", pkg == null ? "" : pkg);
        setIptvFallbackWarnCount(0);
    }

    /** How often the "didn't answer the search" heads-up was shown for the current pick. Not a setting:
     *  written without bumping version, so it never makes Home start over. */
    static final int IPTV_FALLBACK_WARN_LIMIT = 3;
    static int iptvFallbackWarnCount() { return sp().getInt("iptvFallbackWarnCount", 0); }
    static void setIptvFallbackWarnCount(int count) { sp().edit().putInt("iptvFallbackWarnCount", count).apply(); }

    // MARK: - Watch providers (#106) - not home-screen settings: never bump version

    /** The answer for an app that offers "Where to watch" results: "on|<signing digest>" or "off|<digest>"; "" before it was asked. */
    static String watchProvider(String pkg) { return sp().getString("watchProvider." + pkg, ""); }
    static void setWatchProvider(String pkg, String value) { sp().edit().putString("watchProvider." + pkg, value).apply(); }

    /** The providers' packages in the person's order; null until one is moved or answered. */
    static List<String> watchProvidersOrder() {
        String v = sp().getString("watchProvidersOrder", null);
        return v == null ? null : keyList(v);
    }

    static void setWatchProvidersOrder(List<String> pkgs) { sp().edit().putString("watchProvidersOrder", String.join(",", pkgs)).apply(); }

    /** Changes whenever an answer or the order does, so a page open behind Settings asks again on return. */
    static int watchProvidersStamp() {
        int h = 0;
        for (java.util.Map.Entry<String, ?> e : sp().getAll().entrySet()) {
            if (e.getKey().startsWith("watchProvider")) h = h * 31 + (e.getKey() + "=" + e.getValue()).hashCode();
        }
        return h;
    }

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

    // MARK: - Live TV button (Settings > Inputs, #60)

    /**
     * Kept in the buttons file under a name no button has (buttons are "s<scancode>" and "k<keycode>"): the key service
     * (:keys) runs the "live" action too, and it doesn't see this process's SharedPreferences.
     */
    private static final String LIVE_BUTTON = "liveButton";

    /** The TV input (its id) the "live" action opens instead of Live TV, or "" for Live TV. */
    static String liveButton() {
        String v = button(LIVE_BUTTON);
        return v == null ? "" : v;
    }

    static void setLiveButton(String inputId) {
        setButton(LIVE_BUTTON, inputId == null || inputId.isEmpty() ? null : inputId);
    }
}
