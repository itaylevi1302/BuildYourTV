package io.cleanyourtv.ui;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.tv.TvContract;
import android.os.Build;
import android.provider.Settings;
import android.view.InputDevice;
import android.view.KeyEvent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * The remote's buttons. remote.json (examples/<model>/remote.json, pushed by launcher-ui/config.sh --remote) lists them: a
 * "native" button is never touched, a "configurable" one runs the action the person picked in Settings > Remote
 * Buttons, or its default. Without remote.json, TCL and Vestel TVs keep the app buttons Free My TV handled.
 *
 * Actions: a package, "input:<tv input id>", "input:next" (the next HDMI input, press after press), "live", "settings", "home", "tmdb:explore", "tmdb:watchlist", or ""
 * (the button does what it did before).
 */
final class RemoteKeys {
    private RemoteKeys() {}

    static final boolean TCL = is("TCL"), VESTEL = is("Vestel");

    private static boolean is(String maker) {
        return maker.equalsIgnoreCase(Build.MANUFACTURER) || maker.equalsIgnoreCase(Build.BRAND);
    }

    static final class Button {
        final String id, name, dflt;
        Button(String id, String name, String dflt) {
            this.id = id;
            this.name = name;
            this.dflt = dflt;
        }
    }

    private static final Object LOCK = new Object();
    private static List<Button> cached;
    private static String cachedRemote = "";
    private static long cachedStamp = Long.MIN_VALUE;

    /** The configurable buttons, from remote.json (re-read when it changes) or the built-in TCL/Vestel ones. */
    static List<Button> buttons(Context c) {
        synchronized (LOCK) {
            File f = new File(TvConfig.dir(c), "remote.json");
            long stamp = f.lastModified() * 31 + f.length();
            if (cached != null && stamp == cachedStamp) return cached;
            cachedStamp = stamp;
            List<Button> out = new ArrayList<>();
            String text = TvConfig.read(f);
            cachedRemote = "";
            if (text != null) {
                try {
                    JSONObject o = new JSONObject(text);
                    cachedRemote = o.optString("remote");
                    JSONArray list = o.getJSONArray("buttons");
                    for (int i = 0; i < list.length(); i++) {
                        JSONObject b = list.getJSONObject(i);
                        if (!"configurable".equals(b.optString("kind"))) continue;
                        String id = b.has("scan") ? "s" + b.getInt("scan") : b.has("key") ? "k" + b.getInt("key") : null;
                        if (id != null) out.add(new Button(id, b.optString("name", id), b.optString("default")));
                    }
                } catch (Exception e) {
                    AppLog.w("remote.json not read: " + e.getMessage());
                    out.clear();
                }
            }
            if (text == null && VESTEL) {
                out.add(new Button("k" + KeyEvent.KEYCODE_STEM_1, Strings.get(R.string.netflix_button), "com.netflix.ninja"));
            } else if (text == null && TCL) {
                out.add(new Button("k4062", Strings.get(R.string.netflix_button), "com.netflix.ninja"));
                out.add(new Button("k190", Strings.get(R.string.youtube_button), "com.google.android.youtube.tv"));
                out.add(new Button("k4099", Strings.get(R.string.prime_video_button), "com.amazon.amazonvideo.livingroom"));
                out.add(new Button("k4020", Strings.get(R.string.tcl_channel_button), ""));
            }
            cached = out;
            return out;
        }
    }

    /** The remote's name from remote.json, or "" without one. */
    static String remoteName(Context c) {
        buttons(c);
        return cachedRemote;
    }

    static String action(Button b) {
        String picked = Prefs.button(b.id);
        return picked != null ? picked : b.dflt;
    }

    /** Runs the button's action on its first press. True if the key is one of ours and in use (then consumed). */
    static boolean onKey(Context c, KeyEvent e) {
        // BUTTON_3 is also a game controller's button: keys from a gamepad or a joystick are left alone.
        int source = e.getSource();
        if ((source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) return false;
        int keyCode = e.getKeyCode() == 4065 ? KeyEvent.KEYCODE_BUTTON_3 : e.getKeyCode();   // TCL's own YouTube key
        String scan = "s" + e.getScanCode(), key = "k" + keyCode;
        Button found = null;
        for (Button b : buttons(c)) {
            if (b.id.equals(scan)) { found = b; break; }
            if (b.id.equals(key) && found == null) found = b;
        }
        if (found == null) return false;
        String action = action(found);
        if (action.isEmpty()) return false;
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) run(c, action);
        return true;
    }

