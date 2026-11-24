"""CleanYourTV UI: crisp rounded images without clipPath, symbols pre-scaled, whole pixels at rest (#47).

As in Free My TV (e9ffe2b, a990e02, 6901adc, 76b8b66): a clipPath isn't anti-aliased on some GPUs (stair-stepped
corners on a MediaTek TV), so every version fills an anti-aliased rounded rectangle with the image as its shader (the
image still covers the cell, cropped, never stretched), and the shaders exist on every version. Symbols are scaled
ahead of time at layout and drawn 1:1; drawing never makes a bitmap. Rounded shapes sit on whole pixels at rest.

Run from the repository root: python3 -m unittest discover -s tests -p test_ui_crisp_images.py -v
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "launcher-ui/app/src/io/cleanyourtv/ui"


def read(name):
    return (SRC / name).read_text(encoding="utf-8")


def block(text, head):
    """The text from `head` to its matching closing brace."""
    start = text.index(head)
    depth = 0
    for i in range(text.index("{", start), len(text)):
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                return text[start:i + 1]
    raise AssertionError("unbalanced braces after " + head)


class CrispImages(unittest.TestCase):
    def test_no_clip_path_for_rounded_images(self):
        for name in ("HomeView.java", "HomeApps.java"):
            self.assertNotIn(".clipPath(", read(name), name)

    def test_round_image_covers_with_its_shader(self):
        body = block(read("HomeView.java"), "private void roundImage(")
        self.assertIn("Math.max(cell.width() / image.getWidth(), cell.height() / image.getHeight())", body,
                      "roundImage must keep cover (centre-crop), not stretch")
        self.assertIn("imageFill.setShader(shader)", body)
        self.assertNotIn("LEGACY", body)

    def test_shaders_exist_on_every_version(self):
        self.assertNotRegex(read("HomeApps.java"), r"tileShader\s*=.*LEGACY")
        self.assertNotRegex(read("Backdrop.java"), r"(wide|tall)Shader\s*=.*LEGACY")

    def test_symbols_scaled_ahead_never_while_drawing(self):
        source = read("Symbols.java")
        self.assertIn("static void warm(Context c, String name, float pointSize)", source)
        draw = block(source, "private static void drawLocked(")
        self.assertNotIn("createScaledBitmap", draw)
        self.assertNotIn("createBitmap", draw)
        for name in ("HomeView.java", "HomeApps.java", "DreamView.java", "SettingsActivity.java"):
            self.assertRegex(read(name), r"Symbols\.warm(Weather)?\(", name)

    def test_rounded_shapes_on_whole_pixels_at_rest(self):
        source = read("HomeView.java")
        self.assertIn("private static void snap(RectF r)", source)
        self.assertIn("snap(tileRects[i])", block(source, "private void layoutShelf("))
        self.assertIn("snap(platterRect)", block(source, "private void layoutShelf("))
        self.assertIn("snap(drawerRects[i])", block(source, "private void layoutDrawer("))
        light = block(source, "private void layoutLight(")
        self.assertIn("snap(topRects[i])", light)
        self.assertRegex(light, r"for \(RectF r : cardRects\) snap\(r\)")


if __name__ == "__main__":
    unittest.main()
