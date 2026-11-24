"""CleanYourTV UI: the optional muted trailer behind Explore's banner (#113): off by default, no WebView unless on.

This reads the source. Run from the repository root: python3 -m unittest tests.test_ui_banner_trailer -v
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "launcher-ui/app"
SRC = APP / "src/io/cleanyourtv/ui"


def read(name):
    return (SRC / name).read_text(encoding="utf-8")


def body(text, signature):
    start = text.index(signature)
    brace = text.index("{", start)
    depth = 0
    for i in range(brace, len(text)):
        depth += {"{": 1, "}": -1}.get(text[i], 0)
        if depth == 0:
            return text[start:i + 1]
    raise AssertionError(signature)


class BannerTrailerTest(unittest.TestCase):
    def setUp(self):
        self.explore = read("ExploreActivity.java")
        self.trailer = read("BannerTrailer.java")
        self.prefs = read("Prefs.java")

    def test_off_by_default_and_no_version_bump(self):
        self.assertRegex(self.prefs, r'static boolean bannerTrailer\(\) \{ return sp\(\)\.getBoolean\("bannerTrailer", false\); \}')
        setter = self.prefs[self.prefs.index("static void setBannerTrailer"):].split("\n", 1)[0]
        self.assertNotIn("put(", setter)

    def test_a_webview_is_made_only_in_the_trailer_class(self):
        for path in SRC.glob("*.java"):
            if path.name != "BannerTrailer.java":
                self.assertNotIn("new WebView(", path.read_text(encoding="utf-8"), path.name)

    def test_nothing_starts_with_the_setting_off(self):
        self.assertTrue(body(self.explore, "private void maybeStartTrailer()").split("{", 1)[1].lstrip().startswith("if (!Prefs.bannerTrailer()) return;"))
        self.assertIn("Prefs.bannerTrailer()", body(self.explore, "private void scheduleTrailer()"))
        self.assertIn("postDelayed(trailerSoon, 1000)", self.explore)

    def test_it_is_released_when_not_needed(self):
        for sig in ("protected void onStop()", "protected void onDestroy()", "private void showPage("):
            self.assertIn("releaseTrailer()", body(self.explore, sig), sig)
        self.assertIn("ACTION_SCREEN_OFF", self.explore)
        for sig in ("protected void onPause()", "public void onWindowFocusChanged("):
            self.assertIn("stopTrailer()", body(self.explore, sig), sig)
        self.assertIn("TRIM_MEMORY_RUNNING_LOW", body(self.explore, "public void onTrimMemory("))
        self.assertIn("trailer.playing()", self.explore[self.explore.index("heroTick = () ->"):][:300])

    def test_player_page_and_safety(self):
        t = self.trailer
        for needle in ("setMediaPlaybackRequiresUserGesture(false)", "youtube-nocookie.com/embed/", "mute=1", "enablejsapi=1",
                       "getCurrentWebViewPackage()", "catch (Throwable", "include_video_language=", "Prefs.addBadTrailer(",
                       '"Trailer"', '"Teaser"', "TIMEOUT_MS = 8000"):
            self.assertIn(needle, t)
        self.assertNotIn("loop=1", t)
        self.assertIn("return true", body(t, "public boolean onRenderProcessGone("))

    def test_setting_row_and_no_config_key(self):
        services = read("ServicesActivity.java")
        self.assertIn("R.string.trailer_in_the_banner", services)
        self.assertIn("Prefs.setBannerTrailer(!Prefs.bannerTrailer())", services)
        cfg = read("TvConfig.java")
        self.assertNotIn("banner_trailer", cfg)
        self.assertNotIn("bannerTrailer", cfg)

    def test_no_new_permission(self):
        text = (APP / "AndroidManifest.xml").read_text(encoding="utf-8")
        perms = set(re.findall(r'<uses-permission android:name="([\w.]+)"', text))
        self.assertEqual(perms, {"android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE",
                                 "android.permission.WRITE_SECURE_SETTINGS", "android.permission.READ_TV_LISTINGS"})

    def test_strings_and_readme(self):
        for d in ("values", "values-pt", "values-uk"):
            text = (APP / "res" / d / "strings.xml").read_text(encoding="utf-8")
            for name in ("trailer_in_the_banner", "trailer_banner_unavailable", "trailer_mute", "trailer_unmute"):
                self.assertIn(f'name="{name}"', text, d)
        self.assertIn("## Trailer in Explore's banner", (ROOT / "launcher-ui/README.md").read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
