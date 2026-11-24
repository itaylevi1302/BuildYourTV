"""CleanYourTV UI: a sleep timer through the key service, with no new permission (#68).

Free My TV 80902d0 (and its follow-ups up to f2d984c) turns the TV off with the remote's Sleep key through the TV's own
ADB, shows the minutes left over other apps and keeps an exact alarm: SYSTEM_ALERT_WINDOW, FOREGROUND_SERVICE and
USE_EXACT_ALARM. Ours: RemoteKeyService's "lock screen" global action puts the TV to sleep; two inexact alarms land in
SleepReceiver in the :keys process; the last minute is SleepWarningActivity, our own window, started from that process;
Settings > Sleep Timer offers Off, 15, 30, 60, 90 and 120 minutes, and says why when the key service is off. This reads
the source.

Run from the repository root: python3 -m unittest discover -s tests -p test_ui_sleep_timer.py -v
"""
import re
import unittest
from pathlib import Path
from xml.etree import ElementTree

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "launcher-ui/app"
SRC = APP / "src/io/cleanyourtv/ui"
MANIFEST = APP / "AndroidManifest.xml"
ANDROID = "{http://schemas.android.com/apk/res/android}"
NAMES = ("sleep_timer", "sleep_minutes", "sleep_left", "sleep_unavailable", "sleep_unavailable_why",
         "sleep_warning", "sleep_more", "sleep_keep_on", "sleep_now")


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


def component(tag, name):
    app = ElementTree.parse(MANIFEST).getroot().find("application")
    for el in app.findall(tag):
        if el.get(ANDROID + "name") == name:
            return el
    raise AssertionError(f"no <{tag}> {name} in the manifest")


class SleepTimerTest(unittest.TestCase):
    def setUp(self):
        self.timer = read("SleepTimer.java")

    def test_no_new_permission(self):
        perms = set(re.findall(r'<uses-permission android:name="([^"]+)"', MANIFEST.read_text(encoding="utf-8")))
        for name in ("SYSTEM_ALERT_WINDOW", "FOREGROUND_SERVICE", "USE_EXACT_ALARM", "SCHEDULE_EXACT_ALARM",
                     "WAKE_LOCK", "RECEIVE_BOOT_COMPLETED", "BIND_DEVICE_ADMIN"):
            self.assertNotIn("android.permission." + name, perms)

    def test_alarms_and_warning_live_with_the_key_service(self):
        for tag, name in (("service", ".RemoteKeyService"), ("receiver", ".SleepReceiver"),
                          ("activity", ".SleepWarningActivity")):
            with self.subTest(name=name):
                self.assertEqual(component(tag, name).get(ANDROID + "process"), ":keys")
        receiver = component("receiver", ".SleepReceiver")
        self.assertEqual(receiver.get(ANDROID + "exported"), "false")
        self.assertIsNone(receiver.get(ANDROID + "permission"))
        self.assertEqual(receiver.findall("intent-filter"), [], "reached by name only")
        self.assertEqual(component("activity", ".SleepWarningActivity").get(ANDROID + "exported"), "false")
        self.assertNotIn("SLEEP_TEST", self.timer + read("SleepReceiver.java"))

    def test_sleep_is_the_lock_screen_action(self):
        service = read("RemoteKeyService.java")
        self.assertIn("performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)", block(service, "static boolean sleep()"))
        self.assertIn("Intent.ACTION_SCREEN_OFF", service)
        self.assertIn("SleepTimer.cancel(", block(service, "private final BroadcastReceiver screenOff"))
        self.assertIn("unregisterReceiver(screenOff)", block(service, "public void onDestroy()"))
        self.assertNotIn("KEYCODE_SLEEP", self.timer)

    def test_inexact_alarms_without_permission(self):
        self.assertNotRegex(self.timer, r"setExact|setAlarmClock|_WAKEUP")
        self.assertEqual(self.timer.count("am.setWindow(AlarmManager.ELAPSED_REALTIME,"), 2)
        self.assertIn("PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE", self.timer)

    def test_choices(self):
        self.assertIn("static final int[] CHOICES = {0, 15, 30, 60, 90, 120};", self.timer)

    def test_running_only_while_its_alarm_is_pending(self):
        left = block(self.timer, "static int minutesLeft(Context c)")
        self.assertIn("PendingIntent.FLAG_NO_CREATE", left)
        self.assertIn("SystemClock.elapsedRealtime()", left)

    def test_a_late_or_dark_alarm_does_nothing(self):
        due = block(self.timer, "static boolean due(Context c, long end)")
        self.assertIn("end != end(c)", due)
        self.assertIn("STALE_MS", due)
        self.assertIn("isInteractive()", due)
        on = block(read("SleepReceiver.java"), "public void onReceive(")
        self.assertLess(on.index("SleepTimer.due(c, end)"), on.index("SleepTimer.sleepNow(c)"))
        self.assertLess(on.index("RemoteKeyService.running()"), on.index("SleepTimer.warn(c, end)"))

    def test_settings_row_says_why_without_the_key_service(self):
        settings = read("SettingsActivity.java")
        self.assertIn("Strings.get(R.string.sleep_timer), Strings.get(R.string.tv_settings)}", settings)
        activate = block(settings, "private void activate()")
        row = activate[activate.index("focus == MAIN_SLEEP"):]
        self.assertLess(row.index("SleepTimer.available(c)"), row.index("openPicker(PICK_SLEEP)"))
        self.assertLess(row.index("R.string.sleep_unavailable_why"), row.index("openPicker(PICK_SLEEP)"))
        picker = block(settings, "private void openPicker(int which)")
        self.assertLess(picker.index("which == PICK_SLEEP"), picker.index("RemoteKeys.buttons("))
        draw = block(settings, "private void drawPicker(Canvas c)")
        self.assertLess(draw.index("pickFor == PICK_SLEEP"), draw.index("RemoteKeys.buttons("))

    def test_settings_poll_reads_the_timer_off_the_ui_thread(self):
        poll = block(read("SettingsActivity.java"), "private final Runnable poll = new Runnable()")
        io = block(poll, "IO.execute(")
        self.assertIn("SleepTimer.minutesLeft(c)", io)
        self.assertIn("SleepTimer.available(c)", io)

    def test_strings_in_every_language(self):
        for path in [APP / "res/values/strings.xml", *sorted((APP / "res").glob("values-*/strings.xml"))]:
            have = {el.get("name") for el in ElementTree.parse(path).getroot() if el.tag == "string"}
            with self.subTest(locale=path.parent.name):
                self.assertEqual([n for n in NAMES if n not in have], [])
        english = (APP / "res/values/strings.xml").read_text(encoding="utf-8")
        self.assertIn("lets the sleep timer turn the TV off", english)

    def test_documented(self):
        readme = (ROOT / "launcher-ui/README.md").read_text(encoding="utf-8")
        section = readme[readme.index("## Sleep timer"):readme.index("## Translations")]
        self.assertIn("KEY_SERVICE=1", section)
        self.assertNotIn("SLEEP_TEST", section)


if __name__ == "__main__":
    unittest.main()
