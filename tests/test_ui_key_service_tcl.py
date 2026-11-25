"""CleanYourTV UI's remote-key service stays off on TCL's Android 11 (#41).

There any key filter makes one volume press move two steps and a held key stick (Free My TV #325), so
build-and-install.sh refuses KEY_SERVICE=1 when the TV's manufacturer or brand is TCL and its SDK is 30, and takes
the service out when a reinstall finds it on. Elsewhere KEY_SERVICE=1 and the rebind on reinstall work as before.
This runs the script's key-service block against a fake TV whose `settings` live in a temporary folder.

Run from the repository root: python3 -m unittest discover -s tests -p test_ui_key_service_tcl.py -v
"""
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "launcher-ui/build-and-install.sh"
SVC = "io.cleanyourtv.ui/.RemoteKeyService"

# The TV side: tv() runs its command in a bash that has fake getprop, settings and sleep.
FAKE_TV = r'''
getprop() { case "$1" in ro.product.manufacturer) echo "$MAKER" ;; ro.product.brand) echo "$BRAND" ;; esac; }
settings() {
  f="$STATE/$3"
  case "$1" in
    get) if [ -f "$f" ]; then cat "$f"; else echo null; fi ;;
    put) printf '%s\n' "$4" > "$f" ;;
  esac
}
sleep() { :; }
'''


def key_block():
    """The script's key-service block: from `svc=$PKG/.RemoteKeyService` to the `fi` that closes it."""
    lines = SCRIPT.read_text(encoding="utf-8").splitlines()
    start = lines.index("svc=$PKG/.RemoteKeyService")
    end = lines.index("fi", start)
    return "\n".join(lines[start:end + 1])


def run(maker, brand, sdk, key_service, listed=None, enabled=None):
    """Runs the block; returns (stdout, stderr, the accessibility list, accessibility_enabled) after it."""
    with tempfile.TemporaryDirectory() as state:
        Path(state, "fake.sh").write_text(FAKE_TV, encoding="utf-8")
        if listed is not None:
            Path(state, "enabled_accessibility_services").write_text(listed + "\n", encoding="utf-8")
        if enabled is not None:
            Path(state, "accessibility_enabled").write_text(enabled + "\n", encoding="utf-8")
        snippet = ("set -euo pipefail\nPKG=io.cleanyourtv.ui\n"
                   "tv() { bash -c '. \"$STATE/fake.sh\"; eval \"$1\"' _ \"$1\"; }\n" + key_block() + "\n")
        env = dict(os.environ, STATE=state, MAKER=maker, BRAND=brand, TV_SDK=str(sdk))
        env.pop("KEY_SERVICE", None)
        if key_service:
            env["KEY_SERVICE"] = "1"
        p = subprocess.run(["bash", "-c", snippet], capture_output=True, text=True, env=env, check=True)

        def read(name):
            f = Path(state, name)
            return f.read_text(encoding="utf-8").strip() if f.exists() else None
        return p.stdout, p.stderr, read("enabled_accessibility_services"), read("accessibility_enabled")


@unittest.skipUnless(shutil.which("bash"), "needs bash")
class UiKeyServiceTcl(unittest.TestCase):
    def test_refused_on_tcl_android_11(self):
        out, err, listed, enabled = run("TCL", "TCL", 30, True)
        self.assertIn("KEY_SERVICE=1 refused", err)
        self.assertIsNone(listed)
        self.assertIsNone(enabled)

    def test_brand_alone_is_enough(self):
        out, err, listed, _ = run("Foo", "tcl", 30, True)
        self.assertIn("KEY_SERVICE=1 refused", err)
        self.assertIsNone(listed)

    def test_reinstall_takes_it_out_and_keeps_the_others(self):
        out, err, listed, enabled = run("TCL", "TCL", 30, False, listed="com.other/.A:" + SVC, enabled="1")
        self.assertEqual(listed, "com.other/.A")
        self.assertEqual(enabled, "1")
        self.assertIn("switched off", out)
        self.assertEqual(err, "")

    def test_reinstall_takes_out_the_only_one(self):
        out, err, listed, enabled = run("TCL", "TCL", 30, False, listed=SVC, enabled="1")
        self.assertEqual(listed, "")
        self.assertEqual(enabled, "0")

    def test_nothing_listed_nothing_said(self):
        out, err, listed, enabled = run("TCL", "TCL", 30, False)
        self.assertEqual((out, err, listed, enabled), ("", "", None, None))

    def test_tcl_other_sdk_still_turns_on(self):
        for sdk in (28, 29, 31, 34):
            with self.subTest(sdk=sdk):
                out, err, listed, enabled = run("TCL", "TCL", sdk, True)
                self.assertEqual((listed, enabled), (SVC, "1"))
                self.assertIn("remote buttons enabled", out)

    def test_other_maker_android_11_still_turns_on(self):
        out, err, listed, enabled = run("Sony", "Sony", 30, True, listed="com.other/.A")
        self.assertEqual((listed, enabled), ("com.other/.A:" + SVC, "1"))

    def test_rebind_on_reinstall_elsewhere(self):
        out, err, listed, enabled = run("Sony", "Sony", 30, False, listed=SVC, enabled="1")
        self.assertEqual((listed, enabled), (SVC, "1"))
        self.assertIn("remote buttons enabled", out)


if __name__ == "__main__":
    unittest.main()
