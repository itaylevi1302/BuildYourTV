"""Now Playing (#67): what apps play, in the quotes' place on the home screen and the screensaver.

It needs a notification listener, which could read every notification, so it is off until build-and-install.sh's
NOW_PLAYING=1 allows it. These checks keep the privacy promises in the source (the listener reads no notification and
unbinds at once; only media-session metadata; nothing logged, stored or sent), the manifest declaration, the screens
watching only while shown, and run the script's NOW_PLAYING block against a fake TV whose `cmd` and `settings` live in a
temporary folder.

Run from the repository root: python3 -m unittest discover -s tests -p test_ui_now_playing.py -v
"""
import os
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path
from xml.etree import ElementTree

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "launcher-ui/app"
SRC = APP / "src/io/cleanyourtv/ui"
SCRIPT = ROOT / "launcher-ui/build-and-install.sh"
NP = "io.cleanyourtv.ui/io.cleanyourtv.ui.NowPlaying"
OTHER = "com.other/com.other.Listener"
A = "{http://schemas.android.com/apk/res/android}"


def read(name):
    return (SRC / name).read_text(encoding="utf-8")


def method(source, signature):
    """The declaration containing `signature` through its closing brace."""
    start = source.index(signature)
    i = source.index("{", start)
    depth = 0
    while True:
        if source[i] == "{":
            depth += 1
        elif source[i] == "}":
            depth -= 1
            if depth == 0:
                return source[start:i + 1]
        i += 1


class NowPlayingSource(unittest.TestCase):
    def test_manifest_declares_the_listener_without_asking_a_permission(self):
        root = ElementTree.parse(APP / "AndroidManifest.xml").getroot()
        services = [s for s in root.iter("service") if s.get(A + "name") == ".NowPlaying"]
        self.assertEqual(len(services), 1)
        s = services[0]
        self.assertEqual(s.get(A + "permission"), "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE")
        self.assertEqual(s.get(A + "exported"), "true")
        self.assertEqual(s.get(A + "process"), ":home")
        self.assertEqual([a.get(A + "name") for a in s.iter("action")],
                         ["android.service.notification.NotificationListenerService"])
        asked = {p.get(A + "name") for p in root.iter("uses-permission")}
        self.assertNotIn("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE", asked)
        self.assertNotIn("android.permission.MEDIA_CONTENT_CONTROL", asked)

    def test_the_listener_reads_no_notification_and_unbinds_at_once(self):
        src = read("NowPlaying.java")
        self.assertIn("public class NowPlaying extends NotificationListenerService", src)
        self.assertIn("requestUnbind();", method(src, "public void onListenerConnected()"))
        for never in ("onNotificationPosted", "onNotificationRemoved", "getActiveNotifications",
                      "getSnoozedNotifications", "StatusBarNotification", "extras"):
            self.assertNotIn(never, src)
        self.assertIn("getActiveSessions(component(", src)

    def test_only_metadata_nothing_logged_stored_or_sent(self):
        src = read("NowPlaying.java")
        for never in ("AppLog", "android.util.Log", "getSharedPreferences", "Prefs.set", "FileOutputStream",
                      "openFileOutput", "URL", "Socket", "Http", "sendBroadcast", "getContentResolver"):
            self.assertNotIn(never, src)

    def test_nothing_is_watched_until_allowed_and_shown(self):
        self.assertIn('sp().getBoolean("nowPlaying", true)', read("Prefs.java"))
        start = method(read("NowPlaying.java"), "void start()")
        self.assertIn("Prefs.nowPlaying()", start)
        self.assertIn("catch (SecurityException", start)

    def test_screens_watch_only_while_shown(self):
        home = read("HomeView.java")
        self.assertIn("playing.start();", method(home, "void resume()"))
        self.assertIn("playing.stop();", method(home, "void pause()"))
        self.assertEqual(home.count("if (HomeData.coinCount() > 0 || playingTab != null) drawQuotes(c);"), 3)   # the clock top, the tabs, Continue Watching
        self.assertIn("R.string.now_playing_announce", home)
        dream = read("DreamView.java")
        self.assertIn("playing.start();", method(dream, "public void start()"))
        self.assertIn("playing.stop();", method(dream, "public void stop()"))

    def test_settings_row(self):
        s = read("SettingsActivity.java")
        self.assertIn("l.add(Strings.get(R.string.now_playing_widget));", s)
        rows = dict(re.findall(r"\b([A-Z_]+_ROWS?) = (\d+)", s))
        self.assertEqual(int(rows["NOW_PLAYING_ROW"]), int(rows["QUOTES_ROW"]) + 1, "right after the quotes row")
        self.assertEqual(int(rows["COIN_ROWS"]), int(rows["NOW_PLAYING_ROW"]) + 1, "the coins stay last")
        self.assertIn("NowPlaying.allowed(getContext())", s)
        self.assertIn("R.string.now_playing_hint_not_allowed", s)
        self.assertGreaterEqual(int(re.search(r"new Spring\[(\d+)\]", s).group(1)), int(rows["COIN_ROWS"]) + 4)   # coins, dollar

    def test_readmes_say_what_it_reads(self):
        doc = (ROOT / "launcher-ui/README.md").read_text(encoding="utf-8")
        self.assertIn("## Now Playing", doc)
        self.assertIn("NOW_PLAYING=1", doc)
        self.assertIn("media session", doc)
        self.assertIn("never reads a notification", doc)
        self.assertIn("NOW_PLAYING=1", (ROOT / "README.md").read_text(encoding="utf-8"))


