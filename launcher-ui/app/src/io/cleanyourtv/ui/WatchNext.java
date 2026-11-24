package io.cleanyourtv.ui;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.net.Uri;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Continue Watching (Home Settings → Top of the screen): what you left half-way in your apps, from Android TV's own
 * lists (TvContract): the system Watch Next list, where Prime Video, Disney+, Max, Plex and others note it, and the
 * home screen rows some apps name "Continue Watching" instead (Stremio). Newest first, PER_APP per app and MAX in all.
 * Reading other apps' entries needs READ_TV_LISTINGS, which only ADB grants (build-and-install.sh WATCH_NEXT=1).
 * Read only while that choice is on, when the home screen comes back (HomeView.reload), never polled; without the
 * permission, or on a TV without the provider, the list is empty. From Free My TV PR 42 (WatchNext.java).
 */
final class WatchNext {
    private WatchNext() {}

    static final String PERMISSION = "android.permission.READ_TV_LISTINGS";
    static final int PER_APP = 2, MAX = 6;
    /** Rows read from one table, at most, whatever the apps wrote there. */
    private static final int READ_MAX = 60;
    /** Channel names that mean "continue watching" (apps name their own channel). */
    private static final Set<String> CHANNEL_NAMES = new HashSet<>(Arrays.asList(
            "continue watching", "seguir viendo", "continuar viendo", "continuar assistindo", "continue assistindo"));

    static final class Item {
        String pkg, title, app, season, number, episodeTitle, intent;
        long duration, position, engaged;
        /** The card's picture (the thumbnail, usually 16:9, before the poster); null when it isn't one we read. */
        Uri picture;

        /** 0 to 1, or -1 when there is no position worth showing (not started, or no length). */
        float progress() {
            if (duration <= 0 || position < 1000 || position >= duration) return -1;
            return (float) position / duration;
        }

        /** Minutes still to watch, or 0 when progress() has none. */
        int minutesLeft() {
            return progress() < 0 ? 0 : (int) Math.max(1, (duration - position) / 60_000);
        }
    }

    /**
     * Pictures on the TV, read through the apps' own providers, or an https address the app left there (Netflix does),
     * which only NetPicture fetches. Never a file, never plain http.
     */
    static Uri picture(String s) {
        if (s == null || s.isEmpty()) return null;
        Uri uri = Uri.parse(s);
        String scheme = uri.getScheme();
        return "content".equalsIgnoreCase(scheme) || "android.resource".equalsIgnoreCase(scheme)
                || "https".equalsIgnoreCase(scheme) ? uri : null;
    }

    static boolean allowed(Context c) {
        return c.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    /** Everything to show, newest first (off the main thread: these are content provider queries). */
    static List<Item> load(Context c) {
        if (!allowed(c)) return Collections.emptyList();
        ContentResolver cr = c.getContentResolver();
        List<Item> all = new ArrayList<>(read(cr, TvContract.WatchNextPrograms.CONTENT_URI));
        for (long channel : continueChannels(cr, c.getPackageName())) {
            all.addAll(read(cr, TvContract.buildPreviewProgramsUriForChannel(channel)));
        }
        Collections.sort(all, (a, b) -> Long.compare(b.engaged, a.engaged));
        List<Item> out = new ArrayList<>();
        Map<String, Integer> perApp = new HashMap<>();
        Set<String> seen = new HashSet<>();
        for (Item it : all) {
            if (it.pkg.equals(c.getPackageName())) continue;
            String key = it.pkg + "|" + (it.intent != null ? it.intent : it.title);
            int n = perApp.containsKey(it.pkg) ? perApp.get(it.pkg) : 0;
            if (n >= PER_APP || !seen.add(key)) continue;
            perApp.put(it.pkg, n + 1);
            it.app = appName(c, it.pkg);
            out.add(it);
            if (out.size() >= MAX) break;
        }
        return out;
    }

    /** Same cards as before (a reload that changes nothing redraws nothing). */
    static boolean same(List<Item> a, List<Item> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            Item x = a.get(i), y = b.get(i);
            if (!x.pkg.equals(y.pkg) || !x.title.equals(y.title) || x.position != y.position || x.duration != y.duration
                    || !String.valueOf(x.picture).equals(String.valueOf(y.picture))) return false;
        }
        return true;
    }

