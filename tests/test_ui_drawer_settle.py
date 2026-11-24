"""CleanYourTV UI: no focus animation under the sliding drawer (#44).

While the drawer slides, redrawing the drawer's cards or the hero for a focus animation took 35 to 45 ms of GPU a frame
on a Realtek TCL. HomeView.settleWhileSliding (from Free My TV 611437e) snaps the drawer, card, top-button, capsule and
hero focus to their targets while the drawer moves; the shelf's focus still animates. This reads HomeView.java and
checks that doFrame calls it before any spring steps, and what it settles.

Run from the repository root: python3 -m unittest discover -s tests -p test_ui_drawer_settle.py -v
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


class DrawerSettleTest(unittest.TestCase):
    def test_do_frame_settles_before_stepping(self):
        frame = body(source(), "@Override public void doFrame(long frameTimeNanos) {")
        self.assertIn("settleWhileSliding();", frame)
        call = frame.index("settleWhileSliding();")
        for step in ("step(tileFocus, dt)", "heroFocus.step(dt)", "step(topFocus, dt)", "step(cardFocus, dt)",
                     "step(drawerFocus, dt)", "step(statusFocus, dt)", "drawer.step(dt)"):
            self.assertIn(step, frame)
            self.assertLess(call, frame.index(step), step + " runs before settleWhileSliding()")

    def test_settles_only_while_the_drawer_moves(self):
        method = body(source(), "    private void settleWhileSliding() {")
        lines = [l.strip() for l in method.splitlines()[1:] if l.strip()]
        self.assertEqual(lines[0], "if (drawer.settled()) return;")
        for springs in ("drawerFocus", "cardFocus", "topFocus", "statusFocus"):
            self.assertIn("settle(%s)" % springs, method)
        self.assertIn("heroFocus.snap(heroFocus.target);", method)
        self.assertIn("drawerDirty = true", method)
        self.assertIn("heroDirty", method)
        self.assertIn("statusDirty", method)

    def test_shelf_and_sheet_still_animate(self):
        method = body(source(), "    private void settleWhileSliding() {")
        for springs in ("tileFocus", "tileSlide", "gridFocus"):
            self.assertNotIn(springs, method)

    def test_settle_snaps_to_target(self):
        helper = body(source(), "    private static boolean settle(Spring[] springs) {")
        self.assertIn("s.snap(s.target);", helper)
        self.assertIn("if (s.settled()) continue;", helper)


if __name__ == "__main__":
    unittest.main()
