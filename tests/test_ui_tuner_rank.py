"""CleanYourTV UI: Live TV tunes the digital antenna when a TV lists several hardware tuners (#40).

HomeApps.tunerInput used to take the first system "/HW" tuner, which on a Haier (AnalogInputService/HW1 listed first)
or a Cecotec (TunerInputService/HW1 analog, listed first) opened the analogue tuner on static. HomeApps.tunerRank,
from Free My TV, ranks digital antenna, then cable, then analogue, then the lowest HW number. This test compiles the
real tunerRank out of HomeApps.java with plain javac (no Android SDK) and runs it.

Run from the repository root: python3 -m unittest discover -s tests -p test_ui_tuner_rank.py -v
"""
import os
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
HOME_APPS = ROOT / "launcher-ui/app/src/io/cleanyourtv/ui/HomeApps.java"


def source():
    return HOME_APPS.read_text(encoding="utf-8")


def tuner_input_body(text):
    start = text.index("    static String tunerInput(Context c) {")
    return text[start:text.index("\n    }\n", start)]


class TunerRankSource(unittest.TestCase):
    def test_first_hardware_tuner_no_longer_wins(self):
        body = tuner_input_body(source())
        self.assertNotIn('if (info.getId().contains("/HW")) return info.getId();', body)
        self.assertIn("tunerRank(info.getId()) < tunerRank(hardware)", body)
        self.assertIn("return hardware != null ? hardware : system != null ? system : fallback;", body)

    def test_live_tv_still_pinned(self):
        # d179f941 and c25c1966: Live TV always names the tuner and an explicit activity.
        text = source()
        self.assertIn("TvContract.buildChannelsUriForInput(tuner)", text)
        self.assertIn("TvContract.buildChannelUriForPassthroughInput(tuner)", text)
        self.assertIn("channels.setComponent(component(ri))", text)


class TunerRank(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        home = os.environ.get("JAVA_HOME")
        javac = str(Path(home) / "bin/javac") if home else shutil.which("javac")
        java = str(Path(home) / "bin/java") if home else shutil.which("java")
        if not javac or not java or not Path(javac).exists():
            raise unittest.SkipTest("requires a JDK (JAVA_HOME or javac on PATH)")
        text = source()
        field = re.search(r"^    private static final Pattern HW = .*;$", text, re.M)
        method = re.search(r"^    static int tunerRank\(String id\) \{\n.*?^    \}$", text, re.M | re.S)
        if not field or not method:
            raise AssertionError("HomeApps.HW or HomeApps.tunerRank not found")
        cls.tmp = tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls.tmp.cleanup)
        work = Path(cls.tmp.name)
        (work / "Rank.java").write_text(
            "import java.util.Locale;\nimport java.util.regex.Matcher;\nimport java.util.regex.Pattern;\n"
            "public class Rank {\n" + field.group(0) + "\n" + method.group(0) + "\n"
            "    public static void main(String[] a) { for (String id : a) System.out.println(tunerRank(id)); }\n}\n",
            encoding="utf-8")
        result = subprocess.run([javac, "-nowarn", "-d", str(work), str(work / "Rank.java")],
                                capture_output=True, text=True, timeout=120)
        if result.returncode:
            raise AssertionError(result.stdout + result.stderr)
        cls.java, cls.work = java, work

    def ranks(self, *ids):
        result = subprocess.run([self.java, "-cp", str(self.work), "Rank", *ids],
                                capture_output=True, text=True, timeout=60)
        self.assertEqual(result.returncode, 0, result.stderr)
        return [int(n) for n in result.stdout.split()]

    def best(self, *ids):
        ranks = self.ranks(*ids)
        return ids[ranks.index(min(ranks))]   # tunerInput keeps the first of equal ranks: strict "<"

    def test_haier_digital_before_cable_before_analogue(self):
        analog = "com.mediatek.tvinput/.analog.AnalogInputService/HW1"
        cable = "com.mediatek.tvinput/.cable.CableInputService/HW2"
        tuner = "com.mediatek.tvinput/.tuner.TunerInputService/HW3"
        self.assertEqual(self.best(analog, cable, tuner), tuner)
        self.assertEqual(self.best(analog, cable), cable)

    def test_cecotec_lowest_hw_number_between_one_name(self):
        hw1 = "com.mediatek.tvinput/.tuner.TunerInputService/HW1"
        hw0 = "com.mediatek.tvinput/.tuner.TunerInputService/HW0"
        self.assertEqual(self.best(hw1, hw0), hw0)

    def test_amlogic_atv_class_is_analogue(self):
        atv = "com.droidlogic.tvinput/.services.ATVInputService/HW0"
        dtv = "com.droidlogic.tvinput/.services.DTVInputService/HW16"
        self.assertEqual(self.best(atv, dtv), dtv)

    def test_atv_in_the_package_name_is_not_analogue(self):
        self.assertLess(self.ranks("com.example.atvtuner/.TunerInputService/HW0")[0], 1000)

    def test_odd_hw_number_sorts_last_of_its_kind(self):
        self.assertEqual(self.ranks("com.example.tv/.TunerInputService/HW12345", "com.example.tv/.TunerInputService"),
                         [999, 999])


if __name__ == "__main__":
    unittest.main()
