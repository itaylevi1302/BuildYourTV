package io.cleanyourtv.ui;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Continue Watching's pictures, decoded in the background once, at the card's size on screen (16:9, centre-cropped),
 * and kept in memory: CACHE_MAX of them at most (~0.7 MB each on a 1080p screen), and none while the home screen is
 * hidden (App.onTrimMemory). Only pictures already on the TV are read, through the apps' own providers (content:// and
 * android.resource://); never a file (file://). A picture is sampled down before it's decoded (inSampleSize), so a
 * huge one never lands whole in memory. Anything else, or a picture that can't be read, shows the app's TV banner
 * instead, and isn't tried again for RETRY_MS. From Free My TV PR 42 (WatchArt.java) and 8490c56 (sampled decoding).
 */
final class WatchArt {
    private static final int MAX_BYTES = 8 << 20, CACHE_MAX = 8;
    /** At most this many pixels decoded (~8 MB): a 60000×300 picture is sampled down before it's decoded. */
    private static final long MAX_PIXELS = 2_000_000;
    private static final long RETRY_MS = 10 * 60_000L, READ_MS = 12_000;
    /** One thread for the whole process: a home screen started over doesn't leave one behind. */
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static volatile WatchArt live;

    private final Context app;
    private final Runnable onLoaded;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean closed;
    private volatile int pw = 1, ph = 1;   // the pictures' size in pixels (setSize)
    private int generation;                // bumped by setSize and clear: a picture decoded before is dropped
    private final Set<String> pending = new HashSet<>();
    private final Map<String, Long> failed = new HashMap<>();
    private final Map<Bitmap, BitmapShader> shaders = new HashMap<>();   // for the rounded corners, made with the picture
    private final Map<String, Bitmap> cache = new LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
            if (size() <= CACHE_MAX) return false;
            shaders.remove(eldest.getValue());
            return true;
        }
    };

    WatchArt(Context c, Runnable onLoaded) {
        app = c.getApplicationContext();
        this.onLoaded = onLoaded;
        live = this;
    }

    /** The home screen left the screen (App.onTrimMemory): the pictures go, and are made again when it's back. */
    static void trimLoaded() {
        WatchArt w = live;
        if (w != null) w.clear();
    }

    /** The card's picture, or the app's banner if it has none or it failed; null while loading (onLoaded follows). */
    Bitmap get(WatchNext.Item it) {
        if (it.picture != null) {
            String key = it.picture.toString();
            Bitmap b = fetch(key, it.picture, it.pkg);
            if (b != null || !failedRecently(key)) return b;
        }
        return fetch("banner:" + it.pkg, null, it.pkg);
    }

    /** The cards' size on screen, in pixels: pictures made at another size are made again. */
    void setSize(int width, int height) {
        width = Math.max(1, width);
        height = Math.max(1, height);
        if (width == pw && height == ph) return;
        pw = width;
        ph = height;
        clear();
    }

    /** The picture's shader (get's pictures only). */
    BitmapShader shader(Bitmap b) {
        synchronized (cache) {
            return shaders.get(b);
        }
    }

    /** Lets the pictures go (Continue Watching turned off, the screen's size changed, or the home screen hidden). */
    void clear() {
        synchronized (cache) {
            cache.clear();
            shaders.clear();
            failed.clear();
            generation++;
        }
    }

    /** The home screen went away: nothing more is posted to it, and the pictures go. */
    void close() {
        closed = true;
        if (live == this) live = null;
        clear();
    }

    private boolean failedRecently(String key) {
        synchronized (cache) {
            Long at = failed.get(key);
            return at != null && System.currentTimeMillis() - at < RETRY_MS;
        }
    }

    private Bitmap fetch(String key, Uri uri, String pkg) {
        if (closed) return null;
        final int gen;
        synchronized (cache) {
            Bitmap b = cache.get(key);
            if (b != null || pending.contains(key)) return b;
            Long at = failed.get(key);
            if (at != null && System.currentTimeMillis() - at < RETRY_MS) return null;
            pending.add(key);
            gen = generation;
        }
        final int w = pw, h = ph;
        IO.execute(() -> {
            Bitmap b = null;
            if (!closed) {
                try {
                    b = uri == null ? banner(pkg, w, h) : load(uri, w, h);
                } catch (IOException | RuntimeException | OutOfMemoryError e) {
                    // Never the address: it says what someone watches.
                    AppLog.w("continue watching picture: " + e.getClass().getSimpleName());
                }
            }
            BitmapShader shader = b == null ? null : new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            synchronized (cache) {
                pending.remove(key);
                if (!closed && gen == generation) {
                    if (b != null) {
                        shaders.put(b, shader);
                        cache.put(key, b);
                    } else {
                        failed.put(key, System.currentTimeMillis());
                    }
                }
            }
            main.post(() -> { if (!closed) onLoaded.run(); });   // the picture, the banner in its place, or ask again
        });
        return null;
    }

    private Bitmap load(Uri uri, int cw, int ch) throws IOException {
        byte[] data = read(uri);
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("unreadable picture");
        int bw = bounds.outWidth, bh = bounds.outHeight;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = 1;
        // Halved while both sides stay at least the card's, or while it's still too many pixels (either side too big).
        for (int s = 1; bw / (s * 2) >= cw && bh / (s * 2) >= ch || (long) (bw / s) * (bh / s) > MAX_PIXELS; s *= 2) {
            opts.inSampleSize = s * 2;
        }
        Bitmap src = BitmapFactory.decodeByteArray(data, 0, data.length, opts);
        if (src == null) throw new IOException("unreadable picture");
        // Centre crop to the card's shape.
        int sw = src.getWidth(), sh = src.getHeight();
        Rect from = (long) sw * ch > (long) sh * cw ? new Rect((sw - sh * cw / ch) / 2, 0, (sw + sh * cw / ch) / 2, sh)
                : new Rect(0, (sh - sw * ch / cw) / 2, sw, (sh + sw * ch / cw) / 2);
        Bitmap out = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888);
        new Canvas(out).drawBitmap(src, from, new Rect(0, 0, cw, ch), new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG));
        src.recycle();
        return out;
    }

    /** The app's TV banner (or its icon) on a dark card, as the shelf shows apps without a logo. */
    private Bitmap banner(String pkg, int cw, int ch) throws IOException {
        PackageManager pm = app.getPackageManager();
        Drawable d;
        try {
            d = pm.getApplicationBanner(pkg);
            if (d == null) d = pm.getApplicationIcon(pkg);
        } catch (PackageManager.NameNotFoundException e) {
            throw new IOException("no app");
        }
        Bitmap out = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawColor(0xFF15161C);
        // TV banners are 16:9 already: edge to edge; an icon goes in the middle.
        boolean wide = d.getIntrinsicWidth() > d.getIntrinsicHeight();
        int s = Math.round(ch * 0.5f);
        if (wide) d.setBounds(0, 0, cw, ch);
        else d.setBounds((cw - s) / 2, (ch - s) / 2, (cw + s) / 2, (ch + s) / 2);
        d.draw(c);
        return out;
    }

    /** content:// and android.resource:// come from the app's own provider, with a size and a time limit. */
    private byte[] read(Uri uri) throws IOException {
        try (InputStream in = app.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("no picture there");
            return readAll(in, MAX_BYTES, System.currentTimeMillis() + READ_MS);
        }
    }

    /** Reads it all, up to a size and until a moment (a provider that trickles bytes can't hold a card's place). */
    static byte[] readAll(InputStream in, int max, long deadline) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16384];
        int n;
        while ((n = in.read(buffer)) > 0) {
            out.write(buffer, 0, n);
            if (out.size() > max) throw new IOException("picture too large");
            if (System.currentTimeMillis() > deadline) throw new IOException("picture took too long");
        }
        return out.toByteArray();
    }
}
