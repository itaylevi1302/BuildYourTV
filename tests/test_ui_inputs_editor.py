"""CleanYourTV UI: choose, hide and reorder the drawer's inputs; the Live TV button can open an HDMI input (#60).

From Free My TV 9ee0103 and 78af796 (Settings > Inputs, VisibleOrder) and eb2fbfb (the Live TV button). Settings >
Inputs is the shelf editor on Live TV and the HDMI ports: OK shows or hides one, left/right move it, and the drawer
follows (HomeApps.orderedInputs); until it is used nothing changes. Its first row, where a remote button runs "live",
picks what that action opens: Live TV or one HDMI input, kept in the buttons file the key service reads too (our home
screen has no Live TV top button, upstream's place for it). This reads the source, and runs VisibleOrder's cases with
the JDK's javac when one is there.

Run from the repository root: python3 -m unittest discover -s tests -p test_ui_inputs_editor.py -v
"""
import os
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "launcher-ui/app"
SRC = APP / "src/io/cleanyourtv/ui"
NEW_STRINGS = ("inputs_edit_hint", "in_the_inputs_place_prefix", "not_in_the_inputs", "input_hidden", "live_tv_button")


def read(name):
    return (SRC / name).read_text(encoding="utf-8")


def method(text, signature, indent="    "):
    """The method that starts at `signature`, up to its closing brace at `indent`."""
    start = text.index(signature)
    return text[start:text.index("\n" + indent + "}\n", start)]


def settings_method(signature):
    return method(read("SettingsActivity.java"), signature, indent="        ")


def jdk_tool(name):
    home = os.environ.get("JAVA_HOME")
    path = str(Path(home) / "bin" / name) if home else shutil.which(name)
    if not path or not Path(path).exists():
        return None
    ok = subprocess.run([path, "-version"], capture_output=True, timeout=60).returncode == 0
    return path if ok else None


class VisibleOrderRuns(unittest.TestCase):
    def test_upstream_cases_pass(self):
        javac, java = jdk_tool("javac"), jdk_tool("java")
        if not javac or not java:
            self.skipTest("needs a JDK (javac and java)")
        with tempfile.TemporaryDirectory() as out:
            build = subprocess.run([javac, "-nowarn", "-encoding", "UTF-8", "-d", out, str(SRC / "VisibleOrder.java"),
                                    str(ROOT / "tests/visible_order/VisibleOrderTest.java")],
                                   capture_output=True, text=True, timeout=120)
            self.assertEqual(build.returncode, 0, build.stdout + build.stderr)
            run = subprocess.run([java, "-ea", "-cp", out, "io.cleanyourtv.ui.VisibleOrderTest"],
                                 capture_output=True, text=True, timeout=120)
            self.assertEqual(run.returncode, 0, run.stdout + run.stderr)
            self.assertRegex(run.stdout, r"VisibleOrder: \d+ assertions passed")


class DrawerFollows(unittest.TestCase):
    def test_drawer_uses_the_ordered_inputs(self):
        drawer = method(read("HomeView.java"), "    private static List<HomeApps.Item> drawerList(Context c) {")
        self.assertIn("HomeApps.orderedInputs(c)", drawer)
        self.assertNotIn("allInputs", drawer)

    def test_nothing_changes_until_the_editor_is_used(self):
        ordered = method(read("HomeApps.java"), "    static List<Item> orderedInputs(Context c) {")
        self.assertLess(ordered.index("if (order == null && hidden.isEmpty()) return inputs;"),
                        ordered.index("VisibleOrder.arrange(order, hidden, keys)"))
        prefs = read("Prefs.java")
        self.assertIn('sp().getString("inputsOrder", null)', method(prefs, "    static List<String> inputsOrder() {"))
        self.assertIn('sp().getString("inputsHidden", "")', prefs)


