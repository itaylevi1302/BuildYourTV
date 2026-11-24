"""CleanYourTV UI: remote.json can name its remote (vendor/product) and debounce bursts (#50).

From Free My TV a3b1af2 / 2a6ee9f (Sabrina, onn: only the read remote's BUTTON_3..10 are ours, so a gamepad keeps its
own) and 6fbaba1 (Xiaomi sends one press as a burst: the app opens once). RemoteKeys reads optional top-level "vendor",
"product" and "debounce_ms". This checks onKey's order in the source, then compiles the pure helpers deviceId, matches
and burst out of RemoteKeys.java with plain javac (no Android SDK) and runs them.

Run from the repository root: python3 -m unittest discover -s tests -p test_ui_remote_match.py -v
"""
import os
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REMOTE_KEYS = ROOT / "launcher-ui/app/src/io/cleanyourtv/ui/RemoteKeys.java"


def source():
    return REMOTE_KEYS.read_text(encoding="utf-8")


def body(text, signature):
    start = text.index(signature)
    return text[start:text.index("\n    }\n", start)]


class RemoteMatchSource(unittest.TestCase):
    def test_on_key_order(self):
        b = body(source(), "    static boolean onKey(Context c, KeyEvent e) {")
        gamepad = b.index("InputDevice.SOURCE_GAMEPAD")
        found = b.index("if (found == null) return ")
        remote = b.index("if (!fromRemote(e)) return false;")
        action = b.index("if (action.isEmpty()) return false;")
        self.assertLess(gamepad, found)
        self.assertLess(found, remote)
        self.assertLess(remote, action)

    def test_debounced_press_is_consumed_not_run(self):
        b = body(source(), "    static boolean onKey(Context c, KeyEvent e) {")
        self.assertIn("burst(lastButton, lastDown, found.id, e.getEventTime(), cachedDebounce)", b)
        self.assertIn("if (!again) run(c, action);", b)
        self.assertTrue(b.rstrip().endswith("return true;   // down, repeats and up alike, debounced or not"))

    def test_fields_reset_and_read_with_the_buttons(self):
        b = body(source(), "    static List<Button> buttons(Context c) {")
        reset = b.index("cachedVendor = -1;")
        self.assertLess(reset, b.index("new JSONObject(text)"))
        self.assertIn('deviceId(o.isNull("vendor") ? null : o.opt("vendor"), "vendor")', b)
        self.assertIn('deviceId(o.isNull("product") ? null : o.opt("product"), "product")', b)
        self.assertIn('Math.min(MAX_DEBOUNCE_MS, o.optLong("debounce_ms", 0))', b)

    def test_documented(self):
        readme = (ROOT / "launcher-ui/README.md").read_text(encoding="utf-8")
        section = readme[readme.index("## remote.json"):]
        section = section[:section.index("\n## ", 1)]
        for field in ("vendor", "product", "debounce_ms"):
            self.assertIn("| `%s` |" % field, section)
        design = (ROOT / "docs/ui/DESIGN.md").read_text(encoding="utf-8")
        self.assertIn("`debounce_ms`", design)


