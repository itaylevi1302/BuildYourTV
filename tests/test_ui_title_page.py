"""CleanYourTV UI: the title page in the tk-stream style (#111).

A hero (poster, title, pills, overview, credits, actions), the seasons as a dropdown with the episodes on the page, the
facts last, a pinned Back, and the hold-OK menu that watches and rates. These are source checks (the page is drawn in
code, so there is nothing to parse in a layout): what is there, and in which order the page puts it.

Run from the repository root: python3 -m unittest discover -s tests -p test_ui_title_page.py -v
"""
import re
import unittest
from pathlib import Path
from xml.etree import ElementTree

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "launcher-ui/app"
SRC = APP / "src/io/cleanyourtv/ui"
ICONS = ["ic_play", "ic_replay", "ic_trailer", "ic_bookmark", "ic_bookmark_border", "ic_favorite", "ic_favorite_border",
         "ic_check", "ic_star", "ic_star_border", "ic_arrow_back", "ic_playlist_add", "ic_expand_more"]
NEW_STRINGS = ["back", "watch", "start_over", "episodes", "more_like_this", "credit_director", "credit_creators", "credit_network",
               "facts", "original_title", "released", "last_aired", "runtime", "original_language", "budget", "revenue"]


def read(name):
    return (SRC / name).read_text(encoding="utf-8")


def method(text, signature, indent="    "):
    """The method that starts at `signature`, up to its closing brace at `indent`."""
    start = text.index(signature)
    return text[start:text.index("\n" + indent + "}\n", start)]


def in_order(test, text, needles):
    at = -1
    for needle in needles:
        found = text.find(needle, at + 1)
        test.assertGreater(found, -1, needle)
        test.assertGreater(found, at, f"{needle} is out of order")
        at = found


class Detail(unittest.TestCase):
    def setUp(self):
        self.detail = read("DetailActivity.java")

    def test_the_actions_come_in_the_order_of_the_hero(self):
        in_order(self, method(self.detail, "private View hero("), ["playTrailer(", "toggleList()", "toggleFavorite()", "toggleSeen()", "rate()"])

    def test_the_sections_come_in_the_order_of_the_page(self):
        in_order(self, method(self.detail, "private void show("), ["hero(", "R.string.where_to_watch", "episodes(", "R.string.more_like_this",
                                                                     'collection.optString("name")', "R.string.cast", "keywords(", "R.string.reviews", "facts("])

    def test_the_backdrop_is_one_small_blurred_copy(self):
        self.assertNotIn("Tmdb.BACKDROP", self.detail)
        self.assertIn("ExploreActivity.blur(", self.detail)
        self.assertIn("t/p/w300", self.detail)
        self.assertIn("Tmdb.POSTER", self.detail)
        self.assertIn("    static Bitmap blur(", read("ExploreActivity.java"))

    def test_back_is_pinned_and_left_goes_to_it(self):
        self.assertIn("R.drawable.ic_arrow_back", self.detail)
        self.assertIn("R.string.back", self.detail)
        self.assertIn("finish()", self.detail)
        keys = method(self.detail, "@Override public boolean dispatchKeyEvent")
        self.assertIn("KEYCODE_DPAD_LEFT", keys)
        self.assertIn("FOCUS_LEFT", keys)

    def test_the_page_asks_tmdb_once_and_shares_the_path(self):
        self.assertIn("static String path(String kind, int id)", self.detail)
        self.assertIn("path(kind, id)", self.detail)
        self.assertRegex(self.detail, r"append_to_response=[^\"]*external_ids")
        self.assertIn("static String[] firstApp(Context c, JSONObject region)", self.detail)

    def test_progress_comes_from_tmdb_lists_and_the_primary_button_continues(self):
        self.assertIn("TmdbLists.progress(", self.detail)
        self.assertIn("resume()", method(self.detail, "private View hero("))
        self.assertIn("R.string.start_over", self.detail)
        self.assertIn("R.string.watch_time_left", self.detail)

    def test_seen_still_goes_through_the_account_sync(self):
        self.assertIn("TmdbLists.markSeen(", method(self.detail, "private void toggleSeen()"))

    def test_the_facts_are_last_and_focusable(self):
        facts = method(self.detail, "private View facts(")
        self.assertIn("TvUi.softFocus(", facts)
        for token in ["original_title", "released", "last_aired", "runtime", "episodes", "original_language", "R.string.production", "budget", "revenue", '"TMDB"']:
            self.assertIn(token, facts)

    def test_the_old_title_page_pieces_are_gone(self):
        for token in ["you_may_also_like", "directed_by_prefix", "created_by_prefix", "SeasonActivity"]:
            self.assertNotIn(token, self.detail)

    def test_the_title_page_is_not_mirrored(self):
        self.assertIn("not mirrored in Hebrew", self.detail)