class LiveButton(unittest.TestCase):
    def test_live_action_opens_the_picked_input_first(self):
        keys = read("RemoteKeys.java")
        live = keys[keys.index('            case "live": {'):]
        live = live[:live.index("\n            }\n")]
        self.assertLess(live.index("HomeApps.liveButtonInput(c)"), live.index("HomeApps.liveTv(c)"))
        self.assertIn("if (box == null) return HomeApps.liveTv(c);", live)
        self.assertIn("rememberInput(c, box.inputId)", keys)

    def test_choice_only_while_the_tv_lists_the_input(self):
        box = method(read("HomeApps.java"), "    static Item liveButtonInput(Context c) {")
        self.assertIn("Prefs.liveButton()", box)
        self.assertIn("if (id.isEmpty()) return null;", box)
        self.assertIn("inputs(c, true)", box)

    def test_kept_where_the_key_service_reads_it(self):
        prefs = read("Prefs.java")
        name = re.search(r'private static final String LIVE_BUTTON = "([^"]+)";', prefs).group(1)
        self.assertIsNone(re.fullmatch(r"[sk][0-9]{1,6}", name), "a button id would clash")
        self.assertIn("button(LIVE_BUTTON)", method(prefs, "    static String liveButton() {"))
        self.assertIn("setButton(LIVE_BUTTON,", method(prefs, "    static void setLiveButton(String inputId) {"))


class Editor(unittest.TestCase):
    def setUp(self):
        self.settings = read("SettingsActivity.java")

    def test_inputs_row_after_the_shelf(self):
        self.assertEqual(self.settings.count(
            "Strings.get(R.string.shelf), Strings.get(R.string.inputs), Strings.get(R.string.remote_buttons),"), 2)
        self.assertIn("case MAIN: return 9;", self.settings)
        activate = settings_method("        private void activate() {")
        self.assertLess(activate.index("openShelfEditor();"), activate.index("openInputsEditor();"))
        self.assertLess(activate.index("openInputsEditor();"), activate.index("loadApps();"))
        self.assertIn("screen == SHELF_EDIT ? (editingInputs ? 3 : 2)", self.settings)

    def test_editor_rows_and_saving(self):
        opener = settings_method("        private void openInputsEditor() {")
        self.assertIn("HomeApps.orderedInputs(getContext())", opener)
        self.assertIn("HomeApps.inputCandidates(getContext())", opener)
        save = settings_method("        private void saveInputs() {")
        self.assertIn("VisibleOrder.replace(saved == null ? shelfOrder : saved, shelfOrder)", save)
        self.assertIn("Prefs.setInputsHidden(hidden)", save)
        self.assertIn("if (editingInputs) saveInputs(); else Prefs.setShelf(shelfOrder);",
                      settings_method("        private void refreshShelfRows(String focusedPkg) {"))
        toggle = settings_method("        private void toggleShelfApp() {")
        self.assertIn("editingInputs || shelfOrder.size() < HomeApps.MAX_SHELF", toggle)
        self.assertIn("if (shelfRows.isEmpty()) return;", toggle)
        self.assertIn("if (screen != SHELF_EDIT || editingInputs) return false;", self.settings)

    def test_live_button_row(self):
        self.assertIn("liveRow = !Prefs.liveButton().isEmpty() || remoteOpensLive();",
                      settings_method("        private void openInputsEditor() {"))
        self.assertIn("cycleLiveButton(1);", settings_method("        private void toggleShelfApp() {"))
        self.assertIn("cycleLiveButton(dir);", settings_method("        private void moveShelfApp(int dir) {"))
        cycle = settings_method("        private void cycleLiveButton(int dir) {")
        self.assertIn('ids.add("");', cycle)
        self.assertIn("it.inputId != null", cycle)
        value = settings_method("        private String liveButtonValue() {")
        self.assertIn("liveButtonAt != Prefs.version", value, "the inputs are read once per change, not every frame")


class StringsAndDocs(unittest.TestCase):
    def test_every_language_has_the_new_strings(self):
        for path in [APP / "res/values/strings.xml", *sorted((APP / "res").glob("values-*/strings.xml"))]:
            text = path.read_text(encoding="utf-8")
            if '<string name="not_on_the_shelf">' not in text:
                continue   # values-v30 and the like carry no strings
            for name in NEW_STRINGS + ("inputs",):
                with self.subTest(locale=path.parent.name, name=name):
                    self.assertIn('<string name="%s">' % name, text)

    def test_documented(self):
        readme = (ROOT / "launcher-ui/README.md").read_text(encoding="utf-8")
        self.assertIn("Settings → Inputs → Live TV button", readme)
        self.assertIn("**Entradas**", (ROOT / "docs/ui/DESIGN.md").read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