# The TV side: tv() runs its command in a bash with fake `settings` and `cmd`.
FAKE_TV = r'''
settings() {
  f="$STATE/$3"
  case "$1" in
    get) if [ -f "$f" ]; then cat "$f"; else echo null; fi ;;
    put) printf '%s\n' "$4" > "$f" ;;
  esac
}
cmd() {
  echo "$*" >> "$STATE/cmd.log"
  if [ "$CMD_MODE" = missing ]; then echo "Unknown command: $2"; fi
  return 0
}
'''


def block():
    """The script's NOW_PLAYING block: from `np=$PKG/$PKG.NowPlaying` to the `esac` that closes it."""
    lines = SCRIPT.read_text(encoding="utf-8").splitlines()
    start = lines.index("np=$PKG/$PKG.NowPlaying")
    end = lines.index("esac", start)
    return "\n".join(lines[start:end + 1])


def run(now_playing, cmd_mode="ok", listed=None):
    """Runs the block; returns (stdout, stderr, the cmd calls, enabled_notification_listeners) after it."""
    with tempfile.TemporaryDirectory() as state:
        Path(state, "fake.sh").write_text(FAKE_TV, encoding="utf-8")
        if listed is not None:
            Path(state, "enabled_notification_listeners").write_text(listed + "\n", encoding="utf-8")
        snippet = ("set -euo pipefail\nPKG=io.cleanyourtv.ui\n"
                   "tv() { bash -c '. \"$STATE/fake.sh\"; eval \"$1\"' _ \"$1\"; }\n" + block() + "\n")
        env = dict(os.environ, STATE=state, CMD_MODE=cmd_mode)
        env.pop("NOW_PLAYING", None)
        if now_playing is not None:
            env["NOW_PLAYING"] = now_playing
        p = subprocess.run(["bash", "-c", snippet], capture_output=True, text=True, env=env, check=True)

        def get(name):
            f = Path(state, name)
            return f.read_text(encoding="utf-8").strip() if f.exists() else None
        return p.stdout, p.stderr, get("cmd.log"), get("enabled_notification_listeners")


@unittest.skipUnless(shutil.which("bash"), "needs bash")
class NowPlayingInstallFlag(unittest.TestCase):
    def test_unset_touches_nothing(self):
        self.assertEqual(run(None, listed=NP), ("", "", None, NP))
        self.assertEqual(run(None), ("", "", None, None))

    def test_allow_through_cmd_keeps_the_setting_as_it_was(self):
        out, err, calls, listed = run("1", listed=OTHER)
        self.assertEqual(calls, "notification allow_listener " + NP)
        self.assertEqual(listed, OTHER)
        self.assertIn("now playing allowed", out)
        self.assertEqual(err, "")

    def test_without_cmd_it_is_appended_keeping_the_others(self):
        out, err, calls, listed = run("1", cmd_mode="missing", listed=OTHER)
        self.assertEqual(listed, OTHER + ":" + NP)
        self.assertIn("enabled_notification_listeners", out)

    def test_without_cmd_and_none_listed(self):
        self.assertEqual(run("1", cmd_mode="missing")[3], NP)

    def test_without_cmd_already_listed_stays_once(self):
        self.assertEqual(run("1", cmd_mode="missing", listed=OTHER + ":" + NP)[3], OTHER + ":" + NP)

    def test_zero_takes_it_back_and_keeps_the_others(self):
        for before, after in ((OTHER + ":" + NP, OTHER), (NP + ":" + OTHER, OTHER), (NP, ""),
                              ("a/a.A:" + NP + ":b/b.B", "a/a.A:b/b.B")):
            with self.subTest(before=before):
                out, err, calls, listed = run("0", listed=before)
                self.assertEqual(calls, "notification disallow_listener " + NP)
                self.assertEqual(listed, after)
                self.assertIn("taken back", out)

    def test_zero_leaves_a_list_without_it_alone(self):
        self.assertEqual(run("0", listed=OTHER)[3], OTHER)

    def test_other_values_are_refused(self):
        out, err, calls, listed = run("yes", listed=OTHER)
        self.assertIn("ignored", err)
        self.assertEqual((calls, listed), (None, OTHER))


if __name__ == "__main__":
    unittest.main()
