"""CleanYourTV UI: the Settings action reaches Android TV's own settings (#52).

From Free My TV ed6be1e and 4a8f238: HomeApps.tvSettings asks the plain settings request and, when nothing answers
it, opens com.android.tv.settings/.MainSettings by name. On TCL, Philips, Sony, JVC and Skyworth the remote's
"settings" action pins the request to com.android.tv.settings first. The package is in <queries> so resolveActivity
can see it on Android 11+. This reads the source.

Run from the repository root: python3 -m unittest discover -s tests -p test_ui_tv_settings.py -v
"""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "launcher-ui/app/src/io/cleanyourtv/ui"
MANIFEST = ROOT / "launcher-ui/app/AndroidManifest.xml"


def read(name):
    return (SRC / name).read_text(encoding="utf-8")


def body(text, signature):
    start = text.index(signature)
    return text[start:text.index("\n    }\n", start)]


class TvSettingsTest(unittest.TestCase):
    def test_helper_tries_plain_request_before_main_settings(self):
        helper = body(read("HomeApps.java"), "    static Intent tvSettings(Context c) {")
        plain = helper.index("new Intent(Settings.ACTION_SETTINGS)")
        resolves = helper.index("resolveActivity(")
        main = helper.index('"com.android.tv.settings.MainSettings"')
        self.assertLess(plain, resolves)
        self.assertLess(resolves, main)

    def test_drawer_and_wifi_fallback_use_it(self):
        home = read("HomeApps.java")
        self.assertIn("case ANDROID_SETTINGS:\n                intent = tvSettings(a);", home)
        self.assertIn("wifi.resolveActivity(pm) != null ? wifi : tvSettings(c);", home)

    def test_remote_action_is_brand_scoped(self):
        keys = read("RemoteKeys.java")
        for brand in ("TCL", "Philips", "Sony", "JVC", "Skyworth"):
            self.assertIn('is("%s")' % brand, keys)
        action = body(keys, '            case "settings":')
        pinned = action.index('setPackage("com.android.tv.settings")')
        self.assertLess(action.index("ANDROID_TV_SETTINGS_BRAND"), pinned)
        self.assertLess(pinned, action.index("HomeApps.tvSettings(c)"))

    def test_settings_row_uses_it(self):
        settings = read("SettingsActivity.java")
        self.assertIn("HomeApps.tvSettings(c).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)", settings)
        self.assertNotIn("new Intent(Settings.ACTION_SETTINGS)", settings)

    def test_no_stray_plain_settings_request(self):
        home = read("HomeApps.java")
        helper = body(home, "    static Intent tvSettings(Context c) {")
        self.assertEqual(home.replace(helper, "").count("new Intent(Settings.ACTION_SETTINGS)"), 0)
        keys = read("RemoteKeys.java")
        self.assertEqual(keys.count("new Intent(Settings.ACTION_SETTINGS)"), 1)

    def test_package_visible(self):
        manifest = MANIFEST.read_text(encoding="utf-8")
        queries = manifest[manifest.index("<queries>"):manifest.index("</queries>")]
        self.assertIn('<package android:name="com.android.tv.settings" />', queries)
        self.assertNotIn("QUERY_ALL_PACKAGES", manifest)


if __name__ == "__main__":
    unittest.main()
