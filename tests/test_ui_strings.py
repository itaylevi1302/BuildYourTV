"""Check the custom launcher's default and Ukrainian string resources."""

import re
import unittest
from pathlib import Path
from xml.etree import ElementTree

APP = Path(__file__).resolve().parents[1] / "launcher-ui/app"
FORMAT = re.compile(r"%(?:\d+\$)?[ds]")


def catalog(path):
    return {(el.tag, el.attrib["name"]): el for el in ElementTree.parse(path).getroot()}


class LauncherStrings(unittest.TestCase):
    def test_ukrainian_covers_the_strings_used_by_the_launcher(self):
        english = catalog(APP / "res/values/strings.xml")
        ukrainian = catalog(APP / "res/values-uk/strings.xml")
        refs = set()
        for path in (APP / "src").rglob("*.java"):
            refs.update(re.findall(r"(?<!\.)\bR\.(string|plurals)\.(\w+)", path.read_text(encoding="utf-8")))
        self.assertEqual(refs - english.keys(), set())
        self.assertEqual(refs - ukrainian.keys() - {("string", "app_name")}, set())
        for key in ("titles", "seasons"):
            forms = {item.attrib["quantity"] for item in ukrainian["plurals", key]}
            self.assertEqual(forms, {"one", "few", "many", "other"})

    def test_localized_strings_keep_the_format_parameters(self):
        english = catalog(APP / "res/values/strings.xml")
        for path in sorted((APP / "res").glob("values-*/strings.xml")):
            for key, localized in catalog(path).items():
                if key[0] != "string":
                    continue
                with self.subTest(locale=path.parent.name, name=key[1]):
                    self.assertIn(key, english)
                    self.assertEqual(sorted(FORMAT.findall(english[key].text or "")),
                                     sorted(FORMAT.findall(localized.text or "")))
