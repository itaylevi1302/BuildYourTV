"""CleanYourTV UI: Continue Watching's https pictures go only through NetPicture's checks (#66).

From Free My TV PR 506: an address another app left in Android TV's Watch Next list is fetched over https only, never
from a host on the TV or the home network (checked after the name is looked up, and again at each redirect), only when
the answer is a picture, with a size and a time limit. tests/net_picture/NetPictureTest.java runs NetPicture on a plain
JVM (no Android, no network: every refused address is refused before a connection); the rest reads the sources.

Run from the repository root: python3 -m unittest discover -s tests -p test_ui_net_picture.py -v
"""
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "launcher-ui/app/src/io/cleanyourtv/ui"
TEST = ROOT / "tests/net_picture/NetPictureTest.java"


def jdk_tool(name):
    home = os.environ.get("JAVA_HOME")
    if home and (Path(home) / "bin" / name).exists():
        return str(Path(home) / "bin" / name)
    return shutil.which(name)


def read(name):
    return (SRC / name).read_text(encoding="utf-8")


class NetPictureOnAJvm(unittest.TestCase):
    def test_checks(self):
        javac, java = jdk_tool("javac"), jdk_tool("java")
        if not javac or not java or subprocess.run([java, "-version"], capture_output=True).returncode != 0:
            self.skipTest("needs a JDK (JAVA_HOME, or javac and java on PATH)")
        with tempfile.TemporaryDirectory() as out:
            subprocess.run([javac, "-nowarn", "-encoding", "UTF-8", "-d", out, str(SRC / "NetPicture.java"), str(TEST)],
                           check=True, capture_output=True, timeout=120)
            run = subprocess.run([java, "-cp", out, "io.cleanyourtv.ui.NetPictureTest"],
                                 capture_output=True, text=True, timeout=120)
        self.assertEqual(run.returncode, 0, run.stdout + run.stderr)
        self.assertIn("assertions passed", run.stdout)


class OnlyThroughNetPicture(unittest.TestCase):
    def test_net_picture_stays_free_of_android(self):
        self.assertNotIn("import android.", read("NetPicture.java"))

    def test_the_checks_are_there(self):
        src = read("NetPicture.java")
        for check in ('throw new IOException("not https")', 'throw new IOException("not a public host")',
                      "c.setInstanceFollowRedirects(false);", "c.setUseCaches(false);", '.startsWith("image/")',
                      "static final int MAX_BYTES = 4 << 20;", "TOTAL_MS = 10_000"):
            self.assertIn(check, src)

    def test_the_peer_actually_connected_to_is_checked(self):
        src = read("NetPicture.java")
        for check in ("Proxy.NO_PROXY", "setSSLSocketFactory(", "class PublicOnly", "getInetAddress()"):
            self.assertIn(check, src)

    def test_https_pictures_are_fetched_by_net_picture_alone(self):
        art = read("WatchArt.java")
        self.assertIn('if ("https".equalsIgnoreCase(uri.getScheme())) return NetPicture.get(uri.toString());', art)
        self.assertIn('(uri != null && "https".equalsIgnoreCase(uri.getScheme()) ? NET : IO).execute(', art)
        self.assertNotIn("openConnection", art)
        self.assertNotIn("Images.", art)   # never Explore's disk cache: what someone watches stays off the disk
        picture = read("WatchNext.java")
        picture = picture[picture.index("    static Uri picture(String s) {"):]
        picture = picture[:picture.index("\n    }\n")]
        self.assertIn('"https".equalsIgnoreCase(scheme)', picture)
        self.assertNotIn('"http".equalsIgnoreCase', picture)
        self.assertNotIn('"file"', picture)

    def test_documented(self):
        readme = (ROOT / "launcher-ui/README.md").read_text(encoding="utf-8")
        self.assertIn("never an address on your home network", readme)
        self.assertIn("Watch Next", (ROOT / "README.md").read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
