"""CleanYourTV UI: Explore by category, genre tiles and category pages with sorting (#112).

Source checks: the By category tab on Movies and Shows, the genre list and counts behind the tiles, the discover query
of a category page and its sorts, the grid that shares its line's width, and the strings in every language.

Run from the repository root: python3 -m unittest discover -s tests -p test_ui_explore_categories.py -v
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
    """The method that starts at `signature`, up to its closing brace at `indent`."""
    start = text.index(signature)
    return text[start:text.index("\n" + indent + "}\n", start)]


NEW_STRINGS = ["by_category", "explore_by_category", "all_movies", "all_shows", "recently_opened", "recents_empty",
               "sort_popular", "sort_newest", "sort_top_rated", "sort_a_z"]
NEW_PLURALS = ["movies_count", "shows_count"]


class ExploreCategories(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.explore = read("ExploreActivity.java")
        cls.tvui = read("TvUi.java")
        cls.tmdb = read("Tmdb.java")

    def test_tiles_come_from_the_genre_list_with_counts(self):
        show = method(self.explore, "private void showExplorer(")
        self.assertIn('"/genre/" + kind + "/list"', show)
        self.assertIn("total_results", show)
        self.assertIn("catPath(kind, c.id", show)
        self.assertIn("--left[0]", show)   # a countdown: nothing waits inside the pool

    def test_category_query_and_sorts(self):
        path = method(self.explore, "private String catPath(")
        for part in ["popularity.desc", "primary_release_date", "first_air_date", ".lte=", "vote_average.desc",
                     "vote_count.gte", "original_title", "original_name", ".asc", "servicesParam()", "with_genres"]:
            self.assertIn(part, path)

    def test_by_category_tab_on_movies_and_shows_only(self):
        catalog = method(self.explore, "private void buildCatalog(")
        self.assertEqual(catalog.count("Tab.explorer("), 2)
        movies = catalog[catalog.index("case MOVIES:"):catalog.index("case SHOWS:")]
        shows = catalog[catalog.index("case SHOWS:"):catalog.index("case KIDS:")]
        self.assertIn("Tab.explorer(", movies)
        self.assertIn("Tab.explorer(", shows)
        for kept in ["/trending/movie/week", "/tv/on_the_air", '"nacionais"']:
            self.assertIn(kept, catalog)
        self.assertIn("showExplorer(t.explore)", method(self.explore, "private void showCatalogTab("))

    def test_category_page_is_a_feed_over_a_grid(self):
        page = method(self.explore, "private void showCategoryGrid(")
        self.assertIn("startFeed(", page)
        self.assertIn("new TvUi.Grid(", page)
        self.assertIn("total_results", page)
        self.assertIn("openCategoryFilter", method(self.explore, "private void openCategory("))
        self.assertIn("pendingFilter", method(self.explore, "private void buildFilterPage("))

    def test_grid_shares_the_width_of_its_line(self):
        self.assertIn("static final class Columns", self.tvui)
        self.assertIn("static final class Ratio", self.tvui)
        self.assertIn("static final class Chip", self.tvui)
        self.assertIn("setLines(2)", self.tvui)
        self.assertIn("new Columns(", method(self.tvui, "void add(View cell)", "        "))
        self.assertRegex(method(self.explore, "private View ranked("), r"grid\.stretch = false;")
        self.assertIn("getMaxLines() == 1", method(self.tvui, "static void focusable(View v, float k, float radius, TextView caption)"))

    def test_posters_of_the_tiles_are_small_and_lazy(self):
        self.assertIn('POSTER_SMALL = "https://image.tmdb.org/t/p/w154"', self.tmdb)
        self.assertIn("Tmdb.POSTER_SMALL", method(self.explore, "private void loadTiles("))
        self.assertIn("tilesNear(now)", self.explore)

    def test_recents_are_kept_and_genres_cached_longer(self):
        self.assertIn("static List<Title> opened(", self.tmdb)
        self.assertIn("noteOpened", method(self.explore, "private void open(Tmdb.Title t)"))
        self.assertIn('path.startsWith("/genre/")', method(self.tmdb, "private static long ttl("))

    def test_keys_stay_in_their_line_and_back_leaves_the_category(self):
        keys = method(self.explore, "public boolean dispatchKeyEvent(")
        self.assertIn("inside(next, page)", keys)
        self.assertIn("sameLine(", keys)
        self.assertIn("backToTiles()", keys)
        self.assertIn("catReturn", keys)

    def test_strings_in_every_language(self):
        for folder in ["values", "values-pt", "values-uk"]:
            text = (APP / "res" / folder / "strings.xml").read_text(encoding="utf-8")
            for key in NEW_STRINGS:
                self.assertEqual(len(re.findall(r'<string name="%s">' % key, text)), 1, (folder, key))
            for key in NEW_PLURALS:
                self.assertEqual(len(re.findall(r'<plurals name="%s">' % key, text)), 1, (folder, key))
        uk = (APP / "res/values-uk/strings.xml").read_text(encoding="utf-8")
        for key in NEW_PLURALS:
            block = re.search(r'<plurals name="%s">(.*?)</plurals>' % key, uk, re.S).group(1)
            self.assertEqual(re.findall(r'quantity="(\w+)"', block), ["one", "few", "many", "other"])


if __name__ == "__main__":
    unittest.main()
