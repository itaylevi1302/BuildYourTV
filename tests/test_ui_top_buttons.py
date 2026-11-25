"""CleanYourTV UI: an App Store top button, and hold OK to move the top buttons (#62).

From Free My TV b5eb90e (PR 404) and fea9721 (PR 405). The round buttons at the top right come from
HomeApps.topButtons in the saved order (Prefs.topButtons; Explore, HDMI Inputs, Settings until one is moved). Holding OK
on one drops a menu: Move (the shelf's lift, slide and wiggle; OK drops, Back cancels) and the App Store button (off
until added; Google Play, or Amazon Appstore on Fire TV, only where it opens). A drop saves with VisibleOrder.replace,
so a hidden App Store button keeps its slot. This reads the source.

Run from the repository root: python3 -m unittest discover -s tests -p test_ui_top_buttons.py -v
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
    return text[start:text.index("\n    }\n", start)]


class TopButtonsTest(unittest.TestCase):
    def setUp(self):
        self.apps = read("HomeApps.java")
        self.view = read("HomeView.java")

    def test_default_order_is_unchanged_and_the_store_comes_last(self):
        self.assertIn('TOP_EXPLORE = "explore", TOP_INPUTS = "inputs", TOP_SETTINGS = "settings", TOP_STORE = "store"',
                      self.apps)
        m = re.search(r"TOP_ALL = Collections\.unmodifiableList\(\s*java\.util\.Arrays\.asList\(([^)]*)\)\)", self.apps)
        self.assertIsNotNone(m)
        keys = [k.strip() for k in m.group(1).split(",")]
        self.assertEqual(keys[:3], ["TOP_EXPLORE", "TOP_INPUTS", "TOP_SETTINGS"])
        self.assertEqual(keys[-1], "TOP_STORE")
        self.assertIn("static final int MAX_TOP = TOP_ALL.size();", self.apps)

    def test_store_button_is_off_until_added(self):
        self.assertIn('getBoolean("storeButton", false)', read("Prefs.java"))
        body = method(self.apps, "    static List<Item> topButtons(Context c) {")
        self.assertIn("Prefs.storeButton()", body)
        self.assertIn("storePackage(c)", body)
        self.assertIn('if (o.has("app_store_button")) Prefs.setStoreButton(', read("TvConfig.java"))

    def test_store_opens_only_a_store_the_tv_has(self):
        body = method(self.apps, "    static String storePackage(Context c) {")
        for pkg in ("com.android.vending", "com.amazon.venezia"):
            self.assertIn(pkg, body)
        self.assertIn("launchIntent(pm, first)", body)
        self.assertNotIn("market://", body)
        self.assertNotIn("http", body)

    def test_manifest_sees_both_stores(self):
        text = (APP / "AndroidManifest.xml").read_text(encoding="utf-8")
        queries = text[text.index("<queries>"):text.index("</queries>")]
        for pkg in ("com.android.vending", "com.amazon.venezia"):
            self.assertIn(f'<package android:name="{pkg}" />', queries)

    def test_store_symbol_ships(self):
        self.assertTrue((APP / "assets/symbols/store.png").is_file())

    def test_top_row_holds_every_button(self):
        self.assertIn("new Spring[3 + HomeApps.MAX_TOP]", self.view)
        self.assertIn("new RectF[3 + HomeApps.MAX_TOP]", self.view)
        self.assertIn("new Spring[HomeApps.MAX_TOP]", self.view)
        self.assertNotIn("Math.min(5, topIndex", self.view)
        self.assertIn("for (int i = lightButtons.size() - 1; i >= 0; i--)", method(self.view, "    private void layoutLight() {"))
        draw = method(self.view, "    private void drawLightButtons(Canvas c) {")
        self.assertIn("int n = lightButtons.size();", draw)
        self.assertIn("topSlide[i].value", draw)

    def test_holding_ok_on_a_top_button_opens_its_menu(self):
        self.assertIn("if (this.okDown && this.holdArmed) hold();", self.view)
        self.assertIn("topButtonHeld()", method(self.view, "    private boolean canHold() {"))
        menu = method(self.view, "    private void topMenu() {")
        for name in ("R.string.move", "R.string.add_app_store_button", "R.string.remove_app_store_button",
                     "R.string.close", "this::startReorder"):
            self.assertIn(name, menu)

    def test_drop_saves_with_visible_order_and_back_restores(self):
        drop = method(self.view, "    private void dropReorder() {")
        self.assertIn("Prefs.setTopButtons(VisibleOrder.replace(HomeApps.topButtonKeys(), shown))", drop)
        cancel = method(self.view, "    private void cancelReorder(boolean snap) {")
        self.assertIn("lightButtons.addAll(reorderOrigin)", cancel)
        self.assertIn("for (Spring s : topSlide) s.snap(0);", cancel)
        self.assertIn("static List<String> replace(List<String> saved, List<String> ordered)", read("VisibleOrder.java"))

    def test_a_list_read_during_a_move_does_not_undo_it(self):
        reload = method(self.view, "    private void reload() {")
        self.assertIn("final int orderAtLoad = orderRevision;", reload)
        self.assertIn("orderAtLoad != orderRevision", reload)
        self.assertIn("setLightButtons(top)", reload)

    def test_strings_in_every_language(self):
        for path in [APP / "res/values/strings.xml", *sorted((APP / "res").glob("values-*/strings.xml"))]:
            text = path.read_text(encoding="utf-8")
            for name in ("app_store", "add_app_store_button", "remove_app_store_button"):
                with self.subTest(locale=path.parent.name, name=name):
                    self.assertIn(f'<string name="{name}">', text)


if __name__ == "__main__":
    unittest.main()
