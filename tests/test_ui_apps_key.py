"""CleanYourTV UI: the remote's Apps key opens All Apps, a second press closes it (#53).

From Free My TV (a7f8905, 0e33d04): HomeActivity answers android.intent.action.ALL_APPS (the system sends it to the
home app) and takes KEYCODE_ALL_APPS unless the key has a row in Remote Buttons; HomeView.showAllApps toggles the
sheet, ignores the same press delivered twice within 400 ms, and opens it on resume when pressed elsewhere.
This reads the source.

Run from the repository root: python3 -m unittest discover -s tests -p test_ui_apps_key.py -v
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "launcher-ui/app/src/io/cleanyourtv/ui"
MANIFEST = ROOT / "launcher-ui/app/AndroidManifest.xml"


def method(text, signature):
    start = text.index(signature)
    return text[start:text.index("\n    }\n", start)]


class AppsKeyTest(unittest.TestCase):
    def setUp(self):
        self.activity = (SRC / "HomeActivity.java").read_text(encoding="utf-8")
        self.view = (SRC / "HomeView.java").read_text(encoding="utf-8")

    def test_manifest_filter_on_home_activity(self):
        text = MANIFEST.read_text(encoding="utf-8")
        start = text.index('android:name=".HomeActivity"')
        block = text[start:text.index("</activity>", start)]
        self.assertIn('android.intent.action.ALL_APPS', block)
        self.assertIn('android:launchMode="singleTask"', block, "one Home instance")
        self.assertEqual(text.count("android.intent.action.ALL_APPS"), 1)

    def test_intent_routes_to_show_all_apps(self):
        self.assertIn("Intent.ACTION_ALL_APPS.equals(intent.getAction())", self.activity)
        self.assertIn("if (wantsAllApps(getIntent())) home.showAllApps();", method(self.activity, "void onCreate("))
        new_intent = method(self.activity, "void onNewIntent(")
        self.assertLess(new_intent.index("home.showAllApps();"), new_intent.index("home.reset(true)"),
                        "the Apps intent must not reset (it would close the sheet before the toggle)")

    def test_key_after_remote_buttons(self):
        keys = method(self.activity, "public boolean dispatchKeyEvent(")
        remote = keys.index("RemoteKeys.onKey(this, event)")
        apps = keys.index("KeyEvent.KEYCODE_ALL_APPS")
        self.assertLess(remote, apps)
        self.assertLess(apps, keys.index("home.onKey(event)"))
        self.assertIn("!remoteButton(KeyEvent.KEYCODE_ALL_APPS)", keys)
        self.assertIn("event.getRepeatCount() == 0", keys)
        self.assertIn("RemoteKeys.buttons(this)", method(self.activity, "private boolean remoteButton("))

    def test_show_all_apps_toggles(self):
        show = method(self.view, "    void showAllApps() {")
        self.assertIn("allAppsOnResume = true;", show)
        self.assertRegex(show, r"now - allAppsAt < 400\) return;")
        self.assertLess(show.index("if (sheetOpen)"), show.index("openSheet();"))
        self.assertIn("closeSheet();", show)

    def test_resume_opens_after_away(self):
        resume = method(self.view, "    void resume() {")
        self.assertIn("if (allAppsOnResume) {", resume)
        self.assertLess(resume.index("openSheet();"), resume.index("sheet.snap(sheet.target);"))
        self.assertIsNone(re.search(r"^\s*if \(sheetOpen\) closeSheet\(\);", resume, re.M),
                          "the plain close would undo All Apps opened on resume")


if __name__ == "__main__":
    unittest.main()
