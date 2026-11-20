package io.cleanyourtv.ui;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.LocaleList;

import java.util.Locale;

/** Resource lookup for views drawn in the language selected in the launcher. */
final class Strings {
    private static volatile Resources resources;

    private Strings() {}

    static void select(Context app, Locale locale) {
        Configuration config = new Configuration(app.getResources().getConfiguration());
        config.setLocales(new LocaleList(locale));
        resources = app.createConfigurationContext(config).getResources();
    }

    static String get(int id) { return resources.getString(id); }

    static String get(int id, Object... args) { return resources.getString(id, args); }

    static String quantity(int id, int count) { return resources.getQuantityString(id, count, count); }

    static String duration(int minutes) {
        return minutes >= 60 ? get(R.string.duration_hours_minutes, minutes / 60, minutes % 60)
                : get(R.string.duration_minutes, minutes);
    }
}