class RemoteMatchHelpers(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        home = os.environ.get("JAVA_HOME")
        javac = str(Path(home) / "bin/javac") if home else shutil.which("javac")
        java = str(Path(home) / "bin/java") if home else shutil.which("java")
        if not javac or not java or not Path(javac).exists():
            raise unittest.SkipTest("requires a JDK (JAVA_HOME or javac on PATH)")
        text = source()
        methods = []
        for name in ("deviceId", "matches", "burst"):
            m = re.search(r"^    static \w+ %s\(.*?\) \{\n.*?^    \}$" % name, text, re.M | re.S)
            if not m:
                raise AssertionError("RemoteKeys.%s not found" % name)
            methods.append(m.group(0))
        cls.tmp = tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls.tmp.cleanup)
        work = Path(cls.tmp.name)
        (work / "Match.java").write_text(
            "public class Match {\n" + "\n".join(methods) + "\n"
            "    static Object arg(String a) {\n"
            "        if (a.equals(\"null\")) return null;\n"
            "        if (a.startsWith(\"i:\")) return Integer.valueOf(a.substring(2));\n"
            "        if (a.startsWith(\"l:\")) return Long.valueOf(a.substring(2));\n"
            "        if (a.startsWith(\"d:\")) return Double.valueOf(a.substring(2));\n"
            "        if (a.equals(\"true\")) return Boolean.TRUE;\n"
            "        return a.substring(2);   // s:\n"
            "    }\n"
            "    public static void main(String[] a) {\n"
            "        switch (a[0]) {\n"
            "            case \"id\":\n"
            "                for (int i = 1; i < a.length; i++) {\n"
            "                    try { System.out.println(deviceId(arg(a[i]), \"vendor\")); }\n"
            "                    catch (IllegalArgumentException e) { System.out.println(\"error\"); }\n"
            "                }\n"
            "                break;\n"
            "            case \"match\":\n"
            "                System.out.println(matches(Integer.parseInt(a[1]), Integer.parseInt(a[2]),\n"
            "                        Integer.parseInt(a[3]), Integer.parseInt(a[4])));\n"
            "                break;\n"
            "            case \"burst\":\n"
            "                System.out.println(burst(a[1].equals(\"-\") ? null : a[1], Long.parseLong(a[2]), a[3],\n"
            "                        Long.parseLong(a[4]), Long.parseLong(a[5])));\n"
            "                break;\n"
            "        }\n"
            "    }\n}\n",
            encoding="utf-8")
        result = subprocess.run([javac, "-nowarn", "-d", str(work), str(work / "Match.java")],
                                capture_output=True, text=True, timeout=120)
        if result.returncode:
            raise AssertionError(result.stdout + result.stderr)
        cls.java, cls.work = java, work

    def run_java(self, *args):
        result = subprocess.run([self.java, "-cp", str(self.work), "Match", *args],
                                capture_output=True, text=True, timeout=60)
        self.assertEqual(result.returncode, 0, result.stderr)
        return result.stdout.split()

    def test_device_ids(self):
        self.assertEqual(
            self.run_java("id", "null", "i:2391", "l:49", "s:0957", "s:0x0957", "s:0X0031", "s: 3697 ", "i:0", "i:65535"),
            ["-1", "2391", "49", "2391", "2391", "49", "13975", "0", "65535"])

    def test_bad_device_ids_are_refused(self):
        self.assertEqual(
            self.run_java("id", "i:-1", "i:65536", "d:9.5", "s:", "s:0x", "s:12345", "s:zz", "s:-1", "true"),
            ["error"] * 9)

    def test_matches(self):
        self.assertEqual(self.run_java("match", "-1", "-1", "1", "2"), ["true"])
        self.assertEqual(self.run_java("match", "2391", "49", "2391", "49"), ["true"])
        self.assertEqual(self.run_java("match", "2391", "49", "2391", "50"), ["false"])
        self.assertEqual(self.run_java("match", "2391", "-1", "2391", "7"), ["true"])
        self.assertEqual(self.run_java("match", "-1", "49", "1", "49"), ["true"])
        self.assertEqual(self.run_java("match", "2391", "-1", "1118", "49"), ["false"])

    def test_burst(self):
        self.assertEqual(self.run_java("burst", "k100", "1000", "k100", "1500", "0"), ["false"])     # off
        self.assertEqual(self.run_java("burst", "-", "0", "k100", "10", "1000"), ["false"])         # first press
        self.assertEqual(self.run_java("burst", "k100", "1000", "k100", "1999", "1000"), ["true"])
        self.assertEqual(self.run_java("burst", "k100", "1000", "k100", "2000", "1000"), ["false"])
        self.assertEqual(self.run_java("burst", "k100", "1000", "s242", "1100", "1000"), ["false"])  # another button


if __name__ == "__main__":
    unittest.main()
