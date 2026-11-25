package io.cleanyourtv.ui;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Build;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Fonts and text for the home screen, the screensaver and the settings. Font: Inter (variable, assets/fonts, SIL OFL),
 * with the "display" optical size for large text and the "text" size for small text; without it, the system font at a
 * similar weight.
 */
final class Ui {
    private Ui() {}

    /** Cheaper drawing paths for older Android versions and TVs with less than 3 GiB of usable RAM. */
    static final boolean LEGACY = Build.VERSION.SDK_INT < 31 || smallMemory();

    private static boolean smallMemory() {
        // LEGACY is initialized before Ui.init provides a Context.
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/meminfo"))) {
            String line = reader.readLine();
            if (line != null && line.startsWith("MemTotal:")) {
                return Long.parseLong(line.replaceAll("\\D+", "")) < 3L * 1024 * 1024;
            }
        } catch (IOException | NumberFormatException | SecurityException ignored) {
        }
        return false;
    }

    static final Locale RU = new Locale("ru", "RU");
    static final Locale PT = new Locale("pt", "BR");
    static final Locale TR = new Locale("tr", "TR");
    static final Locale HE = new Locale("he", "IL");
    /** The launcher language, or the TV's language when the setting is "auto". */
    static volatile String language = language();
    static volatile boolean en = "en".equals(language), es = "es".equals(language), de = "de".equals(language),
            ru = "ru".equals(language), fr = "fr".equals(language), tur = "tr".equals(language),
            he = "he".equals(language), uk = "uk".equals(language);
    static Typeface thin, light, displayMedium, displaySemibold, regular, medium, semibold;
    private static boolean loaded;
    private static final Rect BOUNDS = new Rect();
    private static final RectF OVAL = new RectF();

    static synchronized void init(Context c) {
        if (loaded) return;
        loaded = true;
        AssetManager a = c.getAssets();
        thin = font(a, 32, 200);
        light = font(a, 32, 300);
        displayMedium = font(a, 32, 500);
        displaySemibold = font(a, 32, 600);
        regular = font(a, 14, 400);
        medium = font(a, 14, 500);
        semibold = font(a, 14, 600);
    }

    /** Inter at an optical size (14 = text, 32 = display) and weight; the system font if the file is missing. */
    private static Typeface font(AssetManager a, int opticalSize, int weight) {
        if (has(a, FONT)) {
            try {
                Typeface t = new Typeface.Builder(a, FONT)
                        .setFontVariationSettings("'opsz' " + opticalSize + ", 'wght' " + weight).build();
                if (t != null) return t;
            } catch (RuntimeException ignored) {
            }
        }
        return Typeface.create(Typeface.DEFAULT, weight, false);
    }

    private static final String FONT = "fonts/InterVariable.ttf";

    /** "pt", "es", "de", "ru", "uk", "fr", "tr", "he" or "en": the one picked (TV settings or config.properties), or with "auto" the TV's own, else English. */
    private static String language() {
        String lang = Prefs.language();
        if (!lang.equals("auto")) return lang;
        lang = Locale.getDefault().getLanguage();
        if ("iw".equals(lang)) lang = "he";   // Android reports Hebrew by its old ISO code
        return "pt".equals(lang) || "es".equals(lang) || "de".equals(lang) || "ru".equals(lang) || "uk".equals(lang) || "fr".equals(lang)
                || "tr".equals(lang) || "he".equals(lang) ? lang : "en";
    }

    /** After the language changes in the settings (and once Prefs can read them). */
    static void applyLanguage(Context app) {
        language = language();
        en = "en".equals(language);
        es = "es".equals(language);
        de = "de".equals(language);
        ru = "ru".equals(language);
        fr = "fr".equals(language);
        tur = "tr".equals(language);
        he = "he".equals(language);
        uk = "uk".equals(language);
        Strings.select(app, locale());
    }

    private static boolean has(AssetManager a, String path) {
        try (InputStream in = a.open(path)) {
            return in != null;
        } catch (IOException e) {
            return false;
        }
    }

    static Paint text(Typeface face, float size, int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        p.setTypeface(face);
        p.setTextSize(size);
        p.setColor(color);
        return p;
    }

    /** Fixed-width digits (the clock and the prices don't jitter when a digit changes). */
    static Paint numbers(Typeface face, float size, int color) {
        Paint p = text(face, size, color);
        p.setFontFeatureSettings("'tnum'");
        return p;
    }

    /** Height of capital letters (and digits) above the baseline. */
    static float cap(Paint p) {
        synchronized (BOUNDS) {
            p.getTextBounds("H8", 0, 2, BOUNDS);
            return -BOUNDS.top;
        }
    }

    /** Half the cap height: added to the center line, it gives the baseline that centers the text. */
    static float halfCap(Paint p) {
        return cap(p) / 2f;
    }

    /** Left bearing of the first character (large, thin fonts start a little inward). */
    static float leftBearing(Paint p, String s) {
        if (s.isEmpty()) return 0;
        synchronized (BOUNDS) {
            p.getTextBounds(s, 0, 1, BOUNDS);
            return BOUNDS.left;
        }
    }

    static int alpha(int color, float a) {
        int base = (color >>> 24) & 255;
        return (Math.round(base * Math.max(0, Math.min(1, a))) << 24) | (color & 0xFFFFFF);
    }

    static float clamp(float v) {
        return Math.max(0, Math.min(1, v));
    }

    /** Largest 2w×2h step `downscale` takes (4 MB, freed at once): 1 GB TVs build tiles while Home is open. */
    private static final long SMOOTH_STEP_PIXELS = 1_000_000;

    /**
     * Filtered down to w × h without jagged edges: bilinear filtering reads only 2×2 pixels and software Canvas has no
     * mipmaps, so no step shrinks more than 2×. Halves while it can; when over 1.5× is left, goes to 2w × 2h and halves
     * exactly, else one last step. Never recycles src; may return it (when it already has the size).
     */
    static Bitmap downscale(Bitmap src, int w, int h) {
        w = Math.max(1, w);
        h = Math.max(1, h);
        Bitmap b = src;
        while (b.getWidth() / 2 >= w && b.getHeight() / 2 >= h) {
            Bitmap half = Bitmap.createScaledBitmap(b, b.getWidth() / 2, b.getHeight() / 2, true);
            if (b != src) b.recycle();
            b = half;
        }
        if ((2 * b.getWidth() > 3 * w || 2 * b.getHeight() > 3 * h) && 4L * w * h <= SMOOTH_STEP_PIXELS) {
            Bitmap twice = Bitmap.createScaledBitmap(b, 2 * w, 2 * h, true);
            if (b != src) b.recycle();
            b = twice;
        }
        if (b.getWidth() == w && b.getHeight() == h) return b;
        Bitmap out = Bitmap.createScaledBitmap(b, w, h, true);
        if (b != src && b != out) b.recycle();
        return out;
    }

    /** 4×4 ordered-dither thresholds (0 to 15). */
    static final int[] BAYER = {0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5};

    /**
     * A vertical gradient from top to bottom over `height` px without bands: a 4 px wide strip with a Bayer dither,
     * repeated across (about 4 × height × 4 bytes). Works on every renderer, unlike Paint.setDither on the GPU.
     */
    static Shader gradient(int height, int top, int bottom) {
        int n = Math.max(2, height);
        int[] px = new int[4 * n];
        for (int y = 0; y < n; y++) {
            float t = y / (float) (n - 1);
            for (int x = 0; x < 4; x++) {
                float d = (BAYER[(y & 3) * 4 + x] + 0.5f) / 16f;
                int c = 0;
                for (int s = 0; s < 32; s += 8) {
                    float a = (top >>> s) & 255, b = (bottom >>> s) & 255;
                    c |= Math.min(255, (int) (a + (b - a) * t + d)) << s;
                }
                px[y * 4 + x] = c;
            }
        }
        Bitmap b = Bitmap.createBitmap(px, 4, n, Bitmap.Config.ARGB_8888);
        return new BitmapShader(b, Shader.TileMode.REPEAT, Shader.TileMode.CLAMP);
    }

    /**
     * BuildYourTV's own screens (Settings, Continue watching) still pass their text here: Hebrew from Hebrew.java
     * (keyed by the English), Portuguese as given, English for every other language.
     */
    static String tr(String pt, String english) {
        return he ? Hebrew.text(english) : "pt".equals(language) ? pt : english;
    }

    static String tr(String pt, String english, String spanish) {
        return he ? Hebrew.text(english) : es ? spanish : "pt".equals(language) ? pt : english;
    }

    static Locale locale() {
        return he ? HE : ru ? RU : uk ? Locale.forLanguageTag("uk-UA") : es ? spanish() : de ? german() : fr ? french() : tur ? TR : en ? Locale.US : PT;
    }

    /** The TV's own French (fr-BE, fr-CA…) when it is in French, France's otherwise. */
    private static Locale french() {
        Locale tv = Locale.getDefault();
        return "fr".equals(tv.getLanguage()) ? tv : Locale.FRANCE;
    }

    /** The TV's own Spanish (es-MX, es-AR…) when it is in Spanish, Spain's otherwise. */
    private static Locale spanish() {
        Locale tv = Locale.getDefault();
        return "es".equals(tv.getLanguage()) ? tv : new Locale("es", "ES");
    }

    /** The TV's own German (de-AT, de-CH…) when it is in German, Germany's otherwise. */
    private static Locale german() {
        Locale tv = Locale.getDefault();
        return "de".equals(tv.getLanguage()) ? tv : Locale.GERMANY;
    }

    /** A 12-hour clock: in English, unless Home Settings > Clock format picks one. */
    static boolean twelveHour() {
        String f = Prefs.clockFormat();
        return f.equals("12h") || f.equals("auto") && en;
    }

    /** "21:47" (12-hour, "9:47", like the iPhone in the US). */
    static String clock(long now) {
        return new SimpleDateFormat(twelveHour() ? "h:mm" : "HH:mm", locale()).format(new Date(now));
    }

    /** "5:21" (sunrise and sunset; 12-hour, "5:21 AM"). */
    static String time(long at) {
        boolean twelve = twelveHour();
        return once(twelve ? "time12" : "time24", at, () -> new SimpleDateFormat(twelve ? "h:mm a" : "H:mm", locale()).format(new Date(at)));
    }

    private static final HashMap<String, String> FORMATTED = new HashMap<>();

    /**
     * A formatted value, made once per language: the bottom drawer is redrawn on every frame of its focus animation,
     * and SimpleDateFormat and String.format there cost ~10 ms a frame on a Mi Box 3.
     */
    static String once(String kind, double value, Supplier<String> format) {
        String key = kind + '|' + language + '|' + value;
        synchronized (FORMATTED) {
            String s = FORMATTED.get(key);
            if (s == null) {
                if (FORMATTED.size() > 200) FORMATTED.clear();
                s = format.get();
                FORMATTED.put(key, s);
            }
            return s;
        }
    }

    /** "domingo, 18 de outubro" (lowercase, like the iPhone in Portuguese); "Sunday, October 18" in English; "Sonntag, 18. Oktober". */
    static String date(long now) {
        return ru ? new SimpleDateFormat("EEEE, d MMMM", locale()).format(new Date(now))
                : fr ? new SimpleDateFormat("EEEE d MMMM", locale()).format(new Date(now))   // "samedi 24 octobre"
                : tur ? new SimpleDateFormat("d MMMM EEEE", locale()).format(new Date(now))   // "26 Ekim Pazartesi"
                : he ? new SimpleDateFormat("EEEE, d בMMMM", locale()).format(new Date(now))   // "יום רביעי, 28 באוקטובר"
                : en ? new SimpleDateFormat("EEEE, MMMM d", Locale.US).format(new Date(now))
                : new SimpleDateFormat(de ? "EEEE, d. MMMM" : "EEEE, d 'de' MMMM", locale()).format(new Date(now));
    }

    /** Spinning arc (work in progress). */
    static void spinner(Canvas c, float cx, float cy, float r, float seconds, Paint stroke) {
        OVAL.set(cx - r, cy - r, cx + r, cy + r);
        c.drawArc(OVAL, (seconds * 360f) % 360f, 270f, false, stroke);
    }
}
