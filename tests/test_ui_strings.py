"""Check the custom launcher's string resources (launcher-ui/app/res/values*/strings.xml).

The same checks Free My TV's `tools/translate.py --check` runs on its catalogues, for Android resources: every string
the code asks for exists in English, no translation keeps a key English no longer has, the format parameters match,
plurals carry the forms the language needs, and every translated folder is a language the settings offer.
"""

import re
import unittest
from pathlib import Path
from xml.etree import ElementTree

APP = Path(__file__).resolve().parents[1] / "launcher-ui/app"
FORMAT = re.compile(r"%(?:\d+\$)?[dsf]")
# CLDR plural categories for the languages that use more than one and other.
PLURAL_FORMS = {"ru": {"one", "few", "many", "other"}, "uk": {"one", "few", "many", "other"}}


def entries(path):
    return [el for el in ElementTree.parse(path).getroot() if el.tag in ("string", "plurals")]


def catalog(path):
    return {(el.tag, el.attrib["name"]): el for el in entries(path)}


def locales():
    return sorted((APP / "res").glob("values-*/strings.xml"))


def params(text):
    return sorted(FORMAT.findall(text or ""))


def references():
    refs = set()
    for path in (APP / "src").rglob("*.java"):
        refs.update(re.findall(r"(?<!\.)\bR\.(string|plurals)\.(\w+)", path.read_text(encoding="utf-8")))
    for path in [APP / "AndroidManifest.xml", *(APP / "res").rglob("*.xml")]:
        refs.update(("string", name) for name in re.findall(r"@string/(\w+)", path.read_text(encoding="utf-8")))
    return refs


class LauncherStrings(unittest.TestCase):
    def test_every_string_the_code_uses_is_in_english(self):
        self.assertEqual(references() - catalog(APP / "res/values/strings.xml").keys(), set())

    def test_english_has_no_unused_strings(self):
        unused = catalog(APP / "res/values/strings.xml").keys() - references() - {("string", "app_name")}
        self.assertEqual(unused, set())

    def test_no_name_is_defined_twice(self):
        for path in [APP / "res/values/strings.xml", *locales()]:
            names = [(el.tag, el.attrib["name"]) for el in entries(path)]
            with self.subTest(locale=path.parent.name):
                self.assertEqual(sorted({n for n in names if names.count(n) > 1}), [])

    def test_ukrainian_covers_the_strings_used_by_the_launcher(self):
        ukrainian = catalog(APP / "res/values-uk/strings.xml")
        self.assertEqual(references() - ukrainian.keys() - {("string", "app_name")}, set())

    def test_translations_keep_no_key_english_dropped(self):
        english = catalog(APP / "res/values/strings.xml")
        for path in locales():
            with self.subTest(locale=path.parent.name):
                self.assertEqual(sorted(catalog(path).keys() - english.keys()), [])

    def test_localized_strings_keep_the_format_parameters(self):
        english = catalog(APP / "res/values/strings.xml")
        for path in locales():
            for key, localized in catalog(path).items():
                if key[0] != "string" or key not in english:
                    continue
                with self.subTest(locale=path.parent.name, name=key[1]):
                    self.assertEqual(params(english[key].text), params(localized.text))

    def test_plurals_have_the_language_forms_and_the_count(self):
        english = catalog(APP / "res/values/strings.xml")
        for path in [APP / "res/values/strings.xml", *locales()]:
            lang = path.parent.name.partition("-")[2]
            for key, localized in catalog(path).items():
                if key[0] != "plurals":
                    continue
                items = {item.attrib["quantity"]: item.text for item in localized}
                expected = params(next(i.text for i in english[key] if i.attrib["quantity"] == "other"))
                with self.subTest(locale=path.parent.name, name=key[1]):
                    self.assertIn("other", items)
                    if lang in PLURAL_FORMS:
                        self.assertEqual(set(items), PLURAL_FORMS[lang])
                    for quantity, text in items.items():
                        self.assertEqual(params(text), expected, quantity)

    def test_every_translation_is_a_language_the_settings_offer(self):
        source = (APP / "src/io/cleanyourtv/ui/SettingsActivity.java").read_text(encoding="utf-8")
        offered = set(re.findall(r'"(\w+)"', re.search(r"LANGUAGES = \{([^}]*)\}", source).group(1))) - {"auto", "en"}
        folders = {path.parent.name.partition("-")[2] for path in locales()}
        self.assertEqual(folders, offered)


if __name__ == "__main__":
    unittest.main()
