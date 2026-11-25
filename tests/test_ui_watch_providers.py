"""CleanYourTV UI: watch providers under Where to watch (#106).

Apps on the TV that answer "do you have this title?" (docs/specs/provider.md). This reads the sources and checks the
promises the contract makes: nothing is asked before the person allowed an app, the question goes through an unstable
client with a 1500 ms deadline, the answer is capped and the item opens only inside the provider's own package, the
manifest adds a query and no permission, and the label rules run with the JDK's javac when one is there.

Run from the repository root: python3 -m unittest discover -s tests -p test_ui_watch_providers.py -v
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


def read(name):
    return (SRC / name).read_text(encoding="utf-8")


def method(text, signature, indent="    "):
    """The method that starts at `signature`, up to its closing brace at `indent`."""
    start = text.index(signature)
    return text[start:text.index("\n" + indent + "}\n", start)]


def jdk_tool(name):
    home = os.environ.get("JAVA_HOME")
    path = str(Path(home) / "bin" / name) if home else shutil.which(name)
    if not path or not Path(path).exists():
        return None
    ok = subprocess.run([path, "-version"], capture_output=True, timeout=60).returncode == 0
    return path if ok else None


class Manifest(unittest.TestCase):
    def setUp(self):
        self.manifest = (APP / "AndroidManifest.xml").read_text(encoding="utf-8")

    def test_queries_the_watch_provider_action(self):
        queries = self.manifest[self.manifest.index("<queries>"):self.manifest.index("</queries>")]
        self.assertRegex(queries, r'<intent>\s*<action android:name="io\.cleanyourtv\.action\.WATCH_PROVIDER" />\s*</intent>')

    def test_no_new_permission(self):
        perms = set(re.findall(r'<uses-permission[^>]*android:name="([^"]+)"', self.manifest))
        self.assertFalse([p for p in perms if "provider" in p.lower() or "QUERY_ALL" in p])


class AskingPromises(unittest.TestCase):
    def setUp(self):
        self.src = read("WatchProviders.java")

    def test_the_call_is_unstable_with_the_contract_numbers(self):
        self.assertIn("acquireUnstableContentProviderClient", self.src)
        self.assertIn('METHOD = "availability"', self.src)
        self.assertIn("TIMEOUT_MS = 1500", self.src)
        self.assertIn("MAX_ITEMS = 5", self.src)
        self.assertIn("URI_MAX = 2048", self.src)
        self.assertIn("RESPONSE_MAX = 32 * 1024", self.src)

    def test_opens_only_inside_the_providers_package(self):
        self.assertIn(".setPackage(", self.src)
        self.assertIn('"file".equalsIgnoreCase(scheme)', self.src)

    def test_only_allowed_providers_are_asked(self):
        allowed = method(self.src, "    static List<Provider> allowed(List<Provider> found) {")
        self.assertIn("State.ON", allowed)

    def test_no_network_in_the_feature(self):
        for name in ("WatchProviders.java", "WatchProviderLabels.java"):
            self.assertNotRegex(read(name), r"HttpURLConnection|java\.net\.|Tmdb\.get\(")

    def test_only_this_file_calls_a_provider(self):
        for path in SRC.glob("*.java"):
            if path.name == "WatchProviders.java":
                continue
            self.assertNotIn("acquireUnstableContentProviderClient", path.read_text(encoding="utf-8"), path.name)
            self.assertNotRegex(path.read_text(encoding="utf-8"), r'WatchProviders\.METHOD|"availability"', path.name)

    def test_the_page_asks_external_ids(self):
        self.assertRegex(read("DetailActivity.java"), r'append_to_response=[^"]*external_ids')

    def test_decisions_never_restart_home(self):
        prefs = read("Prefs.java")
        for sig in ("    static void setWatchProvider(", "    static void setWatchProvidersOrder("):
            body = prefs[prefs.index(sig):prefs.index("\n", prefs.index(sig))]
            self.assertNotIn("put(", body)
            self.assertNotIn("version++", body)

    def test_asking_happens_after_consent_and_off_the_main_thread(self):
        detail = read("DetailActivity.java")
        ask = method(detail, "    private void askProviders(boolean mayPrompt) {")
        self.assertLess(ask.index("consentChain("), ask.index("WatchProviders.ask("))
        self.assertIn("new Thread(", ask)
        self.assertIn("WatchProviders.allowed(", ask)


class Labels(unittest.TestCase):
    def test_label_rules_run(self):
        javac, java = jdk_tool("javac"), jdk_tool("java")
        if not javac or not java:
            self.skipTest("needs a JDK (javac and java)")
        with tempfile.TemporaryDirectory() as out:
            build = subprocess.run([javac, "-nowarn", "-encoding", "UTF-8", "-d", out, str(SRC / "WatchProviderLabels.java"),
                                    str(ROOT / "tests/watch_providers/WatchProviderLabelsTest.java")],
                                   capture_output=True, text=True, timeout=120)
            self.assertEqual(build.returncode, 0, build.stdout + build.stderr)
            run = subprocess.run([java, "-ea", "-cp", out, "io.cleanyourtv.ui.WatchProviderLabelsTest"],
                                 capture_output=True, text=True, timeout=120)
            self.assertEqual(run.returncode, 0, run.stdout + run.stderr)
            self.assertRegex(run.stdout, r"WatchProviderLabels: \d+ assertions passed")


if __name__ == "__main__":
    unittest.main()
