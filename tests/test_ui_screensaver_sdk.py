"""CleanYourTV UI's screensaver: the copy in the main process on Android 11 to 12L, the one in :home elsewhere.

On Android 11, 12 and 12L (SDK 30 to 32) the system starts DreamActivity in the app's default process, so the
DreamService must be ScreensaverAlone (main process); in :home it fails with "BinderProxy cannot be cast" (#39).
The res/values-vNN bools enable one of the two services per SDK, and build-and-install.sh must point
screensaver_components at the same one. This checks both against the same table.

Run from the repository root: python3 -m unittest discover -s tests -p test_ui_screensaver_sdk.py -v
"""
import re
import shutil
import subprocess
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
UI = ROOT / "launcher-ui"
RES = UI / "app/res"
SCRIPT = UI / "build-and-install.sh"

ALONE = {30, 31, 32}
SDKS = range(28, 37)


def bools(folder):
    text = (RES / folder / "bools.xml").read_text(encoding="utf-8")
    return {k: v == "true" for k, v in re.findall(r'<bool name="(dream_\w+)">(true|false)</bool>', text)}


def resolved(sdk):
    """The bools Android picks on this SDK: the highest values-vNN with NN <= sdk, over values/."""
    out = bools("values")
    levels = sorted(int(p.name[len("values-v"):]) for p in RES.glob("values-v*") if (p / "bools.xml").exists())
    for level in levels:
        if level <= sdk:
            out.update(bools("values-v%d" % level))
    return out


def script_dream(sdk):
    """Runs the script's screensaver branch alone (the `case "${TV_SDK...` line) with this TV_SDK."""
    lines = SCRIPT.read_text(encoding="utf-8").splitlines()
    branch = [l for l in lines if "DREAM=$PKG/.ScreensaverAlone" in l]
    if len(branch) != 1:
        raise AssertionError("expected one line setting DREAM=$PKG/.ScreensaverAlone in build-and-install.sh")
    snippet = "PKG=io.cleanyourtv.ui\nTV_SDK=%d\n%s\necho \"$DREAM\"\n" % (sdk, branch[0].strip())
    return subprocess.run(["bash", "-c", snippet], capture_output=True, text=True, check=True).stdout.strip()


class UiScreensaverSdk(unittest.TestCase):
    def test_bools_per_sdk(self):
        for sdk in SDKS:
            with self.subTest(sdk=sdk):
                b = resolved(sdk)
                self.assertEqual(b["dream_alone"], sdk in ALONE)
                self.assertEqual(b["dream_in_home"], sdk not in ALONE)

    @unittest.skipUnless(shutil.which("bash"), "needs bash")
    def test_script_matches_bools(self):
        for sdk in SDKS:
            with self.subTest(sdk=sdk):
                want = "io.cleanyourtv.ui/." + ("ScreensaverAlone" if sdk in ALONE else "Screensaver")
                self.assertEqual(script_dream(sdk), want)

    def test_prefs_migrates_dream_component(self):
        text = (UI / "app/src/io/cleanyourtv/ui/Prefs.java").read_text(encoding="utf-8")
        self.assertIn("fixDreamComponent", text)
        init = re.search(r"static void init\(Context c\) \{(.*?)\n    \}", text, re.S).group(1)
        self.assertIn("fixDreamComponent();", init)

    def test_manifest_follows_bools(self):
        text = (UI / "app/AndroidManifest.xml").read_text(encoding="utf-8")
        self.assertRegex(text, r'android:name="\.Screensaver"\s+android:process=":home"\s+android:enabled="@bool/dream_in_home"')
        self.assertRegex(text, r'android:name="\.ScreensaverAlone"\s+android:enabled="@bool/dream_alone"')


if __name__ == "__main__":
    unittest.main()
