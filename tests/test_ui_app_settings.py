"""CleanYourTV UI: App Settings in the drawer where TV Settings opens the maker's menu (#56).

From Free My TV 8d13879: on a Philips Android 8, TV Settings opens Philips' own menu, which has no Apps. The drawer then
gets App Settings, Android's list of apps, only where what TV Settings opens is not com.android.tv.settings and a
different package answers the list. This reads the source.

Run from the repository root: python3 -m unittest discover -s tests -p test_ui_app_settings.py -v
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "launcher-ui/app/src/io/cleanyourtv/ui"
RES = ROOT / "launcher-ui/app/res"
MANIFEST = ROOT / "launcher-ui/app/AndroidManifest.xml"


def read(name):
    return (SRC / name).read_text(encoding="utf-8")


def body(text, signature):
    start = text.index(signature)
    return text[start:text.index("\n    }\n", start)]


class AppSettingsTest(unittest.TestCase):
    def test_drawer_adds_it_only_when_the_helper_allows(self):
        home = read("HomeApps.java")
        drawer = body(home, "    static List<Item> drawerShortcuts(Context c) {")
        tv = drawer.index("ANDROID_SETTINGS")
        apps = drawer.index("APP_SETTINGS, \"app-settings\", Strings.get(R.string.apps_settings), \"apps\"")
        self.assertLess(tv, apps)
        self.assertIn("if (appSettings(c) != null)", drawer)

    def test_helper_condition(self):
        helper = body(read("HomeApps.java"), "    static Intent appSettings(Context c) {")
        self.assertIn("answers(c, tvSettings(c))", helper)
        self.assertIn('tv.equals("com.android.tv.settings")', helper)
        self.assertIn("new Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS)", helper)
        self.assertIn("!list.equals(tv)", helper)

    def test_open_uses_the_helper(self):
        home = read("HomeApps.java")
        self.assertIn("case APP_SETTINGS:\n                intent = appSettings(a);", home)
        self.assertEqual(len(re.findall(r"static final int APP_SETTINGS = \d+;", home)), 1)

    def test_drawer_list_passes_the_context(self):
        self.assertIn("HomeApps.drawerShortcuts(c)", read("HomeView.java"))

    def test_every_language_names_it(self):
        for path in [RES / "values/strings.xml", *sorted(RES.glob("values-*/strings.xml"))]:
            text = path.read_text(encoding="utf-8")
            if '<string name="all_apps">' not in text:
                continue   # values-v30 and the like carry no strings
            with self.subTest(locale=path.parent.name):
                self.assertIn('<string name="apps_settings">', text)

    def test_resolvers_visible_without_new_permissions(self):
        manifest = MANIFEST.read_text(encoding="utf-8")
        queries = manifest[manifest.index("<queries>"):manifest.index("</queries>")]
        self.assertIn('<action android:name="android.settings.SETTINGS" />', queries)
        self.assertIn('<action android:name="android.settings.MANAGE_APPLICATIONS_SETTINGS" />', queries)
        self.assertNotIn("QUERY_ALL_PACKAGES", manifest)


if __name__ == "__main__":
    unittest.main()
