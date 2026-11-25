"""CleanYourTV UI: the home screen reads the app lists only when apps change (#46).

Free My TV 3d38bf2, 52bdcbe and 2b0669a: the shelf and All Apps were re-read on every return to Home (100 to 500 ms
of package queries). Now a LauncherApps.Callback marks them stale, settings (Prefs.version) and the language still
force a read, and a 15-minute safety refresh stays. A burst of Home presses within 1.5 s reads once, and firmware
without the launcherapps service (HY300, rk3566) neither crashes nor goes stale: it reads every time.
This reads the source of HomeView.java.

Run from the repository root: python3 -m unittest discover -s tests -p test_ui_app_lists.py -v
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
HOME = ROOT / "launcher-ui/app/src/io/cleanyourtv/ui/HomeView.java"
MANIFEST = ROOT / "launcher-ui/app/AndroidManifest.xml"


def source():
    return HOME.read_text(encoding="utf-8")


def body(text, signature):
    start = text.index(signature)
    return text[start:text.index("\n    }\n", start)]


class AppListsTest(unittest.TestCase):
    def test_constants(self):
        text = source()
        self.assertIn("private static final long APPS_MAX_AGE_MS = 15 * 60_000;", text)
        self.assertIn("private static final long BURST_MS = 1500;", text)
        self.assertIn("import android.content.pm.LauncherApps;", text)
        self.assertIn("import android.os.UserHandle;", text)

    def test_register_is_guarded_and_falls_back_to_null(self):
        ctor = body(source(), "    HomeView(Activity a) {")
        reg = ctor.index("registerCallback(packages, handler)")
        self.assertLess(ctor.index("try {"), reg)
        catch = ctor.index("catch (RuntimeException e)")
        self.assertLess(reg, catch)
        self.assertIn("apps = null;", ctor[catch:])
        self.assertIn("launcherApps = apps;", ctor[catch:])

    def test_unregister_is_guarded(self):
        b = body(source(), "    void destroy() {")
        unreg = b.index("unregisterCallback(packages)")
        self.assertLess(b.index("try {"), unreg)
        self.assertLess(unreg, b.index("catch (RuntimeException"))

    def test_reload_reads_apps_only_when_stale(self):
        b = body(source(), "    private void reload() {")
        for cond in ("launcherApps == null", "appsStale", "Prefs.version != appsVersion",
                     "!Ui.language.equals(appsLanguage)", "up - appsAt > APPS_MAX_AGE_MS"):
            self.assertIn(cond, b)
        self.assertIn("apps ? HomeApps.shelf(c, true) : null", b)
        self.assertIn("apps ? HomeApps.others(c) : null", b)
        # Inputs and the status row: every time.
        self.assertRegex(b, r"final List<HomeApps.Item> st = statusList\(c\);")
        self.assertRegex(b, r"final List<HomeApps.Item> d = drawerList\(c\);")
        self.assertIn("else if (o != null) appsStale = true;", b)

    def test_callback_marks_stale(self):
        text = source()
        for name in ("onPackageAdded", "onPackageRemoved", "onPackageChanged",
                     "onPackagesAvailable", "onPackagesUnavailable"):
            self.assertRegex(text, r"@Override public void %s\([^)]*\) \{ appsChanged\(\); \}" % name)
        b = body(text, "    private void appsChanged() {")
        self.assertIn("appsStale = true;", b)
        self.assertIn("if (running) reloadSoon();", b)

    def test_home_burst_reads_once(self):
        b = body(source(), "    void resume() {")
        self.assertIn("boolean again = resumedAt != 0 && now - resumedAt < BURST_MS;", b)
        self.assertIn("if (!again) reload();", b)
        self.assertEqual(len(re.findall(r"\breload\(\);", b)), 1)

    def test_no_new_permission(self):
        perms = re.findall(r'uses-permission android:name="([^"]+)"', MANIFEST.read_text(encoding="utf-8"))
        self.assertNotIn("android.permission.QUERY_ALL_PACKAGES", perms)


if __name__ == "__main__":
    unittest.main()
