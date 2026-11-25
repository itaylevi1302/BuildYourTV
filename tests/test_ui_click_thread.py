"""CleanYourTV UI: the click sound plays on a thread of its own, not on the UI thread (#42).

SoundPool.play is a call to the audio server (5 to 16 ms on MediaTek boards); on the UI thread it delayed the first
frame of every focus move. Click.java, from Free My TV (b27449a, adf8b0f), posts play and release to a HandlerThread.
This reads the source: move() and open() only post, release() posts the pool's release and quits the thread.

Run from the repository root: python3 -m unittest discover -s tests -p test_ui_click_thread.py -v
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CLICK = ROOT / "launcher-ui/app/src/io/cleanyourtv/ui/Click.java"


def source():
    return CLICK.read_text(encoding="utf-8")


def body(text, signature):
    start = text.index(signature)
    return text[start:text.index("\n    }\n", start)]


class ClickThreadTest(unittest.TestCase):
    def test_has_its_own_handler_thread(self):
        text = source()
        self.assertIn('new HandlerThread("click"', text)
        self.assertIn("new Handler(thread.getLooper())", text)
        self.assertRegex(text, r"private volatile SoundPool pool;")

    def test_thread_started_before_pool_so_failure_path_can_release(self):
        ctor = body(source(), "    Click(Context c) {")
        self.assertLess(ctor.index("thread.start();"), ctor.index("try {"))
        self.assertLess(ctor.index("handler = new Handler"), ctor.index("try {"))

    def test_move_and_open_play_through_the_handler(self):
        text = source()
        for name in ("move", "open"):
            b = body(text, "    void %s() {" % name)
            self.assertIn("handler.post(", b, name)
            post = b.index("handler.post(")
            plays = [m.start() for m in re.finditer(r"\.play\(", b)]
            self.assertTrue(plays, name)
            self.assertTrue(all(p > post for p in plays), "%s plays on the caller's thread" % name)
            self.assertNotIn("pool.play(", b, "%s must play from a local copy of the volatile pool" % name)

    def test_release_runs_on_the_handler_and_quits_the_thread(self):
        b = body(source(), "    void release() {")
        self.assertIn("pool = null;", b)
        self.assertIn("handler.post(p::release)", b)
        self.assertNotRegex(b, r"(?<!::)\bp\.release\(\)|pool\.release\(\)")
        self.assertIn("thread.quitSafely();", b)
        self.assertLess(b.index("handler.post(p::release)"), b.index("thread.quitSafely();"))

    def test_only_one_play_call_per_method_and_none_outside(self):
        text = source()
        self.assertEqual(len(re.findall(r"\.play\(", text)), 2)


if __name__ == "__main__":
    unittest.main()
