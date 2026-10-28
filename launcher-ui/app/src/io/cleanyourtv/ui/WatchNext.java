package io.cleanyourtv.ui;

import android.content.Context;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * The TV provider's Continue watching (Watch Next) rows and preview programs, as other apps publish them. Needs
 * READ_TV_LISTINGS; without ACCESS_ALL_EPG_DATA a reader sees only rows marked searchable (docs/watch-next.md upstream).
 */
final class WatchNext {
    private WatchNext() {}

    private static final String[] COLUMNS = {"package_name", "title", "episode_title", "season_display_number",
        "episode_display_number", "watch_next_type", "last_engagement_time_utc_millis", "last_playback_position_millis",
        "duration_millis", "poster_art_uri", "intent_uri"};

    /** A text dump for the ADB shell (ConfigProvider "watchnext.txt"): counts, then one tab-separated line per row. */
    static ParcelFileDescriptor dump(Context c) throws FileNotFoundException {
        StringBuilder out = new StringBuilder();
        out.append("# watch_next\n");
        rows(c, TvContract.WatchNextPrograms.CONTENT_URI, out);
        out.append("# preview_program\n");
        rows(c, TvContract.PreviewPrograms.CONTENT_URI, out);
        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            byte[] bytes = out.toString().getBytes(StandardCharsets.UTF_8);
            new Thread(() -> {
                try (OutputStream o = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                    o.write(bytes);
                } catch (IOException ignored) {
                }
            }).start();
            return pipe[0];
        } catch (IOException e) {
            throw new FileNotFoundException(e.getMessage());
        }
    }

    /** One title to continue: what the app published, with how far along it is (0 to 1). */
    static final class Item {
        String pkg, title, intent, imdb, poster;
        int season, episode;
        float progress;
        long engaged;
    }

    /**
     * The titles to continue, most recent first: started (a position past 1 %), not finished (under 97 %), touched in the
     * last 60 days, one per title. Empty without READ_TV_LISTINGS or when no app publishes any.
     */
    static java.util.List<Item> items(Context c) {
        java.util.List<Item> out = new java.util.ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        long since = System.currentTimeMillis() - 60L * 24 * 3600_000L;
        try (Cursor cur = c.getContentResolver().query(TvContract.WatchNextPrograms.CONTENT_URI, null, null, null, null)) {
            while (cur != null && cur.moveToNext()) {
                Item it = new Item();
                it.pkg = text(cur, "package_name");
                it.title = text(cur, "title");
                it.intent = text(cur, "intent_uri");
                it.season = number(cur, "season_display_number");
                it.episode = number(cur, "episode_display_number");
                it.engaged = cur.getLong(Math.max(0, cur.getColumnIndex("last_engagement_time_utc_millis")));
                long pos = cur.getLong(Math.max(0, cur.getColumnIndex("last_playback_position_millis")));
                long dur = cur.getLong(Math.max(0, cur.getColumnIndex("duration_millis")));
                it.progress = dur > 0 ? Math.min(1f, (float) pos / dur) : 0;
                if (it.title.isEmpty() || it.intent.isEmpty() || it.engaged < since) continue;
                if (dur > 0 && (it.progress < 0.01f || it.progress > 0.97f)) continue;
                if (seen.add(it.pkg + "\n" + it.title)) out.add(it);
            }
        } catch (RuntimeException e) {
            AppLog.w("continue watching: " + e.getMessage());
        }
        stremio(c, out, seen);
        // Most recent first; Stremio's rows have no date, so they keep their own order after the dated ones.
        java.util.Collections.sort(out, (a, b) -> Long.compare(b.engaged, a.engaged));
        return out;
    }

    private static final String STREMIO = "com.stremio.one";
    private static final java.util.regex.Pattern IMDB = java.util.regex.Pattern.compile("/(tt\\d+)");
    private static final java.util.regex.Pattern EPISODE = java.util.regex.Pattern.compile("%3A(\\d+)%3A(\\d+)");

    /**
     * Stremio publishes no Watch Next rows, but its preview channel holds what is in progress (a playback position
     * and a stremio:// link with the IMDb id, and season and episode for a show). Started, unfinished titles only.
     */
    private static void stremio(Context c, java.util.List<Item> out, java.util.Set<String> seen) {
        try (Cursor cur = c.getContentResolver().query(TvContract.PreviewPrograms.CONTENT_URI, null, null, null, null)) {
            while (cur != null && cur.moveToNext()) {
                if (!STREMIO.equals(text(cur, "package_name"))) continue;
                long pos = cur.getLong(Math.max(0, cur.getColumnIndex("last_playback_position_millis")));
                long dur = cur.getLong(Math.max(0, cur.getColumnIndex("duration_millis")));
                if (dur <= 0) continue;
                Item it = new Item();
                it.pkg = STREMIO;
                it.title = text(cur, "title");
                it.intent = text(cur, "intent_uri");
                it.progress = Math.min(1f, (float) pos / dur);
                if (it.title.isEmpty() || !it.intent.startsWith("stremio:") || it.progress < 0.02f || it.progress > 0.97f) continue;
                java.util.regex.Matcher m = IMDB.matcher(it.intent);
                if (m.find()) it.imdb = m.group(1);
                m = EPISODE.matcher(it.intent);
                if (m.find()) {
                    it.season = Integer.parseInt(m.group(1));
                    it.episode = Integer.parseInt(m.group(2));
                }
                if (seen.add(it.pkg + "\n" + it.title)) out.add(it);
            }
        } catch (RuntimeException e) {
            AppLog.w("continue watching, Stremio: " + e.getMessage());
        }
    }

    private static final String YOUTUBE = "com.google.android.youtube.tv";

    /** YouTube's recommended videos from its preview channels (single videos only, no playlists), in its order. */
    static java.util.List<Item> youtube(Context c, int max) {
        java.util.List<Item> out = new java.util.ArrayList<>();
        try (Cursor cur = c.getContentResolver().query(TvContract.PreviewPrograms.CONTENT_URI, null, null, null, null)) {
            java.util.Set<String> seen = new java.util.HashSet<>();
            while (cur != null && cur.moveToNext() && out.size() < max) {
                if (!YOUTUBE.equals(text(cur, "package_name"))) continue;
                Item it = new Item();
                it.pkg = YOUTUBE;
                it.title = text(cur, "title");
                it.intent = text(cur, "intent_uri");
                it.poster = text(cur, "poster_art_uri");
                it.progress = -1;
                if (it.title.isEmpty() || !it.intent.contains("watch?v=") || !it.poster.startsWith("https://")) continue;
                if (seen.add(it.intent)) out.add(it);
            }
        } catch (RuntimeException e) {
            AppLog.w("YouTube picks: " + e.getMessage());
        }
        return out;
    }

    private static String text(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        return i < 0 || c.isNull(i) ? "" : c.getString(i);
    }

    private static int number(Cursor c, String col) {
        try {
            return Integer.parseInt(text(c, col).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void rows(Context c, Uri uri, StringBuilder out) {
        // No selection: a caller without ACCESS_ALL_EPG_DATA gets a SecurityException when it passes one.
        try (Cursor cur = c.getContentResolver().query(uri, null, null, null, null)) {
            if (cur == null) {
                out.append("no cursor\n");
                return;
            }
            out.append("count ").append(cur.getCount()).append('\n');
            while (cur.moveToNext() && out.length() < 200_000) {
                for (String col : COLUMNS) {
                    int i = cur.getColumnIndex(col);
                    out.append(i < 0 || cur.isNull(i) ? "" : cur.getString(i).replace('\t', ' ').replace('\n', ' ')).append('\t');
                }
                out.append('\n');
            }
        } catch (RuntimeException e) {
            out.append("error ").append(e).append('\n');
        }
    }
}
