"""CleanYourTV UI: Home Settings > At power-on opens Live TV, or keeps Home, after a full start only (#61).

From Free My TV c5f414b and 77b0f27 (Free My TV PRs 396, 346, 489), without their ADB: PowerOn arms once per boot
(Settings.Global.BOOT_COUNT kept in Prefs; no permission, no boot receiver) when HomeActivity is created within
4 minutes of boot. "live" opens Live TV 10 s later unless a key reached the home screen or another screen is in
front, and waits for the screen when the TV started in standby. "home" gives the screen back to ours when another app
takes it within 5 s of the home screen first showing. "tv", the default, changes nothing. This reads the source.

Run from the repository root: python3 -m unittest discover -s tests -p test_ui_power_on.py -v
"""
import json
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "launcher-ui/app"
SRC = APP / "src/io/cleanyourtv/ui"


def read(name):
    return (SRC / name).read_text(encoding="utf-8")


def block(text, head):
    """The text from `head` to its matching closing brace."""
    start = text.index(head)
    depth = 0
    for i in range(text.index("{", start), len(text)):
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                return text[start:i + 1]
    raise AssertionError("unbalanced braces after " + head)


class PowerOnTest(unittest.TestCase):
    def setUp(self):
        self.power = read("PowerOn.java")
        self.activity = read("HomeActivity.java")

    def test_default_is_the_tvs_choice(self):
        prefs = read("Prefs.java")
        self.assertIn('static final String[] POWER_ON = {"tv", "home", "live"};', prefs)
        self.assertIn('sp().getString("powerOn", "tv")', prefs)
        stamp = block(prefs, "static void setPowerOnBoot(")
        self.assertNotIn("put(", stamp, "the boot stamp is not a setting: no version bump, no Home redraw")
        self.assertNotIn("version++", stamp)

    def test_once_per_boot_without_a_permission(self):
        created = block(self.power, "static void created(Activity a)")
        self.assertIn("Settings.Global.BOOT_COUNT", created)
        self.assertIn("boot == Prefs.powerOnBoot()", created)
        self.assertLess(created.index("Prefs.setPowerOnBoot(boot);"), created.index('if (mode.equals("tv")) return;'),
                        "the start is recorded even while the setting is off")
        self.assertIn("BOOT_WINDOW_MS = 240_000", self.power)
        self.assertIn("SystemClock.elapsedRealtime()", created)
        manifest = (APP / "AndroidManifest.xml").read_text(encoding="utf-8")
        self.assertNotIn("RECEIVE_BOOT_COMPLETED", manifest)
        self.assertNotIn("BOOT_COMPLETED", manifest)

    def test_live_waits_for_a_key_and_the_screen(self):
        self.assertIn("LIVE_DELAY_MS = 10_000", self.power)
        self.assertIn("cancel(", block(self.power, "static void key(KeyEvent e)"))
        paused = block(self.power, "static void paused(Activity a)")
        self.assertLess(paused.index("if (!interactive(a)) {"), paused.index("cancel(null);"),
                        "the screen going off keeps the wait (Free My TV PR 489)")
        resumed = block(self.power, "static void resumed(Activity a)")
        self.assertIn("handler.postDelayed(live, LIVE_DELAY_MS);", resumed)
        self.assertIn("dark", block(self.power, "static void homeIntent()"))
        live = block(self.power, "private static void openLive()")
        self.assertLess(live.index("if (a == null) {"), live.index("HomeApps.open("))
        self.assertIn("HomeApps.LIVE_TV", live)

    def test_home_only_right_after_it_showed(self):
        self.assertIn("HOME_GRAB_MS = 5_000", self.power)
        paused = block(self.power, "static void paused(Activity a)")
        self.assertIn("SystemClock.uptimeMillis() - shownAt < HOME_GRAB_MS", paused)
        self.assertIn("addCategory(Intent.CATEGORY_HOME)", paused)
        self.assertIn("setPackage(a.getPackageName())", paused)

    def test_home_activity_hooks(self):
        self.assertIn("PowerOn.created(this);", block(self.activity, "protected void onCreate("))
        resume = block(self.activity, "protected void onResume()")
        self.assertLess(resume.index("recreate();"), resume.index("PowerOn.resumed(this);"),
                        "after the recreate check, or the old instance would see the resume")
        pause = block(self.activity, "protected void onPause()")
        self.assertLess(pause.index("PowerOn.paused(this);"), pause.index("super.onPause();"))
        keys = block(self.activity, "public boolean dispatchKeyEvent(")
        self.assertLess(keys.index("PowerOn.key(event);"), keys.index("RemoteKeys.onKey(this, event)"))
        self.assertIn("PowerOn.homeIntent();", block(self.activity, "protected void onNewIntent("))
        self.assertIn("PowerOn.destroyed(this);", block(self.activity, "protected void onDestroy()"))

    def test_settings_row(self):
        settings = read("SettingsActivity.java")
        rows = {k: int(v) for k, v in re.findall(r"(\w+_ROWS?) = (\d+)", settings[settings.index("CLOCK_ROW ="):])}
        self.assertEqual(rows["POWER_ON_ROW"], rows["CLOCK_ROW"] + 1, "right after Clock format")
        self.assertEqual(rows["WEATHER_ROW"], rows["POWER_ON_ROW"] + 1)
        labels = block(settings, "private String[] settingLabels()")
        self.assertLess(labels.index("R.string.clock_format"), labels.index("R.string.at_power_on"))
        self.assertLess(labels.index("R.string.at_power_on"), labels.index("R.string.weather_widget"))
        self.assertIn("Prefs.powerOn()", block(settings, "private String[] settingValues()"))
        self.assertIn("case POWER_ON_ROW:", block(settings, "private void adjust(int dir)"))
        # Past 11 rows (with the dollar row) the pitch shrinks to fit 1080; the focus springs cover the most rows.
        self.assertIn("Math.min(68f, 816f / settingLabels().length)", settings)
        self.assertGreaterEqual(int(re.search(r"new Spring\[(\d+)\]", settings).group(1)), rows["COIN_ROWS"] + 4)
        self.assertIn("R.string.power_on_hint", settings)

    def test_config_key(self):
        config = read("TvConfig.java")
        self.assertIn('o.optString("power_on")', config)
        self.assertIn("Arrays.asList(Prefs.POWER_ON).contains(powerOn)", config)
        readme = (ROOT / "launcher-ui/README.md").read_text(encoding="utf-8")
        self.assertIn("| `power_on` | `tv`, `home`, `live` |", readme)
        example = json.loads((ROOT / "launcher-ui/config.example.json").read_text(encoding="utf-8"))
        self.assertEqual(example.get("power_on"), "tv")

    def test_strings_in_every_language(self):
        for path in [APP / "res/values/strings.xml", *sorted((APP / "res").glob("values-*/strings.xml"))]:
            text = path.read_text(encoding="utf-8")
            for name in ("at_power_on", "power_on_tv", "power_on_hint"):
                with self.subTest(locale=path.parent.name, name=name):
                    self.assertIn('<string name="%s">' % name, text)


if __name__ == "__main__":
    unittest.main()
