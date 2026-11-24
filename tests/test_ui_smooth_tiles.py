"""CleanYourTV UI: tiles and logos shrink in steps of at most 2x, and the big gradients are dithered (#48).

Software Canvas has no mipmaps and bilinear filtering reads 2x2 pixels, so one big shrink leaves jagged edges on 4K
TVs. The background gradients and the night shadows showed bands. Neither fix may keep extra memory: the big tile
bitmap is recycled, the 2w x 2h step is capped, and the dithered gradient is a 4 px wide strip.

Run from the repository root: python3 -m unittest discover -s tests -p test_ui_smooth_tiles.py -v
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "launcher-ui/app/src/io/cleanyourtv/ui"


def read(name):
    return (SRC / name).read_text(encoding="utf-8")


class SmoothTiles(unittest.TestCase):
    def test_one_shared_downscale(self):
        self.assertIn("static Bitmap downscale(Bitmap src, int w, int h)", read("Ui.java"))
        home = read("HomeView.java")
        self.assertNotIn("private static Bitmap downscale(", home)
        self.assertIn("Ui.downscale(", home)

    def test_smooth_step_is_capped(self):
        m = re.search(r"SMOOTH_STEP_PIXELS = ([\d_]+);", read("Ui.java"))
        self.assertIsNotNone(m)
        self.assertLessEqual(int(m.group(1).replace("_", "")), 1_000_000)

    def test_banner_tile_drawn_big_then_shrunk(self):
        source = read("HomeApps.java")
        body = source[source.index("private static Bitmap bannerTile("):source.index("private static Bitmap iconTile(")]
        self.assertIn("detail(d, TILE_W, TILE_H)", body)
        self.assertIn("return shrink(b, TILE_W, TILE_H);", body)
        self.assertIn("Math.min(2f, k)", source, "never more than 2x the tile")
        self.assertIn("big.recycle()", source)

    def test_logo_tile_shrinks_the_logo_first(self):
        source = read("HomeApps.java")
        body = source[source.index("private static Bitmap logoTile("):source.index("private static Bitmap darkTile(")]
        self.assertIn("Ui.downscale(logo", body)
        self.assertIn("small.recycle()", body)

    def test_background_gradients_dithered(self):
        self.assertIn("static Shader gradient(int height, int top, int bottom)", read("Ui.java"))
        self.assertIn("Ui.gradient(h, 0xFFF5F5F7, 0xFFE2E2E7)", read("HomeView.java"))
        self.assertIn("Ui.gradient(h, 0xFF1C1C1E, 0xFF0B0B0D)", read("HomeView.java"))
        self.assertIn("Ui.gradient(h, palette.color(1), palette.color(0))", read("Scene.java"))

    def test_night_shadows_dithered(self):
        shadows = read("Shadows.java")
        self.assertIn("boolean dither;", shadows)
        self.assertIn("Ui.BAYER", shadows)
        self.assertIn("tint.setFilterBitmap(!dither)", shadows)
        self.assertIn("shadows.dither = dark() > 0.5f;", read("HomeView.java"))


if __name__ == "__main__":
    unittest.main()
