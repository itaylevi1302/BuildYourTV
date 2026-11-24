"""CleanYourTV UI: Settings draw only while something moves (#43).

The settings screen used to re-post a frame callback forever and redraw the aurora every 100 ms, and its 2 s poll
redrew unconditionally while reading recs-status.json on the UI thread. As in Free My TV (e6ab00b, c9b5136),
doFrame no longer re-posts itself: onDraw requests one frame while a focus spring moves, the aurora keeps its own
clock (skySeconds) so it rests with the screen, and the poll reads the file off the UI thread and redraws only when
what it shows changed. This checks those shapes in the source.

Run from the repository root: python3 -m unittest discover -s tests -p test_ui_settings_at_rest.py -v
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SETTINGS = ROOT / "launcher-ui/app/src/io/cleanyourtv/ui/SettingsActivity.java"


def source():
    return SETTINGS.read_text(encoding="utf-8")


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


class SettingsAtRest(unittest.TestCase):
    def test_do_frame_does_not_repost_itself(self):
        body = block(source(), "public void doFrame(long frameTimeNanos)")
        self.assertNotIn("postFrameCallback", body)
        self.assertIn("framePending = false", body)

    def test_frames_requested_only_while_moving(self):
        text = source()
        self.assertEqual(text.count("postFrameCallback("), 1, "one frame request, in onDraw")
        on_draw = block(text, "protected void onDraw(Canvas c)")
        self.assertRegex(on_draw, r"if \(running && moving && !framePending\) \{\s*framePending = true;\s*"
                                  r"Choreographer\.getInstance\(\)\.postFrameCallback\(this\);")
        self.assertIn("moving |= pop[i].step(dt)", on_draw)

    def test_pause_clears_pending_frame(self):
        body = block(source(), "void pause()")
        self.assertIn("removeFrameCallback(this)", body)
        self.assertIn("framePending = false", body)

    def test_aurora_keeps_its_own_clock(self):
        on_draw = block(source(), "protected void onDraw(Canvas c)")
        self.assertIn("skySeconds += dt", on_draw)
        self.assertIn("scene.advance(skySeconds)", on_draw)
        self.assertNotIn("startedAt", source())

    def test_poll_reads_off_ui_thread_and_redraws_on_change(self):
        text = source()
        poll = block(text, "private final Runnable poll = new Runnable()")
        self.assertNotIn("invalidate()", poll)
        io = block(poll, "IO.execute(")
        self.assertIn("Recs.status(", io)
        self.assertEqual(len(re.findall(r"Recs\.status\(", poll)), 1)
        show = block(text, "private void showRecs(JSONObject next)")
        self.assertRegex(show, r"if \(stamp\.equals\(pollStamp\)\) return;")
        self.assertIn("invalidate()", show)


if __name__ == "__main__":
    unittest.main()
