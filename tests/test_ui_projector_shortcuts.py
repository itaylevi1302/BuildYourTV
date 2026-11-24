"""CleanYourTV UI: HY300/HY320 projector shortcuts and HDMI through Allwinner's player (#57).

From Free My TV fb10f1a, 6ab423e and 6666602: the drawer gets Keystone (com.softwinner.tcorrection where it is there,
else the HTC settings' projection screen) and Screen Mirroring (Allwinner's Miracast receiver) as DEVICE items, only
where an activity answers. Where nothing opens a passthrough input but com.softwinner.awlivetv is there, HDMI opens
through that player with input_source "HDMI<n>", only HDMI 1 is listed, and connected standby counts as connected.
This reads the source.

Run from the repository root: python3 -m unittest discover -s tests -p test_ui_projector_shortcuts.py -v
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "launcher-ui/app"
SRC = APP / "src/io/cleanyourtv/ui"
MANIFEST = APP / "AndroidManifest.xml"
KINDS = ("APP", "LIVE_TV", "INPUT", "ALL_APPS", "SETTINGS", "ANDROID_SETTINGS", "INPUTS", "EXPLORE", "NETWORK",
         "APP_SETTINGS", "DEVICE")


def home():
    return (SRC / "HomeApps.java").read_text(encoding="utf-8")


def body(text, signature):
    start = text.index(signature)
    return text[start:text.index("\n    }\n", start)]


class ProjectorShortcuts(unittest.TestCase):
    def test_device_kind_is_unique(self):
        text = home()
        values = {}
        for name in KINDS:
            m = re.search(r"\b%s = (\d+)[,;]" % name, text)
            if m:
                values[name] = int(m.group(1))
        self.assertIn("DEVICE", values)
        self.assertEqual(len(set(values.values())), len(values), values)

    def test_drawer_adds_them_last(self):
        drawer = body(home(), "    static List<Item> drawerShortcuts(Context c) {")
        self.assertIn("projectorShortcuts(c, items);\n        return items;", drawer)

    def test_keystone_prefers_tcorrection_and_mirroring_is_miracast(self):
        b = body(home(), "    private static void projectorShortcuts(Context c, List<Item> items) {")
        tc = b.index('"com.softwinner.tcorrection.projection.ProjectionSettingActivity"')
        htc = b.index('new Intent("action.htcsettings.prjsettings")')
        self.assertLess(tc, htc)
        self.assertIn("opens(c, tcorrection) ? tcorrection :", b)
        self.assertIn('"com.softwinner.miracastReceiver.Miracast"', b)
        self.assertIn("R.string.keystone", b)
        self.assertIn("R.string.screen_mirroring", b)

    def test_shown_only_when_an_activity_answers(self):
        text = home()
        device = body(text, "    private static void device(Context c, List<Item> items,")
        self.assertIn("if (!opens(c, intent)) return;", device)
        self.assertIn("shortcut(DEVICE, key, label, symbol)", device)
        opens = body(text, "    private static boolean opens(Context c, Intent intent) {")
        self.assertIn("getPackageManager().resolveActivity(intent, 0)", opens)
        self.assertIn("r.activityInfo.exported", opens)

    def test_device_opens_in_a_fresh_task(self):
        b = body(home(), "    static boolean open(Activity a, Item it, View from,")
        self.assertIn("case DEVICE:", b)
        self.assertIn("new Intent(it.intent).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)", b)

    def test_hdmi_through_allwinner_player(self):
        text = home()
        self.assertIn('new ComponentName("com.softwinner.awlivetv", "com.softwinner.awlivetv.MainActivity")', text)
        aw = body(text, "    private static boolean allwinnerInputs(Context c) {")
        self.assertIn("buildChannelUriForPassthroughInput", aw)
        self.assertIn("== null && opens(c, new Intent().setComponent(ALLWINNER_TV))", aw)
        intent = body(text, "    static Intent inputIntent(Context c, Item it) {")
        self.assertIn('putExtra("input_source", "HDMI" + it.number)', intent)
        self.assertIn("buildChannelUriForPassthroughInput(it.inputId)", intent)
        self.assertIn("intent = inputIntent(a, it);", body(text, "    static boolean open(Activity a, Item it, View from,"))

    def test_inputs_standby_and_one_port_only_on_allwinner(self):
        b = body(home(), "    private static List<Item> inputs(Context c, boolean all) {")
        self.assertIn("boolean allwinner = allwinnerInputs(c);", b)
        self.assertIn("(allwinner && state == TvInputManager.INPUT_STATE_CONNECTED_STANDBY)", b)
        self.assertIn("if (!all && !connected) continue;", b)
        self.assertIn("it.connected = connected;", b)
        self.assertIn('if (allwinner) out.removeIf(it -> !"1".equals(it.number));', b)
        self.assertEqual(b.count("INPUT_STATE_CONNECTED_STANDBY"), 1)

    def test_packages_queryable_without_new_permissions(self):
        manifest = MANIFEST.read_text(encoding="utf-8")
        queries = manifest[manifest.index("<queries>"):manifest.index("</queries>")]
        for pkg in ("com.htc.htcsettings", "com.softwinner.tcorrection", "com.softwinner.miracastReceiver",
                    "com.softwinner.awlivetv"):
            self.assertIn('<package android:name="%s" />' % pkg, queries)
        self.assertNotIn("QUERY_ALL_PACKAGES", manifest)

    def test_symbols_and_strings(self):
        for name in ("keystone", "cast"):
            data = (APP / ("assets/symbols/%s.png" % name)).read_bytes()
            self.assertTrue(data.startswith(b"\x89PNG"), name)
        for path in [APP / "res/values/strings.xml", *sorted((APP / "res").glob("values-*/strings.xml"))]:
            text = path.read_text(encoding="utf-8")
            if '<string name="tv_settings">' not in text:
                continue   # values-v30 and the like carry no strings
            with self.subTest(locale=path.parent.name):
                self.assertIn('<string name="keystone">', text)
                self.assertIn('<string name="screen_mirroring">', text)


if __name__ == "__main__":
    unittest.main()
