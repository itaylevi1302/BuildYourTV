"""CleanYourTV UI: Home Settings, System apps in All Apps (#64).

Free My TV PR 368 (595083b) and 7235308: off (the default) keeps All Apps as it was; on, the TV's system apps with a
launcher entry are listed too, never a factory menu, and still never our own package, the hidden lists or the Live TV
apps. A system app put on the shelf while it was on keeps its shelf-editor row. This reads the Java sources.

Run from the repository root: python3 -m unittest discover -s tests -p test_ui_system_apps.py -v
"""
import json
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
UI = ROOT / "launcher-ui"
SRC = UI / "app/src/io/cleanyourtv/ui"
RES = UI / "app/res"


def read(name):
    return (SRC / name).read_text(encoding="utf-8")


def body(text, signature):
    start = text.index(signature)
    return text[start:text.index("\n    }\n", start)]


def labels_body(settings):
    start = settings.index("private String[] settingLabels() {")
    return settings[start:settings.index("return l.toArray", start)]


def factory_regex():
    java = re.search(r'FACTORY = Pattern\.compile\("((?:[^"\\]|\\.)*)"\)', read("HomeApps.java")).group(1)
    return re.compile(java.replace("\\\\", "\\"))


class SystemAppsTest(unittest.TestCase):
    def test_off_by_default(self):
        self.assertIn('static boolean systemApps() { return sp().getBoolean("systemApps", false); }', read("Prefs.java"))
        self.assertIn('put("systemApps", on);', read("Prefs.java"))
        example = json.loads((UI / "config.example.json").read_text(encoding="utf-8"))
        self.assertIs(example.get("show_system_apps"), False)

    def test_launchable_lifts_the_filter_only_when_on(self):
        b = body(read("HomeApps.java"), "    static List<String[]> launchable(Context c) {")
        self.assertIn("boolean systemApps = Prefs.systemApps();", b)
        self.assertIn("if (system && (!systemApps || factoryMenu(pkg))) continue;", b)
        # The Leanback loop was never filtered and stays so; the phone loop is the one the setting opens.
        self.assertLess(b.index("CATEGORY_LEANBACK_LAUNCHER"), b.index("CATEGORY_LAUNCHER)"))
        self.assertLess(b.index("CATEGORY_LAUNCHER)"), b.index("factoryMenu(pkg)"))

    def test_all_apps_still_skips_ours_hidden_and_live_tv(self):
        b = body(read("HomeApps.java"), "    static List<Item> others(Context c) {")
        for skip in ("skip.addAll(java.util.Arrays.asList(LIVE_TV_APPS));", "skip.add(c.getPackageName());",
                     "skip.addAll(HIDDEN);", "skip.addAll(Prefs.hidden());", "for (String[] app : launchable(c))"):
            self.assertIn(skip, b)

    def test_factory_menus_never_listed(self):
        rx = factory_regex()
        for pkg in ("com.tcl.factory.view", "com.cvte.tv.fac.aging", "mediatek.factorymenu.ui", "com.cvte.tv.hkcfac",
                    "com.homwee.factory", "factory"):
            with self.subTest(pkg=pkg):
                self.assertTrue(rx.fullmatch(pkg))
        for pkg in ("com.facebook.katana", "com.android.vending", "com.tcl.filemanager", "com.android.tv.settings"):
            with self.subTest(pkg=pkg):
                self.assertIsNone(rx.fullmatch(pkg))

    def test_shelf_editor_keeps_a_shelved_system_app(self):
        b = body(read("HomeApps.java"), "    static List<String[]> shelfCandidates(Context c) {")
        self.assertLess(b.index("for (String[] app : launchable(c))"), b.index("for (String pkg : shelfPackages(c))"))

    def test_settings_row_sits_before_the_weather(self):
        settings = read("SettingsActivity.java")
        rows = {k: int(v) for k, v in re.findall(r"\b([A-Z_]+_ROWS?) = (\d+)", settings)}
        self.assertEqual(rows["WEATHER_ROW"], rows["SYSTEM_APPS_ROW"] + 1)
        labels = labels_body(settings)
        adds = re.findall(r"l\.add\(([^;]*)\);", labels)
        at = adds.index("Strings.get(R.string.system_apps_in_all_apps)")
        self.assertEqual(adds[at + 1], "Strings.get(R.string.weather_widget)")
        self.assertEqual(at, rows["SYSTEM_APPS_ROW"])
        self.assertIn("v[SYSTEM_APPS_ROW] = onOff(Prefs.systemApps());", settings)
        self.assertIn("case SYSTEM_APPS_ROW: Prefs.setSystemApps(!Prefs.systemApps()); break;", settings)

    def test_every_row_has_a_spring(self):
        settings = read("SettingsActivity.java")
        springs = int(re.search(r"new Spring\[(\d+)\]", settings).group(1))
        labels = labels_body(settings)
        self.assertLessEqual(len(re.findall(r"\bl\.add\(", labels)), springs)

    def test_config_json_key(self):
        self.assertIn('if (o.has("show_system_apps")) Prefs.setSystemApps(o.getBoolean("show_system_apps"));',
                      read("TvConfig.java"))

    def test_every_locale_has_the_label(self):
        for path in [RES / "values/strings.xml", *sorted(RES.glob("values-*/strings.xml"))]:
            with self.subTest(locale=path.parent.name):
                self.assertIn('name="system_apps_in_all_apps"', path.read_text(encoding="utf-8"))

    def test_no_new_permission(self):
        manifest = (UI / "app/AndroidManifest.xml").read_text(encoding="utf-8")
        self.assertNotIn("QUERY_ALL_PACKAGES", manifest)


if __name__ == "__main__":
    unittest.main()