class Episodes(unittest.TestCase):
    def setUp(self):
        self.detail = read("DetailActivity.java")
        self.list = read("EpisodeList.java")
        self.tvui = read("TvUi.java")

    def test_seasons_are_a_dropdown_over_an_episode_list(self):
        self.assertFalse((SRC / "SeasonActivity.java").exists())
        self.assertNotIn(".SeasonActivity", (APP / "AndroidManifest.xml").read_text(encoding="utf-8"))
        self.assertIn("TvUi.dropdown(", self.detail)
        self.assertIn("new EpisodeList(", self.detail)
        dropdown = method(self.tvui, "static PopupWindow dropdown(")
        self.assertIn("ScrollView", dropdown)
        self.assertIn('"✓  "', dropdown)

    def test_the_list_builds_a_few_episodes_at_a_time(self):
        self.assertIn('"/tv/" + series + "/season/" + number', self.list)
        self.assertIn("new Thread(", self.list)
        self.assertIn("t/p/w300", self.list)
        self.assertIn("CHUNK", self.list)
        self.assertIn("public void requestChildFocus(", self.list)

    def test_ok_marks_seen_menu_rates_and_watching_asks_only_allowed_apps(self):
        self.assertIn("KEYCODE_MENU", self.list)
        self.assertIn("Tmdb.setEpisodeSeen(", self.list)
        watch = method(self.list, "private void watchEpisode(")
        self.assertLess(watch.index("allowed("), watch.index("ask("))
        self.assertIn("WatchProviders.choose(", watch)
        self.assertNotIn("recreate()", self.list)

    def test_the_still_shows_the_progress(self):
        self.assertIn("setProgress(", self.list)
        self.assertIn("progress.season == season", self.list)
        self.assertIn("progress.d > 0", self.list)


class Menu(unittest.TestCase):
    def test_the_hold_ok_menu_watches_and_rates(self):
        menu = read("TitleMenu.java")
        pick = method(menu, "private static void pick(")
        in_order(self, pick, ["R.string.watch", "R.string.details", "R.string.add_to_my_list", "R.string.mark_favorite",
                              "R.string.mark_as_seen", "R.string.rate"])
        self.assertIn("QuickWatch.open(", pick)
        self.assertIn("TmdbLists.markSeen(", pick)
        self.assertIn("static void rate(Activity a, Tmdb.Title t, int current, IntConsumer done)", menu)
        self.assertIn("TvUi.dropdown(", menu)

    def test_quick_watch_asks_the_same_request_and_never_asks_consent(self):
        quick = read("QuickWatch.java")
        self.assertIn("DetailActivity.path(", quick)
        self.assertIn("new Thread(", quick)
        self.assertLess(quick.index("allowed("), quick.index("ask("))
        self.assertNotIn("watch_provider_consent", quick)
        self.assertNotIn("consentChain", quick)


class Resources(unittest.TestCase):
    def test_the_icons_are_vectors(self):
        for name in ICONS:
            text = (APP / "res/drawable" / (name + ".xml")).read_text(encoding="utf-8")
            with self.subTest(icon=name):
                self.assertTrue(text.startswith("<?xml"))
                self.assertIn("<vector", text)

    def test_the_new_strings_are_in_english_portuguese_and_ukrainian(self):
        for folder in ["values", "values-pt", "values-uk"]:
            names = {el.attrib["name"] for el in ElementTree.parse(APP / "res" / folder / "strings.xml").getroot() if el.tag == "string"}
            with self.subTest(folder=folder):
                self.assertEqual(set(NEW_STRINGS) - names, set())

    def test_the_dropped_strings_are_gone_everywhere(self):
        for path in (APP / "res").glob("values*/strings.xml"):
            text = path.read_text(encoding="utf-8")
            for name in ["you_may_also_like", "directed_by_prefix", "created_by_prefix"]:
                with self.subTest(folder=path.parent.name, name=name):
                    self.assertIsNone(re.search(r'name="%s"' % name, text))


if __name__ == "__main__":
    unittest.main()
