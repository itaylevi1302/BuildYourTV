package io.cleanyourtv.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.content.pm.PackageManager;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Settings > Services: the region's streaming services and the app that opens each one ("Automatic" = the official
 * app when installed, "Off" = left out of Explore and the picks), and whether to show titles from every service.
 */
public class ServicesActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private float k;
    private LinearLayout list;
    private final List<String[]> apps = new ArrayList<>();
    private List<WatchProviders.Provider> watchProviders;   // the apps that offer Where to watch results; null until known
    private List<Providers.Provider> lastProviders;         // what build() last showed, to build again after a change

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        k = TvUi.k(this);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(TvUi.BG);
        ScrollView scroll = new ScrollView(this);
        scroll.setFocusable(false);
        scroll.setVerticalScrollBarEnabled(false);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(Math.round(260 * k), Math.round(70 * k), Math.round(260 * k), Math.round(80 * k));
        scroll.addView(list);
        root.addView(scroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        for (String[] a : HomeApps.launchable(this)) if (!a[0].equals(getPackageName())) apps.add(a);
        Collections.sort(apps, (a, b) -> a[1].toLowerCase(Locale.ROOT).compareTo(b[1].toLowerCase(Locale.ROOT)));
        build(null);
        refreshWatchProviders(false);
    }

    /** Looks the apps up on a thread (it reads their manifests), then shows them; rebuilds even when the services are not in yet. */
    private void refreshWatchProviders(boolean rebuild) {
        new Thread(() -> {
            List<WatchProviders.Provider> found = WatchProviders.found(this);
            main.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                watchProviders = found;
                if (rebuild || lastProviders != null) build(lastProviders);
            });
        }).start();
    }

    private void build(List<Providers.Provider> providers) {
        lastProviders = providers;
        View focused = getCurrentFocus();
        Object focusTag = focused == null ? null : focused.getTag();
        list.removeAllViews();
        TextView h = TvUi.text(this, k, 56, Ui.displaySemibold, TvUi.INK);
        h.setText(Strings.get(R.string.services));
        list.addView(h);
        TextView sub = TvUi.text(this, k, 26, Ui.regular, TvUi.DIM);
        sub.setText(Strings.get(R.string.service_app_settings_hint));
        sub.setPadding(0, Math.round(12 * k), 0, Math.round(26 * k));
        list.addView(sub);
        list.addView(row("trailer", null, Strings.get(R.string.movie_tv_trailers), trailerPlayerValue(), v -> pickTrailerPlayer(providers)));
        boolean webView = BannerTrailer.available();
        list.addView(row("bannerTrailer", null, Strings.get(R.string.trailer_in_the_banner),
                !webView ? Strings.get(R.string.trailer_banner_unavailable) : Prefs.bannerTrailer() ? Strings.get(R.string.on) : Strings.get(R.string.off),
                v -> {
                    if (!webView) return;
                    Prefs.setBannerTrailer(!Prefs.bannerTrailer());
                    build(providers);
                }));
        list.addView(row("iptv", null, Strings.get(R.string.iptv_app), iptvPlayerValue(), v -> pickIptvPlayer(providers)));
        list.addView(row("all", null, Strings.get(R.string.show_every_service),
                Prefs.showAllProviders() ? Strings.get(R.string.on) : Strings.get(R.string.off), v -> {
                    Prefs.setShowAllProviders(!Prefs.showAllProviders());
                    build(providers);
                }));
        if (watchProviders != null && !watchProviders.isEmpty()) watchProvidersSection();
        if (providers == null) {
            TextView loading = TvUi.text(this, k, 26, Ui.regular, TvUi.DIM);
            loading.setText(Strings.get(R.string.loading_the_services));
            loading.setPadding(0, Math.round(30 * k), 0, 0);
            list.addView(loading);
            new Thread(() -> {
                try {
                    List<Providers.Provider> got = Providers.region();
                    main.post(() -> build(got.size() > 40 ? got.subList(0, 40) : got));
                } catch (Exception e) {
                    main.post(() -> loading.setText(Strings.get(R.string.couldnt_fetch_the_services_prefix) + e.getMessage()));
                }
            }).start();
        } else {
            for (Providers.Provider p : providers) {
                list.addView(row(p.id, p.logo, p.name, value(p), v -> pick(p, providers)));
            }
        }
        View restore = focusTag == null ? null : list.findViewWithTag(focusTag);
        (restore != null ? restore : list.getChildAt(2)).requestFocus();
    }

    /** Settings for the apps that offer results in Where to watch: OK switches one on or off, left and right move it. */
    private void watchProvidersSection() {
        TextView head = TvUi.text(this, k, 32, Ui.semibold, TvUi.INK);
        head.setText(Strings.get(R.string.watch_providers_section));
        head.setPadding(0, Math.round(26 * k), 0, 0);
        list.addView(head);
        TextView hint = TvUi.text(this, k, 24, Ui.regular, TvUi.DIM);
        hint.setText(Strings.get(R.string.watch_providers_hint));
        hint.setPadding(0, Math.round(8 * k), 0, Math.round(16 * k));
        list.addView(hint);
        List<WatchProviders.Provider> shown = watchProviders;
        for (WatchProviders.Provider p : shown) {
            Drawable icon = null;
            try {
                icon = getPackageManager().getApplicationIcon(p.pkg);
            } catch (PackageManager.NameNotFoundException ignored) {
            }
            View row = iconRow("wp:" + p.pkg, icon, p.label, p.state == WatchProviders.State.ON ? Strings.get(R.string.on) : Strings.get(R.string.off), v -> {
                if (p.state == WatchProviders.State.ON) {
                    WatchProviders.decide(p, false);
                    refreshWatchProviders(true);
                } else {
                    askConsent(p);
                }
            });
            row.setOnKeyListener((v, code, ev) -> {
                if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (code != KeyEvent.KEYCODE_DPAD_LEFT && code != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
                int dir = (code == KeyEvent.KEYCODE_DPAD_LEFT) != Ui.he ? -1 : 1;   // mirrored in Hebrew, like the home screen
                WatchProviders.move(shown, p, dir);
                refreshWatchProviders(true);
                return true;
            });
            list.addView(row);
        }
    }

    /** The question the title page asks, again: Back records nothing. */
    private void askConsent(WatchProviders.Provider p) {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(p.label)
                .setMessage(Strings.get(R.string.watch_provider_consent, p.appLabel))
                .setPositiveButton(Strings.get(R.string.allow), (d, w) -> {
                    WatchProviders.decide(p, true);
                    refreshWatchProviders(true);
                })
                .setNegativeButton(Strings.get(R.string.not_now), (d, w) -> {
                    WatchProviders.decide(p, false);
                    refreshWatchProviders(true);
                })
                .show();
    }

    private String value(Providers.Provider p) {
        String pick = Prefs.provider(String.valueOf(p.id));
        if ("off".equals(pick)) return Strings.get(R.string.off);
        String app = Providers.app(this, p.id, p.name);
        if (app == null) return Strings.get(R.string.no_app_on_the_tv_2);
        return (pick.isEmpty() ? Strings.get(R.string.automatic_prefix) : "") + Providers.label(this, app);
    }

    private String trailerPlayerValue() {
        String picked = Prefs.trailerPlayer();
        if (!picked.isEmpty() && Providers.installed(this, picked)) return Providers.label(this, picked);
        return Strings.get(R.string.automatic);
    }

    private void pickTrailerPlayer(List<Providers.Provider> providers) {
        List<String> values = new ArrayList<>(), labels = new ArrayList<>();
        values.add("");
        labels.add(Strings.get(R.string.automatic));
        for (String pkg : Providers.youtubePlayers(this)) {
            values.add(pkg);
            labels.add(Providers.label(this, pkg));
        }
        String current = Prefs.trailerPlayer();
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(Strings.get(R.string.movie_tv_trailers))
                .setSingleChoiceItems(labels.toArray(new String[0]), Math.max(0, values.indexOf(current)), (d, which) -> {
                    Prefs.setTrailerPlayer(values.get(which));
                    d.dismiss();
                    build(providers);
                })
                .show();
    }

    private String iptvPlayerValue() {
        String picked = Prefs.iptvPlayer();
        return !picked.isEmpty() && Providers.installed(this, picked) ? Providers.label(this, picked) : Strings.get(R.string.off);
    }

    /** Any installed app: nothing in an IPTV player's manifest tells it apart from other apps. */
    private void pickIptvPlayer(List<Providers.Provider> providers) {
        List<String> values = new ArrayList<>(), labels = new ArrayList<>();
        values.add("");
        labels.add(Strings.get(R.string.off));
        for (String[] a : apps) {
            values.add(a[0]);
            labels.add(a[1]);
        }
        String current = Prefs.iptvPlayer();
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(Strings.get(R.string.iptv_app))
                .setSingleChoiceItems(labels.toArray(new String[0]), Math.max(0, values.indexOf(current)), (d, which) -> {
                    Prefs.setIptvPlayer(values.get(which));
                    d.dismiss();
                    build(providers);
                })
                .show();
    }

    private void pick(Providers.Provider p, List<Providers.Provider> providers) {
        List<String> values = new ArrayList<>(), labels = new ArrayList<>();
        String official = Providers.officialApp(this, p.name);
        values.add("");
        labels.add(Strings.get(R.string.automatic) + " · " + (official == null ? Strings.get(R.string.no_official_app) : Providers.label(this, official)));
        values.add("off");
        labels.add(Strings.get(R.string.off));
        for (String[] a : apps) {
            values.add(a[0]);
            labels.add(a[1]);
        }
        String current = Prefs.provider(String.valueOf(p.id));
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(p.name)
                .setSingleChoiceItems(labels.toArray(new String[0]), Math.max(0, values.indexOf(current)), (d, which) -> {
                    Prefs.setProvider(String.valueOf(p.id), values.get(which));
                    d.dismiss();
                    build(providers);
                })
                .show();
    }

    private View row(Object tag, String logo, String name, String value, View.OnClickListener click) {
        return fullRow(tag, null, logo, name, value, click);
    }

    private View iconRow(Object tag, Drawable icon, String name, String value, View.OnClickListener click) {
        return fullRow(tag, icon, null, name, value, click);
    }

    private View fullRow(Object tag, Drawable icon, String logo, String name, String value, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(this);
        row.setTag(tag);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Math.round(26 * k), Math.round(14 * k), Math.round(30 * k), Math.round(14 * k));
        if (logo != null || icon != null) {
            ImageView img = new ImageView(this);
            img.setClipToOutline(true);
            img.setBackground(TvUi.round(0x00000000, 10 * k));
            if (icon != null) img.setImageDrawable(icon);
            else Images.load(img, logo.isEmpty() ? null : Tmdb.LOGO + logo);
            int s = Math.round(52 * k);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(s, s);
            lp.rightMargin = Math.round(22 * k);
            row.addView(img, lp);
        }
        TextView n = TvUi.text(this, k, 30, Ui.medium, TvUi.INK);
        n.setText(name);
        row.addView(n, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView v = TvUi.text(this, k, 26, Ui.regular, TvUi.DIM);
        v.setText(value);
        row.addView(v);
        row.setFocusable(true);
        row.setOnClickListener(click);
        row.setOnFocusChangeListener((view, has) -> {
            view.setBackground(has ? TvUi.round(TvUi.INK, 18 * k) : null);
            n.setTextColor(has ? 0xFF14161A : TvUi.INK);
            v.setTextColor(has ? 0x9914161A : TvUi.DIM);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Math.round(6 * k);
        row.setLayoutParams(lp);
        return row;
    }
}
