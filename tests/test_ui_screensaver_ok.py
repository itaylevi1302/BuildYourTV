"""CleanYourTV UI: OK on the screensaver opens the title on screen in its app; any other key closes it (#65).

From Free My TV PR 276 (546b22d, 1a3bec1), opt-in as there (Home Settings, config.json's screensaver_ok; off by
default, then the screensaver is as it was): the dream is interactive, so Android hands it every input
event instead of waking up, and Screensaver must end the dream itself on each one. OK, on its release, opens the
title through DeepLinks (the link already known, never the network) or else the app's home, the way DetailActivity's
"Watch on" does, then finishes. The clock and the aurora have nothing to open, so OK only closes them. TalkBack's click
does the same as OK. ScreensaverAlone (Android 11 to 12L, main process) inherits all of it. No new permission.
This reads the source.

Run from the repository root: python3 -m unittest discover -s tests -p test_ui_screensaver_ok.py -v
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "launcher-ui/app"
SRC = APP / "src/io/cleanyourtv/ui"


def read(name):
    return (SRC / name).read_text(encoding="utf-8")


def method(text, signature):
    start = text.index(signature)
    end = text.find("\n    }\n", start)
    line_end = text.index("\n", start)
    # one-line methods ("{ close(); return true; }") end on their own line
    return text[start:line_end] if text[start:line_end].rstrip().endswith("}") else text[start:end]


class ScreensaverOkTest(unittest.TestCase):
    def setUp(self):
        self.saver = read("Screensaver.java")
        self.dream = read("DreamView.java")
        self.links = read("DeepLinks.java")

    def test_dream_is_interactive(self):
        self.assertIn("setInteractive(watching);", self.saver)
        self.assertIn("watching = Prefs.screensaverOk();", self.saver)
        self.assertNotIn("setInteractive(true)", self.saver)
        self.assertNotIn("setInteractive(false)", self.saver)

    def test_pref_is_read_after_reload_and_defaults_off(self):
        body = method(self.saver, "public void onAttachedToWindow()")
        self.assertLess(body.index("Prefs.reload();"), body.index("Prefs.screensaverOk()"))
        self.assertRegex(read("Prefs.java"), r'static boolean screensaverOk\(\) \{ return sp\(\)\.getBoolean\("screensaverOk", false\); \}')

    def test_off_leaves_every_dispatch_to_the_system(self):
        for sig in ("dispatchKeyEvent(", "dispatchKeyShortcutEvent(", "dispatchTouchEvent(", "dispatchTrackballEvent(",
                    "dispatchGenericMotionEvent("):
            with self.subTest(sig):
                body = method(self.saver, "public boolean " + sig)
                self.assertRegex(body, r"if \(!watching\) return super\.dispatch\w+\(event\);")
        self.assertIn("if (watching) view.setOnClickListener", self.saver)

    def test_off_no_hint_and_no_lookups(self):
        self.assertIn("if (!okOn()) return;", method(self.dream, "private void shown("))
        self.assertIn("!okOn()", method(self.dream, "private Recs.Pick watchable()"))
        self.assertIn("boolean withHint = okOn() && hasApp(s.pick);", self.dream)
        self.assertIn("Prefs.screensaverOk()", method(self.dream, "private static boolean okOn()"))

    def test_config_and_settings_row(self):
        self.assertIn('o.has("screensaver_ok")) Prefs.setScreensaverOk(o.getBoolean("screensaver_ok"))', read("TvConfig.java"))
        self.assertIn('"screensaver_ok": false', (ROOT / "launcher-ui/config.example.json").read_text(encoding="utf-8"))
        self.assertIn("`screensaver_ok`", (ROOT / "launcher-ui/README.md").read_text(encoding="utf-8"))
        settings = read("SettingsActivity.java")
        rows = {k: int(v) for k, v in re.findall(r"\b([A-Z_]+_ROWS?) = (\d+)", settings)}
        self.assertLess(rows["SYSTEM_APPS_ROW"], rows["SAVER_OK_ROW"])
        self.assertLess(rows["SAVER_OK_ROW"], rows["WEATHER_ROW"])
        self.assertIn("case SAVER_OK_ROW: Prefs.setScreensaverOk(!Prefs.screensaverOk());", settings)
        self.assertIn("v[SAVER_OK_ROW] = onOff(Prefs.screensaverOk());", settings)

    def test_ok_is_center_enter_and_numpad_enter(self):
        body = method(self.saver, "static boolean isOk(")
        for key in ("KEYCODE_DPAD_CENTER", "KEYCODE_ENTER", "KEYCODE_NUMPAD_ENTER"):
            self.assertIn(key, body)

    def test_ok_acts_on_release_other_keys_close(self):
        body = method(self.saver, "public boolean dispatchKeyEvent(")
        self.assertIn("isOk(event.getKeyCode())", body)
        self.assertRegex(body, r"ACTION_DOWN\) okDown = true")
        self.assertRegex(body, r"ACTION_UP\)[\s\S]*okDown && !event\.isCanceled\(\)\) ok\(\)")
        self.assertIn("close();", body)
        self.assertEqual(body.count("super.dispatchKeyEvent"), 1)   # only the switch-off line
        self.assertNotIn("return false", body)

    def test_every_other_input_closes(self):
        for sig in ("dispatchKeyShortcutEvent(", "dispatchTouchEvent(", "dispatchTrackballEvent(",
                    "dispatchGenericMotionEvent("):
            with self.subTest(sig):
                body = method(self.saver, "public boolean " + sig)
                self.assertIn("close();", body)
                self.assertEqual(body.count("super."), 1)   # only the switch-off line

    def test_ok_opens_then_finishes(self):
        ok = method(self.saver, "private void ok()")
        self.assertLess(ok.index(".watch()"), ok.index("close();"))
        self.assertIn("finish();", method(self.saver, "private void close()"))

    def test_talkback_click_is_ok(self):
        self.assertIn("view.setOnClickListener(v -> ok());", self.saver)
        self.assertIn("setContentDescription(", method(self.dream, "private void shown("))

    def test_show_declares_watch(self):
        self.assertRegex(self.saver, r"interface Show \{[\s\S]*boolean watch\(\);[\s\S]*?\}")
        self.assertIn("@Override public boolean watch()", self.dream)

    def test_screensaver_alone_inherits_everything(self):
        text = read("ScreensaverAlone.java")
        self.assertRegex(text, r"public class ScreensaverAlone extends Screensaver \{\s*\}")

    def test_watch_uses_known_link_or_app_home_never_network(self):
        body = method(self.dream, "public boolean watch()")
        self.assertIn("watchable()", body)
        self.assertIn("DeepLinks.cached(", body)
        self.assertIn("DeepLinks.open(", body)
        self.assertIn("RemoteKeys.run(getContext(), p.pkg)", body)
        self.assertNotIn("DeepLinks.find(", body)
        self.assertNotIn("new Thread", body)

    def test_nothing_to_watch_on_clock_or_without_app(self):
        body = method(self.dream, "private Recs.Pick watchable()")
        self.assertIn("if (!running || !okOn()) return null;", body)
        self.assertIn("s == null || !hasApp(s.pick) ? null", body)

    def test_link_is_prefetched_off_the_main_thread(self):
        body = method(self.dream, "private void prefetchLink(")
        self.assertIn("DeepLinks.covers(p.pkg)", body)
        self.assertIn("asked.add(", body)
        self.assertRegex(body, r"new Thread\(\(\) -> DeepLinks\.find\(")

    def test_cached_never_asks_wikidata(self):
        for sig in ("static Map<String, String> cached(", "private static Map<String, String> kept("):
            with self.subTest(sig):
                body = method(self.links, sig)
                self.assertNotIn("get(\"https", body)
                self.assertNotIn("find(", body)

    def test_covers_matches_the_links_find_builds(self):
        built = set(re.findall(r'out\.put\("([\w.]+)"', method(self.links, "static Map<String, String> find(")))
        covered = set(re.findall(r'case "([\w.]+)":', method(self.links, "static boolean covers(")))
        self.assertEqual(built, covered)

    def test_caption_shows_the_hint(self):
        body = method(self.dream, "private void recordCaption(")
        self.assertIn("hint(s.pick)", body)
        self.assertIn("R.string.screensaver_ok_hint", self.dream)

    def test_preview_ok_watches_too(self):
        text = read("SettingsActivity.java")
        self.assertIn("Screensaver.isOk(event.getKeyCode()) && !event.isCanceled()) ((Screensaver.Show) preview).watch();", text)
        self.assertIn("new Spring[20]", text)

    def test_hint_in_every_catalogue(self):
        for path in [APP / "res/values/strings.xml", *sorted((APP / "res").glob("values-*/strings.xml"))]:
            with self.subTest(path.parent.name):
                text = path.read_text(encoding="utf-8")
                self.assertRegex(text, r'name="screensaver_ok_hint">"[^"]*%1\$s[^"]*"')
                self.assertIn('name="screensaver_ok_opens_title">"', text)

    def test_no_new_permission(self):
        text = (APP / "AndroidManifest.xml").read_text(encoding="utf-8")
        perms = set(re.findall(r'<uses-permission android:name="([\w.]+)"', text))
        self.assertEqual(perms, {"android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE",
                                 "android.permission.WRITE_SECURE_SETTINGS",
                                 "android.permission.READ_TV_LISTINGS"})   # READ_TV_LISTINGS: Continue Watching (#66), granted only from ADB


if __name__ == "__main__":
    unittest.main()