    static void run(Context c, String action) {
        Intent intent = intent(c, action);
        AppLog.w("remote button -> " + action + (intent == null ? " (not available)" : ""));
        if (intent == null) return;
        try {
            c.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException ignored) {
        }
    }

    static Intent intent(Context c, String action) {
        switch (action) {
            case "home":
                return new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(c.getPackageName());
            case "settings":
                return new Intent(Settings.ACTION_SETTINGS);
            case "live":
                return HomeApps.liveTv(c);
            case "tmdb:explore":    // a fresh Explore: whatever was open in it before is dropped
                return new Intent().setClassName(c, ExploreActivity.class.getName()).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
            case "tmdb:watchlist":
                return new Intent().setClassName(c, ExploreActivity.class.getName()).putExtra(ExploreActivity.EXTRA_SECTION, "watchlist")
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
            case NEXT_INPUT: {
                HomeApps.Item next = nextInput(c);
                if (next == null) return null;
                android.widget.Toast.makeText(c, "HDMI " + next.number + (next.label.equals("HDMI " + next.number) ? "" : " · " + next.label),
                        android.widget.Toast.LENGTH_SHORT).show();
                return input(c, next.inputId);
            }
            default:
                if (action.startsWith("input:")) return input(c, action.substring("input:".length()));
                return launchIntent(c.getPackageManager(), action);
        }
    }

    /** Each press goes to the next HDMI input (see nextInput), instead of one fixed input. */
    static final String NEXT_INPUT = "input:next";

    private static Intent input(Context c, String id) {
        rememberInput(c, id);
        return new Intent(Intent.ACTION_VIEW).setDataAndType(TvContract.buildChannelUriForPassthroughInput(id),
                "vnd.android.cursor.item/channel");
    }

    /**
     * The HDMI input after the last one this app opened, in port order. Android doesn't say which input is on screen,
     * so the app remembers the one it opened last (here and from the home screen). A port takes part when a device on
     * it is on, or when the person named it in the TV's settings (a PC that is off still counts); with none, every
     * port does. TCL reports "connected standby" for a port with a device that is off and for an empty one alike.
     */
    static HomeApps.Item nextInput(Context c) {
        List<HomeApps.Item> all = new ArrayList<>();
        for (HomeApps.Item it : HomeApps.allInputs(c)) if (it.inputId != null) all.add(it);
        if (all.isEmpty()) return null;
        List<HomeApps.Item> ring = new ArrayList<>();
        for (HomeApps.Item it : all) if (it.connected || !it.label.equals("HDMI " + it.number)) ring.add(it);
        if (ring.isEmpty()) ring = all;
        String last = lastInput(c);
        int at = -1;
        for (int i = 0; i < all.size(); i++) if (all.get(i).inputId.equals(last)) at = i;
        HomeApps.Item pick = all.get(0);
        for (int step = 1; step <= all.size(); step++) {
            HomeApps.Item it = all.get((at + step + all.size()) % all.size());
            if (ring.contains(it)) {
                pick = it;
                break;
            }
        }
        StringBuilder log = new StringBuilder("next input: ");
        for (HomeApps.Item it : all) {
            log.append(it == pick ? "[" : "").append("HDMI ").append(it.number).append(it.connected ? " on" : "")
                    .append(ring.contains(it) ? "" : " skipped").append(it.inputId.equals(last) ? " (last)" : "").append(it == pick ? "]" : "").append("; ");
        }
        AppLog.w(log.toString());
        return pick;
    }

    /** Kept in a file: the key service and the home screen run in different processes. */
    static void rememberInput(Context c, String id) {
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(new File(c.getFilesDir(), "last_input"))) {
            out.write(id.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException ignored) {
        }
    }

    private static String lastInput(Context c) {
        String s = TvConfig.read(new File(c.getFilesDir(), "last_input"));
        return s == null ? "" : s.trim();
    }

    private static Intent launchIntent(PackageManager pm, String pkg) {
        Intent intent = pm.getLeanbackLaunchIntentForPackage(pkg);
        return intent != null ? intent : pm.getLaunchIntentForPackage(pkg);
    }
}
