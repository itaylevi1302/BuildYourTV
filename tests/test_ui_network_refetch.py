"""CleanYourTV UI: picks and weather fetch again as soon as the network comes up (#45).

A fetch made at boot, before the Wi-Fi is up, fails and then waits out its back-off (10 minutes for the picks, 2 for
the weather): the home screen stayed empty after a reboot. Recs and HomeData, from Free My TV (b6e691f, 7ece5ea),
register a default-network callback that clears the failure time and fetches again, only while a screen listens.
This reads the source.

Run from the repository root: python3 -m unittest discover -s tests -p test_ui_network_refetch.py -v
"""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "launcher-ui/app/src/io/cleanyourtv/ui"
MANIFEST = ROOT / "launcher-ui/app/AndroidManifest.xml"


def callback(name):
    text = (SRC / name).read_text(encoding="utf-8")
    start = text.index("registerDefaultNetworkCallback(")
    end = text.index("catch (RuntimeException e)", start)
    return text, text[text.rindex("try {", 0, start):end]


class NetworkRefetchTest(unittest.TestCase):
    def test_manifest_has_network_state(self):
        self.assertIn("android.permission.ACCESS_NETWORK_STATE", MANIFEST.read_text(encoding="utf-8"))

    def check(self, name, stamps):
        text, cb = callback(name)
        self.assertIn("import android.net.ConnectivityManager;", text)
        self.assertIn("import android.net.Network;", text)
        self.assertIn("public void onAvailable(Network network)", cb)
        for s in stamps:
            self.assertIn("%s = 0;" % s, cb, name)
        post = cb.index("main.postDelayed(")
        guard = cb.index("if (listeners.isEmpty()) return;")
        refresh = cb.index("refreshIfStale()")
        self.assertLess(post, guard, "%s: the listener check must run on the main thread" % name)
        self.assertLess(guard, refresh, name)
        ctor = text[text.index("    private %s(Context c) {" % name[:-5]):]
        self.assertLess(ctor.index("registerDefaultNetworkCallback("), ctor.index("\n    }\n"),
                        "%s: registered in the constructor" % name)

    def test_recs(self):
        self.check("Recs.java", ["failedAt"])

    def test_home_data(self):
        self.check("HomeData.java", ["pricesFailedAt", "weatherFailedAt"])


if __name__ == "__main__":
    unittest.main()
