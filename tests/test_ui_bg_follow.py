"""CleanYourTV UI: the background follows the focused pick (#58).

Free My TV 00dec57 (PR 271) and 77e6e80: once the focus rests 500 ms on a pick card, HomeView fades the background to
that pick's backdrop, which updateCards already built, so a quick run along the cards fades once and nothing new is
decoded. This reads HomeView.java and checks the delay, that retarget cancels before it posts and posts only on the
cards, that bgFollow skips a missing or already-shown backdrop, that pause cancels it, that updateCards uses the
focused card, and that a page turn sets the index before updateCards runs.

Run from the repository root: python3 -m unittest discover -s tests -p test_ui_bg_follow.py -v
"""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
HOME_VIEW = ROOT / "launcher-ui/app/src/io/cleanyourtv/ui/HomeView.java"


def source():
    return HOME_VIEW.read_text(encoding="utf-8")


def body(text, signature):
    start = text.index(signature)
    return text[start:text.index("\n    }\n", start)]


class BgFollowTest(unittest.TestCase):
    def test_delay_is_500_ms(self):
        self.assertRegex(source(), r"private static final long BG_FOLLOW_MS = 500;")

    def test_retarget_cancels_then_posts_only_on_the_cards(self):
        method = body(source(), "    private void retarget(boolean snap) {")
        cancel = method.index("handler.removeCallbacks(bgFollow);")
        post = method.index("handler.postDelayed(bgFollow, BG_FOLLOW_MS);")
        self.assertLess(cancel, post)
        line = method[method.rindex("\n", 0, post):post]
        self.assertIn("zone == Z_CARDS", line)
        self.assertIn("!sheetOpen", line)

    def test_runnable_reuses_the_cached_backdrop(self):
        text = source()
        start = text.index("private final Runnable bgFollow = new Runnable() {")
        run = text[start:text.index("\n    };\n", start)]
        self.assertIn("zone != Z_CARDS", run)
        self.assertIn("drawerOpen", run)
        self.assertIn("recs.backdrop(cards.get(Math.min(cardIndex, cards.size() - 1)))", run)
        self.assertIn("b == null || b == bgTo", run)
        self.assertIn("showBackdrop(b);", run)
        self.assertIn("wake();", run)
        self.assertNotIn("BitmapFactory", run)
        self.assertNotIn("Backdrop.make", run)

    def test_pause_cancels(self):
        self.assertIn("handler.removeCallbacks(bgFollow);", body(source(), "    void pause() {"))

    def test_update_cards_uses_the_focused_card(self):
        method = body(source(), "    private void updateCards() {")
        self.assertIn("recs.backdrop(cards.get(Math.min(cardIndex, cards.size() - 1)))", method)
        self.assertNotIn("recs.backdrop(cards.get(0))", method)

    def test_page_turn_sets_the_index_first(self):
        method = body(source(), "    private boolean moveLight(int dx, int dy) {")
        self.assertLess(method.index("cardIndex = 0;"), method.index("turnPage(1);"))
        self.assertLess(method.index("cardIndex = 2;"), method.index("turnPage(-1);"))

    def test_home_resets_to_the_first_pick(self):
        method = body(source(), "    void reset(boolean animated) {")
        self.assertIn("cardIndex = 0;", method)
        self.assertNotIn("else if (page != 0)", method)


if __name__ == "__main__":
    unittest.main()
