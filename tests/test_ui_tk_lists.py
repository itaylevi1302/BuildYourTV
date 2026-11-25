"""CleanYourTV UI: TMDB v4 sign-in and the lists shared with tk-stream (#110).

Source checks: Tmdb.java stays compilable alone (test_artwork_cache), the lists live in TmdbLists.java, comments are
read and never written, only the explicit Seen toggle goes to tk:watched, and Continue watching leads Home.

Run from the repository root: python3 -m unittest discover -s tests -p test_ui_tk_lists.py -v
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "launcher-ui/app"
SRC = APP / "src/io/cleanyourtv/ui"


def read(name):
    return (SRC / name).read_text(encoding="utf-8")


def method(text, signature, indent="    "):
    start = text.index(signature)
    return text[start:text.index("\n" + indent + "}\n", start)]


class TmdbTests(unittest.TestCase):
    def test_tmdb_compiles_alone(self):
        text = read("Tmdb.java")
        self.assertNotIn("TmdbLists", text)
        self.assertNotIn("AppLog", text)
        self.assertEqual(set(re.findall(r"R\.string\.(\w+)", text)), {"no_tmdb_key"})

    def test_v4_session_and_remote_seen(self):
        text = read("Tmdb.java")
        self.assertIn("/authentication/session/convert/4", text)
        self.assertIn('getString("seen_remote"', method(text, "static boolean isSeen("))


class ListsTests(unittest.TestCase):
    def setUp(self):
        self.text = read("TmdbLists.java")

    def test_names_and_endpoints(self):
        for needle in ['"tk:watching"', '"tk:watched"', '"testetv · Assistindo"', '"testetv · Assistidos"',
                       '"/auth/request_token"', '"/auth/access_token"', '"https://api.themoviedb.org/4"',
                       'put("public", false)', "(tk|testetv):"]:
            self.assertIn(needle, self.text)
        self.assertRegex(self.text, r'"PUT", "/list/"')

    def test_comments_are_never_written(self):
        self.assertNotIn('"comment"', self.text)

    def test_seen_goes_out_in_a_thread(self):
        self.assertIn("new Thread(", method(self.text, "static void markSeen("))

    def test_no_device_identifiers(self):
        self.assertIsNone(re.search(r"ANDROID_ID|Build\.SERIAL|getMacAddress|getDeviceId", self.text))


class ScreenTests(unittest.TestCase):
    def test_login(self):
        text = read("TmdbLoginActivity.java")
        self.assertIn("TmdbLists.signOut", text)
        self.assertIn("R.string.sync_now", text)
        self.assertIn("R.string.reconnect", text)
        self.assertIn("auth/access?request_token=", read("TmdbLists.java"))
        self.assertIn("TmdbLists.APPROVE_V4", text)

    def test_detail(self):
        text = read("DetailActivity.java")
        self.assertIn("TmdbLists.markSeen", method(text, "private void toggleSeen() {"))
        watch = method(text, "private void watch(String pkg) {")
        self.assertIn("Tmdb.setSeen(", watch)
        self.assertNotIn("TmdbLists", watch)
        self.assertIn("R.string.continue_episode", text)
        self.assertIn("R.string.resume_from", text)

    def test_menu(self):
        self.assertIn("TmdbLists.markSeen", read("TitleMenu.java"))

    def test_continue_row_leads_home(self):
        text = read("ExploreActivity.java")
        body = method(text, "private List<Section> sections(String id) {")
        self.assertIn("TmdbLists.continueTitles", body)
        self.assertLess(body.index("R.string.continue_watching"), body.index("R.string.my_list"))

    def test_no_new_permission(self):
        manifest = (APP / "AndroidManifest.xml").read_text(encoding="utf-8")
        perms = set(re.findall(r'uses-permission android:name="([^"]+)"', manifest))
        self.assertEqual(perms, {"android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE",
                                 "android.permission.WRITE_SECURE_SETTINGS", "android.permission.READ_TV_LISTINGS"})


if __name__ == "__main__":
    unittest.main()
