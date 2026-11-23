package io.cleanyourtv.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * A shelf tile the person styled: a logo (a PNG from a URL, a file pushed with launcher-ui/config.sh, or the app's own
 * icon) on a background in their color, drawn like the built-in brands. Set in config.json "app_tiles" or on the TV
 * (hold OK on a shelf app, or Settings > Shelf and Menu on an app).
 */
final class CustomTiles {
    private CustomTiles() {}

    /** {logo source, "#RRGGBB"} for the package, or null when it keeps its own look. */
    static String[] get(String pkg) {
        String v = Prefs.tile(pkg);
        if (v.isEmpty()) return null;
        int bar = v.lastIndexOf('|');
        return bar < 0 ? new String[] {v, ""} : new String[] {v.substring(0, bar), v.substring(bar + 1)};
    }

    static int color(String pkg) {
        String[] t = get(pkg);
        try {
            return t == null || t[1].isEmpty() ? 0xFF1C1C1E : Color.parseColor(t[1]);
        } catch (IllegalArgumentException e) {
            return 0xFF1C1C1E;
        }
    }

    /** The tile's colors, top and bottom, like HomeView's built-in styles; the logo is drawn as it is. */
    static int[] style(String pkg) {
        int top = color(pkg);
        float[] hsv = new float[3];
        Color.colorToHSV(top, hsv);
        hsv[2] *= 0.62f;
        return new int[] {top, Color.HSVToColor(hsv), 0};
    }

    /**
     * The logo: the cached download, the pushed file, or the app's icon. A URL not cached yet is downloaded only when
     * `download` is true (the shelf's background load); until then this returns null and the shelf keeps the TV art.
     */
    static Bitmap logo(Context c, String pkg, boolean download) {
        String[] t = get(pkg);
        if (t == null) return null;
        String source = t[0].trim();
        if (source.isEmpty()) return icon(c, pkg);
        if (!source.startsWith("https://")) {
            if (!TvConfig.allowedName(source)) return null;
            return decode(new File(TvConfig.dir(c), new File(source).getName()));
        }
        File cached = new File(new File(c.getFilesDir(), "tiles"), Integer.toHexString((pkg + source).hashCode()) + ".png");
        if (cached.isFile()) return decode(cached);
        if (!download) return null;
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(source).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(15000);
            Bitmap b;
            try (InputStream in = conn.getInputStream()) {
                b = BitmapFactory.decodeStream(in);
            } finally {
                conn.disconnect();
            }
            if (b == null) return null;
            b = fit(b);
            //noinspection ResultOfMethodCallIgnored
            cached.getParentFile().mkdirs();
            try (FileOutputStream out = new FileOutputStream(cached)) {
                b.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
            return b;
        } catch (Exception e) {
            AppLog.w("tile logo for " + pkg + ": " + e.getMessage());
            return null;
        }
    }

    private static Bitmap decode(File f) {
        if (!f.isFile()) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        return b == null ? null : fit(b);
    }

    private static Bitmap fit(Bitmap b) {
        int largest = Math.max(b.getWidth(), b.getHeight());
        if (largest <= 640) return b;
        float s = 640f / largest;
        return Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * s), Math.round(b.getHeight() * s), true);
    }

    private static Bitmap icon(Context c, String pkg) {
        try {
            Drawable d = c.getPackageManager().getApplicationIcon(pkg);
            Bitmap b = Bitmap.createBitmap(384, 384, Bitmap.Config.ARGB_8888);
            d.setBounds(0, 0, 384, 384);
            d.draw(new Canvas(b));
            return b;
        } catch (Exception e) {
            return null;
        }
    }

    /** The logo-and-color form; `saved` runs after Save or Original (null: nothing to refresh). */
    static void edit(Activity a, String pkg, String label, Runnable saved) {
        String[] now = get(pkg);
        float k = TvUi.k(a);
        LinearLayout form = new LinearLayout(a);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(24 * k);
        form.setPadding(pad, pad, pad, 0);
        EditText url = new EditText(a);
        url.setSingleLine(true);
        url.setHint(Strings.get(R.string.a_pngs_url_empty_the_apps_icon));
        url.setText(now == null ? "" : now[0]);
        EditText color = new EditText(a);
        color.setSingleLine(true);
        color.setHint(Strings.get(R.string.background_color_e_g_e53935));
        color.setText(now == null ? "" : now[1]);
        form.addView(url);
        form.addView(color);
        new AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(Strings.get(R.string.logo_and_color_prefix) + label)
                .setView(form)
                .setPositiveButton(Strings.get(R.string.save), (d, w) -> {
                    String hex = color.getText().toString().trim();
                    if (!hex.isEmpty() && !hex.startsWith("#")) hex = "#" + hex;
                    try {
                        if (!hex.isEmpty()) Color.parseColor(hex);
                    } catch (IllegalArgumentException e) {
                        Toast.makeText(a, Strings.get(R.string.invalid_color_use_rrggbb), Toast.LENGTH_LONG).show();
                        return;
                    }
                    Prefs.setTile(pkg, url.getText().toString(), hex.isEmpty() ? "#1C1C1E" : hex);
                    if (saved != null) saved.run();
                })
                .setNeutralButton(Strings.get(R.string.original), (d, w) -> {
                    Prefs.setTile(pkg, null, null);
                    if (saved != null) saved.run();
                })
                .setNegativeButton(Strings.get(R.string.cancel), null)
                .show();
    }
}
