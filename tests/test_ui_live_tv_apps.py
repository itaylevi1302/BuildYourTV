"""CleanYourTV UI: Live TV opens Sony's and OneWorld's TV apps as they are, before the tuner path (#54).

From Free My TV 3894fc1 (#340) and 820582f (#295): a BRAVIA's first tuner can be DvbOpv1 with an empty channel list, and
KTC's OneWorld TV Center switches tuner mode when one input is tuned. Both packages must be in <queries> on Android 11+,
or launchIntent never sees them.

Run from the repository root: python3 -m unittest discover -s tests -p test_ui_live_tv_apps.py -v
"""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
HOME_APPS = ROOT / "launcher-ui/app/src/io/cleanyourtv/ui/HomeApps.java"
MANIFEST = ROOT / "launcher-ui/app/AndroidManifest.xml"
SONY = "com.sony.dtv.tvx"
ONE_WORLD = "com.mediatek.tv.oneworld.tvcenter"


def live_tv_body():
    text = HOME_APPS.read_text(encoding="utf-8")
    start = text.index("    static Intent liveTv(Context c) {")
    return text[start:text.index("\n    }\n", start)]


class LiveTvApps(unittest.TestCase):
    def test_sony_and_one_world_open_before_the_tuner(self):
        body = live_tv_body()
        sony = body.index('launchIntent(pm, "%s")' % SONY)
        one_world = body.index('launchIntent(pm, "%s")' % ONE_WORLD)
        tuner = body.index("tunerInput(c)")
        self.assertLess(sony, one_world)
        self.assertLess(one_world, tuner)
        self.assertIn("if (sony != null) return sony;", body)
        self.assertIn("if (oneWorld != null) return oneWorld;", body)

    def test_tuner_path_still_pinned(self):
        # d179f941 and c25c1966 stay: MediaTek gets the tuner URI on its explicit activity, others a pinned component.
        body = live_tv_body()
        self.assertIn("TvContract.buildChannelUriForPassthroughInput(tuner)", body)
        self.assertIn("TvContract.buildChannelsUriForInput(tuner)", body)
        self.assertIn("channels.setComponent(component(ri))", body)

    def test_one_world_is_a_live_tv_app(self):
        text = HOME_APPS.read_text(encoding="utf-8")
        start = text.index("static final String[] LIVE_TV_APPS = {")
        decl = text[start:text.index("};", start)]
        self.assertIn('"%s"' % SONY, decl)
        self.assertIn('"%s"' % ONE_WORLD, decl)

    def test_packages_are_queryable(self):
        manifest = MANIFEST.read_text(encoding="utf-8")
        queries = manifest[manifest.index("<queries>"):manifest.index("</queries>")]
        for pkg in (SONY, ONE_WORLD):
            self.assertIn('<package android:name="%s" />' % pkg, queries)


if __name__ == "__main__":
    unittest.main()
