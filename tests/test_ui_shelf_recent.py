"""CleanYourTV UI: the shelf's "Last opened first" order (#63, Free My TV f0f4d20).

Recent keeps the packages opened from the launcher, newest first; HomeApps.shelf orders by it only while
Prefs.shelfRecent is on (off by default), and the home screen reorders the shelf it has on resume without the package
read #46 guards. The pure helpers (pushed, rank, sorted) are compiled out of Recent.java with plain javac against a
stub HomeApps.Item and run; the wiring is checked in the sources.

Run from the repository root: python3 -m unittest discover -s tests -p test_ui_shelf_recent.py -v
"""
import json
import os
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
UI = ROOT / "launcher-ui"
SRC = UI / "app/src/io/cleanyourtv/ui"


def read(name):
    return (SRC / name).read_text(encoding="utf-8")


def body(text, head):
    start = text.index(head)
    return text[start:text.index("\n    }\n", start)]


HARNESS = r"""
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class Check {
    static HomeApps.Item item(String pkg) { HomeApps.Item it = new HomeApps.Item(); it.pkg = pkg; return it; }

    static List<HomeApps.Item> items(String... pkgs) {
        List<HomeApps.Item> out = new ArrayList<>();
        for (String p : pkgs) out.add(item(p.equals("-") ? null : p));
        return out;
    }

    static String join(List<HomeApps.Item> l) {
        StringBuilder sb = new StringBuilder();
        for (HomeApps.Item it : l) sb.append(sb.length() == 0 ? "" : ",").append(it.pkg == null ? "-" : it.pkg);
        return sb.toString();
    }

    public static void main(String[] a) {
        System.out.println("sorted1=" + join(Recent.sorted(Arrays.asList("b", "d"), items("a", "b", "c", "d"))));
        System.out.println("sorted2=" + join(Recent.sorted(new ArrayList<>(), items("a", "b", "c"))));
        System.out.println("sorted3=" + join(Recent.sorted(Arrays.asList("d", "x", "a"), items("a", "-", "c", "d"))));
        System.out.println("push1=" + String.join(",", Recent.pushed(Arrays.asList("a", "b", "c"), "c")));
        System.out.println("push2=" + String.join(",", Recent.pushed(Arrays.asList("a", "b"), "z")));
        System.out.println("push3=" + String.join(",", Recent.pushed(Arrays.asList("a", "b"), "a")));
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 45; i++) many = Recent.pushed(many, "p" + i);
        System.out.println("push4=" + many.size() + "," + many.get(0) + "," + many.get(many.size() - 1));
        List<String> src = new ArrayList<>(Arrays.asList("a", "b"));
        Recent.pushed(src, "b");
        System.out.println("push5=" + String.join(",", src));
    }
}
"""


class RecentOrder(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        home = os.environ.get("JAVA_HOME")
        javac = str(Path(home) / "bin/javac") if home else shutil.which("javac")
        java = str(Path(home) / "bin/java") if home else shutil.which("java")
        if not javac or not java or not Path(javac).exists():
            raise unittest.SkipTest("requires a JDK (JAVA_HOME or javac on PATH)")
        text = read("Recent.java")
        parts = [re.search(r"^    static final int MAX = \d+;$", text, re.M)]
        for sig in (r"static List<String> pushed\(", r"static int rank\(", r"static List<HomeApps.Item> sorted\("):
            parts.append(re.search(r"^    " + sig + r".*?^    \}$", text, re.M | re.S))
        if not all(parts):
            raise AssertionError("Recent.MAX, pushed, rank or sorted not found")
        cls.tmp = tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls.tmp.cleanup)
        work = Path(cls.tmp.name)
        (work / "HomeApps.java").write_text(
            "final class HomeApps {\n    static final class Item { String pkg; }\n}\n", encoding="utf-8")
        (work / "Recent.java").write_text(
            "import java.util.ArrayList;\nimport java.util.Collections;\nimport java.util.List;\n"
            "final class Recent {\n" + "\n".join(p.group(0) for p in parts) + "\n}\n", encoding="utf-8")
        (work / "Check.java").write_text(HARNESS, encoding="utf-8")
        files = [str(work / n) for n in ("HomeApps.java", "Recent.java", "Check.java")]
        result = subprocess.run([javac, "-nowarn", "-d", str(work), *files], capture_output=True, text=True, timeout=120)
        if result.returncode:
            raise AssertionError(result.stdout + result.stderr)
        run = subprocess.run([java, "-cp", str(work), "Check"], capture_output=True, text=True, timeout=60)
        if run.returncode:
            raise AssertionError(run.stderr)
        cls.out = dict(line.split("=", 1) for line in run.stdout.split())

    def test_opened_first_newest_first_rest_in_own_order(self):
        self.assertEqual(self.out["sorted1"], "b,d,a,c")
        self.assertEqual(self.out["sorted3"], "d,a,-,c")

    def test_nothing_opened_keeps_manual_order(self):
        self.assertEqual(self.out["sorted2"], "a,b,c")

    def test_pushed_moves_to_front_once(self):
        self.assertEqual(self.out["push1"], "c,a,b")
        self.assertEqual(self.out["push2"], "z,a,b")
        self.assertEqual(self.out["push3"], "a,b")

    def test_pushed_caps_and_drops_oldest(self):
        self.assertEqual(self.out["push4"], "40,p44,p5")

    def test_pushed_leaves_its_input_alone(self):
        self.assertEqual(self.out["push5"], "a,b")


