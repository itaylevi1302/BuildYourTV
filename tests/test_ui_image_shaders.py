"""CleanYourTV UI: a rounded image's shader lives with its image, never in a cache keyed by the bitmap (#38).

A BitmapShader holds its bitmap strongly, so a WeakHashMap<Bitmap, BitmapShader> never lets a key go: every pick
image ever drawn stayed in memory on the LEGACY path. The shaders are made with their images (HomeApps.Item.setTile,
Backdrop) and drawing never makes one.

Run from the repository root: python3 -m unittest discover -s tests -p test_ui_image_shaders.py -v
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "launcher-ui/app/src/io/cleanyourtv/ui"


def read(name):
    return (SRC / name).read_text(encoding="utf-8")


class ImageShaders(unittest.TestCase):
    def test_home_view_keeps_no_shader_cache(self):
        source = read("HomeView.java")
        self.assertNotIn("WeakHashMap", source)
        self.assertNotIn("new BitmapShader", source, "HomeView must not make image shaders while drawing")

    def test_tiles_are_set_with_their_shader(self):
        source = read("HomeApps.java")
        self.assertIn("void setTile(Bitmap b)", source)
        self.assertEqual(re.findall(r"\.tile\s*=[^=]", source), [], "assign a tile with Item.setTile")

    def test_backdrop_makes_its_card_shaders(self):
        source = read("Backdrop.java")
        self.assertIn("wideShader", source)
        self.assertIn("tallShader", source)


if __name__ == "__main__":
    unittest.main()
