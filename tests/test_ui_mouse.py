"""CleanYourTV UI: a mouse or an air mouse moves the focus, clicks and scrolls on the home screen (#69).

From Free My TV PR 371 (merge 877e9f8, with eb649bb): hovering moves the focus to the item under the pointer (the
shelf, the drawer, the top buttons and tabs, the cards, All Apps), a click opens it like OK, the wheel moves along the
focused row (a row at a time in All Apps). No permission; the remote's keys are untouched. This reads the source.

Run from the repository root: python3 -m unittest discover -s tests -p test_ui_mouse.py -v
"""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "launcher-ui/app/src/io/cleanyourtv/ui"
MANIFEST = ROOT / "launcher-ui/app/AndroidManifest.xml"


def method(text, signature):
    start = text.index(signature)
    return text[start:text.index("\n    }\n", start)]


class MouseTest(unittest.TestCase):
    def setUp(self):
        self.view = (SRC / "HomeView.java").read_text(encoding="utf-8")

    def test_imports(self):
        self.assertIn("import android.view.MotionEvent;", self.view)
        self.assertIn("import android.view.InputDevice;", self.view)

    def test_click_opens_like_ok(self):
        touch = method(self.view, "    @Override public boolean onTouchEvent(MotionEvent e) {")
        self.assertIn("MotionEvent.BUTTON_SECONDARY | MotionEvent.BUTTON_TERTIARY", touch)
        self.assertIn("< 24 * k) pointAt(e.getX(), e.getY(), true);", touch)
        self.assertNotIn("setClickable(true)", self.view)

    def test_hover_moves_the_focus_but_not_on_jitter_nor_for_talkback(self):
        hover = method(self.view, "    @Override public boolean onHoverEvent(MotionEvent e) {")
        self.assertLess(hover.index("isTouchExplorationEnabled()) return super.onHoverEvent(e);"),
                        hover.index("pointAt(x, y, false);"))
        self.assertIn("< 6 * k) return true;", hover)
        self.assertIn("MotionEvent.ACTION_HOVER_EXIT", hover)

    def test_wheel(self):
        generic = method(self.view, "    @Override public boolean onGenericMotionEvent(MotionEvent e) {")
        self.assertIn("MotionEvent.ACTION_SCROLL", generic)
        self.assertIn("InputDevice.SOURCE_CLASS_POINTER", generic)
        self.assertIn("MotionEvent.AXIS_VSCROLL", generic)
        self.assertIn("return super.onGenericMotionEvent(e);", generic)
        wheel = method(self.view, "    private void wheel(int dir) {")
        self.assertIn("moveGrid(0, dir);", wheel)
        self.assertIn("move(Ui.he ? -dir : dir, 0);", wheel)
        self.assertIn("if (reordering) return;", wheel)

    def test_hit_order_and_layer_moves(self):
        point = method(self.view, "    private void pointAt(float x, float y, boolean open) {")
        self.assertIn("if (reordering) return;", point)
        order = ["if (sheetOpen)", "gridAt(x, y)", "if (drawerOpen)",
                 "hitIndex(drawerRects, x, y - drawerLayer.getTranslationY())",
                 "hitIndex(topRects, x, top)", "hitIndex(watchRects, x, top)", "hitIndex(cardRects, x, top)",
                 "hitIndex(tileRects, x, y - tiles.getTranslationY())"]
        at = [point.index(s) for s in order]
        self.assertEqual(at, sorted(at), "sheet, drawer, top, cards, then the shelf")
        self.assertIn("float top = y - heroLayer.getTranslationY();", point)
        self.assertIn("hit >= TABS.length || picksOn()", point, "the tabs are only hit with the picks")
        self.assertIn("if (hit < TABS.length) selectTab(hit);", point)
        self.assertEqual(point.count("if (open) activate();"), 6)

    def test_sheet_hit_only_on_rows_in_view(self):
        grid = method(self.view, "    private int gridAt(float x, float y) {")
        self.assertIn("Math.round(scroll.target)", grid)
        self.assertIn("(first + VISIBLE_ROWS) * COLUMNS", grid)

    def test_keys_untouched(self):
        on_key = method(self.view, "    boolean onKey(KeyEvent e) {")
        for s in ("pointAt", "hover", "wheel", "MotionEvent"):
            self.assertNotIn(s, on_key)

    def test_no_new_permission(self):
        manifest = MANIFEST.read_text(encoding="utf-8")
        self.assertNotIn("BLUETOOTH", manifest)
        self.assertNotIn("android.hardware.type.pc", manifest)


if __name__ == "__main__":
    unittest.main()