class RecentWiring(unittest.TestCase):
    def test_off_by_default(self):
        self.assertIn('getBoolean("shelfRecent", false)', read("Prefs.java"))
        b = body(read("Prefs.java"), "    static void setShelfRecent(boolean on) {")
        self.assertIn("if (!on) Recent.clear(app);", b)

    def test_noted_only_while_on(self):
        b = body(read("Recent.java"), "    static void note(Context c, String pkg) {")
        self.assertIn("!Prefs.shelfRecent()) return;", b)

    def test_shelf_ordered_only_when_on(self):
        b = body(read("HomeApps.java"), "    static List<Item> shelf(Context c, boolean extract) {")
        self.assertIn("return Prefs.shelfRecent() ? Recent.order(c, items) : items;", b)

    def test_launches_are_noted(self):
        b = body(read("HomeApps.java"), "    static boolean open(Activity a, Item it, View from, int x, int y, int w, int h) {")
        self.assertIn("if (it.kind == APP) Recent.note(a, it.pkg);", b)
        self.assertIn("Recent.note(this, pkg);", body(read("DetailActivity.java"), "    private void watch(String pkg) {"))

    def test_resume_reorders_without_bumping_prefs(self):
        view = read("HomeView.java")
        b = body(view, "    void resume() {")
        self.assertLess(b.index("if (Prefs.shelfRecent()) reorderRecent();"), b.index("if (!again) reload();"))
        self.assertEqual(len(re.findall(r"\breload\(\);", b)), 1)
        self.assertIn("Recent.orderKnown(shelf)", body(view, "    private void reorderRecent() {"))
        r = body(view, "    private void reload() {")
        self.assertIn("Recent.order(c, from)", r)
        self.assertIn("Prefs.version != appsVersion", r)   # #46's guard is still there
        self.assertEqual(read("Recent.java").count("version++"), 1)   # only Recent.changed(), never Prefs.version
        self.assertNotIn("Prefs.version", read("Recent.java"))

    def test_no_move_while_recent(self):
        self.assertIn("Prefs.shelfRecent()) return false;", body(read("HomeView.java"), "    private boolean canReorder() {"))

    def test_settings_row_and_config_key(self):
        s = read("SettingsActivity.java")
        self.assertIn('ORDER_ROW = "#order"', s)
        self.assertIn("R.string.shelf_order_recent", s)
        self.assertIn("R.string.shelf_edit_hint_recent", s)
        self.assertIn('o.has("shelf_order")', read("TvConfig.java"))
        self.assertEqual(json.loads((UI / "config.example.json").read_text(encoding="utf-8"))["shelf_order"], "manual")

    def test_no_new_permission_or_shared_storage(self):
        text = read("Recent.java")
        for word in ("getSharedPreferences", "MODE_WORLD", "getExternal", "http"):
            self.assertNotIn(word, text)
        manifest = (UI / "app/AndroidManifest.xml").read_text(encoding="utf-8")
        self.assertNotIn("android.permission.QUERY_ALL_PACKAGES", manifest)


if __name__ == "__main__":
    unittest.main()