    /** Resumes it where you left it (the app's own link); if that link can't be used, the app from its start. */
    static boolean open(Activity a, Item it) {
        Intent link = it.intent == null ? null : linkIntent(it.intent, it.pkg, a.getPackageName());
        if (link != null && link.resolveActivity(a.getPackageManager()) != null) {
            try {
                a.startActivity(link.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return true;
            } catch (ActivityNotFoundException | SecurityException e) {
                AppLog.w("continue watching link: " + e.getClass().getSimpleName());
            }
        }
        Intent launch = HomeApps.launchIntent(a.getPackageManager(), it.pkg);
        if (launch == null) return false;
        try {
            a.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            AppLog.w("couldn't open " + it.pkg + ": " + e.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * Another app wrote this link: it only ever opens that app (never ours or a third one), with no selector and no
     * URI permissions handed along. Null if it can't be used. From Free My TV PR 42 (HomeView.linkIntent).
     */
    static Intent linkIntent(String uri, String pkg, String self) {
        if (pkg == null || pkg.equals(self)) return null;
        Intent intent;
        try {
            intent = uri.startsWith("intent:") ? Intent.parseUri(uri, Intent.URI_INTENT_SCHEME)
                    : new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
        } catch (java.net.URISyntaxException | RuntimeException e) {   // a bad launchFlags= throws NumberFormatException
            AppLog.w("continue watching link: " + e.getClass().getSimpleName());
            return null;
        }
        if (intent.getComponent() != null && !intent.getComponent().getPackageName().equals(pkg)) return null;
        intent.setSelector(null);
        intent.setPackage(pkg);
        intent.setFlags(intent.getFlags() & ~(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION));
        return intent;
    }

    private static String appName(Context c, String pkg) {
        try {
            PackageManager pm = c.getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    private static List<Long> continueChannels(ContentResolver cr, String self) {
        List<Long> ids = new ArrayList<>();
        try (Cursor cur = cr.query(TvContract.Channels.CONTENT_URI,
                new String[] {TvContract.Channels._ID, TvContract.Channels.COLUMN_DISPLAY_NAME,
                        TvContract.Channels.COLUMN_PACKAGE_NAME}, null, null, null)) {
            if (cur == null) return ids;
            int id = cur.getColumnIndex(TvContract.Channels._ID);
            int name = cur.getColumnIndex(TvContract.Channels.COLUMN_DISPLAY_NAME);
            int pkg = cur.getColumnIndex(TvContract.Channels.COLUMN_PACKAGE_NAME);
            while (cur.moveToNext() && ids.size() < 4) {
                String n = name < 0 ? null : cur.getString(name), p = pkg < 0 ? null : cur.getString(pkg);
                if (n != null && CHANNEL_NAMES.contains(n.trim().toLowerCase(Locale.ROOT)) && !self.equals(p)) {
                    ids.add(cur.getLong(id));
                }
            }
        } catch (RuntimeException e) {   // no permission on some firmware, or no provider at all
            AppLog.w("continue watching channels: " + e.getClass().getSimpleName());
        }
        return ids;
    }

    private static List<Item> read(ContentResolver cr, Uri uri) {
        List<Item> out = new ArrayList<>();
        boolean watchNext = TvContract.WatchNextPrograms.CONTENT_URI.equals(uri);
        List<String> projection = new ArrayList<>(Arrays.asList(
                TvContract.WatchNextPrograms.COLUMN_BROWSABLE,
                TvContract.WatchNextPrograms.COLUMN_TITLE,
                TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME,
                TvContract.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER,
                TvContract.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER,
                TvContract.WatchNextPrograms.COLUMN_EPISODE_TITLE,
                TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS,
                TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
                TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI,
                TvContract.WatchNextPrograms.COLUMN_THUMBNAIL_URI,
                TvContract.WatchNextPrograms.COLUMN_INTENT_URI));
        // Preview programs have no engagement time or Watch Next type.
        if (watchNext) {
            projection.add(TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS);
            projection.add(TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE);
        }
        String order = watchNext ? TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS + " DESC" : null;
        try (Cursor cur = cr.query(uri, projection.toArray(new String[0]), null, null, order)) {
            if (cur == null) return out;
            int rows = 0;
            while (cur.moveToNext() && rows++ < READ_MAX) {
                if ("0".equals(text(cur, TvContract.WatchNextPrograms.COLUMN_BROWSABLE))) continue;
                // Saved for later, not something you're in the middle of.
                if (watchNext && number(cur, TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE)
                        == TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_WATCHLIST) continue;
                String title = text(cur, TvContract.WatchNextPrograms.COLUMN_TITLE);
                String pkg = text(cur, TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME);
                if (title == null || title.trim().isEmpty() || pkg == null) continue;
                Item it = new Item();
                it.pkg = pkg;
                it.title = title.trim();
                String thumbnail = text(cur, TvContract.WatchNextPrograms.COLUMN_THUMBNAIL_URI);
                it.picture = picture(thumbnail != null ? thumbnail : text(cur, TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI));
                it.season = text(cur, TvContract.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER);
                it.number = text(cur, TvContract.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER);
                it.episodeTitle = text(cur, TvContract.WatchNextPrograms.COLUMN_EPISODE_TITLE);
                it.duration = number(cur, TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS);
                it.position = number(cur, TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS);
                it.engaged = number(cur, TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS);
                it.intent = text(cur, TvContract.WatchNextPrograms.COLUMN_INTENT_URI);
                out.add(it);
            }
        } catch (RuntimeException e) {   // a provider that refuses (no permission on some firmware) means nothing to show
            AppLog.w("continue watching: " + e.getClass().getSimpleName());
        }
        return out;
    }

    private static String text(Cursor c, String column) {
        int i = c.getColumnIndex(column);
        return i < 0 || c.isNull(i) ? null : c.getString(i);
    }

    private static long number(Cursor c, String column) {
        int i = c.getColumnIndex(column);
        return i < 0 || c.isNull(i) ? 0 : c.getLong(i);
    }
}
