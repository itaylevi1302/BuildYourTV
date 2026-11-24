"""CleanYourTV UI: an "inputs" action; Hisense's Input key opens the inputs drawer when nothing answers it (#55).

From Free My TV 5dbe7bf and fdabf9d: Hisense's firmware turns KEYCODE_TV_INPUT into VIEW_INPUTS, which only the stock
home screen's panel answers. RemoteKeys takes that key on Hisense only while nothing resolves VIEW_INPUTS (a
configurable row decides instead) and opens Home with open=inputs; HomeView.showInputs opens the drawer on the first
input, on resume when pressed elsewhere. The "inputs" action prefers the TV's panel. This reads the source.

Run from the repository root: python3 -m unittest discover -s tests -p test_ui_inputs_key.py -v
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "launcher-ui/app/src/io/cleanyourtv/ui"
MANIFEST = ROOT / "launcher-ui/app/AndroidManifest.xml"


def read(name):
    return (SRC / name).read_text(encoding="utf-8")


def method(text, signature):
    start = text.index(signature)
    return text[start:text.index("\n    }\n", start)]


class InputsKeyTest(unittest.TestCase):
    def setUp(self):
        self.keys = read("RemoteKeys.java")
        self.view = read("HomeView.java")

    def test_hisense_only_and_only_without_a_row(self):
        self.assertRegex(self.keys, r'HISENSE = is\("Hisense"\)')
        on_key = method(self.keys, "    static boolean onKey(Context c, KeyEvent e) {")
        self.assertIn("if (found == null) return HISENSE && keyCode == KeyEvent.KEYCODE_TV_INPUT && hisenseInputs(c, e);",
                      on_key)
        self.assertEqual(self.keys.count("hisenseInputs(c, e)"), 1, "the built-in path is reached only with no row")

    def test_left_to_the_tv_while_its_panel_answers(self):
        self.assertIn('VIEW_INPUTS = "com.android.tv.action.VIEW_INPUTS"', self.keys)
        answered = method(self.keys, "    static boolean inputsAnswered(Context c) {")
        self.assertIn("resolveActivity(new Intent(VIEW_INPUTS), 0) != null", answered)
        hisense = method(self.keys, "    private static boolean hisenseInputs(Context c, KeyEvent e) {")
        self.assertLess(hisense.index("if (inputsAnswered(c)) return false;"), hisense.index("run(c, INPUTS)"))
        self.assertIn("e.getRepeatCount() == 0", hisense)
        on_key = method(self.keys, "    static boolean onKey(Context c, KeyEvent e) {")
        row = on_key.index("if (action.equals(INPUTS) && keyCode == KeyEvent.KEYCODE_TV_INPUT && inputsAnswered(c)) return false;")
        self.assertLess(on_key.index("if (action.isEmpty()) return false;"), row)
        self.assertLess(row, on_key.index("run(c, action)"))

    def test_action_prefers_the_panel_then_home(self):
        self.assertIn('static final String INPUTS = "inputs";', self.keys)
        intent = method(self.keys, "    static Intent intent(Context c, String action) {")
        case = intent[intent.index("case INPUTS:"):]
        self.assertLess(case.index("if (inputsAnswered(c)) return new Intent(VIEW_INPUTS);"),
                        case.index('.putExtra("open", "inputs")'))
        self.assertIn("addCategory(Intent.CATEGORY_HOME).setPackage(c.getPackageName())", case)

    def test_home_opens_the_drawer_on_the_inputs(self):
        open_from = method(read("HomeActivity.java"), "    private void openFrom(Intent intent) {")
        self.assertIn('what.equals("inputs") && home != null) home.showInputs();', open_from)
        show = method(self.view, "    void showInputs() {")
        self.assertLess(show.index("inputsOnResume = true;"), show.index("openInputs();"))
        resume = method(self.view, "    void resume() {")
        self.assertLess(resume.index("sheet.snap(sheet.target);"), resume.index("if (inputsOnResume) {"))
        opener = method(self.view, "    private void openInputs() {")
        self.assertLess(opener.index("openDrawer();"), opener.index("kind == HomeApps.INPUT)"))
        # The Inputs button shares it rather than keeping its own copy.
        self.assertEqual(self.view.count("drawerItems.get(i).kind == HomeApps.INPUT)"), 1)

    def test_action_accepted_and_offered(self):
        self.assertIn("action.equals(RemoteKeys.INPUTS)", method(read("TvConfig.java"), "    static boolean validAction("))
        self.assertIn("{RemoteKeys.INPUTS, Strings.get(R.string.hdmi_inputs)}", read("SettingsActivity.java"))

    def test_panel_visible_without_new_permissions(self):
        text = MANIFEST.read_text(encoding="utf-8")
        queries = text[text.index("<queries>"):text.index("</queries>")]
        self.assertIn('<action android:name="com.android.tv.action.VIEW_INPUTS" />', queries)
        self.assertEqual(sorted(re.findall(r'<uses-permission android:name="([^"]+)"', text)),
                         ["android.permission.ACCESS_NETWORK_STATE", "android.permission.INTERNET",
                          "android.permission.WRITE_SECURE_SETTINGS"])

    def test_documented(self):
        readme = (ROOT / "launcher-ui/README.md").read_text(encoding="utf-8")
        self.assertIn("`inputs` (the TV's inputs panel", readme)
        self.assertIn("KEYCODE_TV_INPUT", readme)
        self.assertIn("`inputs`", (ROOT / "docs/ui/DESIGN.md").read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
