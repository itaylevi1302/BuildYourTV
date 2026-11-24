"""CleanYourTV UI: Wi-Fi/Ethernet and USB drive status icons by the round buttons, off by default (#69).

From Free My TV PR 371 (merge 877e9f8) and eb649bb: the network from a default-network callback on the main thread,
a USB drive as a mounted removable volume queried off the main thread, registered only while the setting is on.
No Bluetooth icon and no new permission (Android 12+ would need BLUETOOTH_CONNECT). This reads the source.

Run from the repository root: python3 -m unittest discover -s tests -p test_ui_status_icons.py -v
"""
import json
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
UI = ROOT / "launcher-ui"
SRC = UI / "app/src/io/cleanyourtv/ui"


def read(name):
    return (SRC / name).read_text(encoding="utf-8")


def method(text, signature):
    start = text.index(signature)
    return text[start:text.index("\n    }\n", start)]


class StatusIconsTest(unittest.TestCase):
    def setUp(self):
        self.view = read("HomeView.java")

    def test_off_by_default_and_from_config(self):
        self.assertIn('static boolean statusIcons() { return sp().getBoolean("statusIcons", false); }', read("Prefs.java"))
        self.assertIn('if (o.has("status_icons")) Prefs.setStatusIcons(o.getBoolean("status_icons"));', read("TvConfig.java"))
        self.assertIs(json.loads((UI / "config.example.json").read_text(encoding="utf-8"))["status_icons"], False)

    def test_registered_only_while_on_and_while_showing(self):
        start = method(self.view, "    private void startStatus() {")
        self.assertTrue(start.split("{", 1)[1].strip().startswith("if (!Prefs.statusIcons()) return;"))
        self.assertIn("registerDefaultNetworkCallback(statusNetwork, handler)", start)
        self.assertIn('f.addDataScheme("file");', start)
        self.assertIn("Context.RECEIVER_NOT_EXPORTED", start)
        stop = method(self.view, "    private void stopStatus() {")
        self.assertIn("unregisterNetworkCallback(statusNetwork)", stop)
        self.assertIn("unregisterReceiver(drivesChanged)", stop)
        self.assertIn("startStatus();", method(self.view, "    void resume() {"))
        self.assertIn("stopStatus();", method(self.view, "    void pause() {"))

    def test_network_transport(self):
        self.assertIn("TRANSPORT_ETHERNET) ? 2", self.view)
        self.assertIn("TRANSPORT_WIFI) ? 1 : 0", self.view)

    def test_drives_off_the_main_thread(self):
        drives = method(self.view, "    private void updateDrives() {")
        loader = drives.index("LOADER.execute(")
        self.assertLess(loader, drives.index("getStorageVolumes()"))
        self.assertLess(drives.index("getStorageVolumes()"), drives.index("handler.post(() -> setUsbDrive(on));"))
        self.assertIn("v.isRemovable() && Environment.MEDIA_MOUNTED.equals(v.getState())", drives)

    def test_drawn_by_the_buttons(self):
        self.assertIn("        applyTone();\n        drawStatusIcons(c);",
                      method(self.view, "    private void drawLightButtons(Canvas c) {"))
        draw = method(self.view, "    private void drawStatusIcons(Canvas c) {")
        self.assertIn("if (!Prefs.statusIcons() || (network == 0 && !usbDrive)) return;", draw)
        self.assertIn("for (int i = TABS.length; i < TABS.length + lightButtons.size(); i++)", draw)
        self.assertIn("for (String name : STATUS_SYMBOLS)", method(self.view, "    private void layoutLight() {"))
        self.assertIn("static float pointSizeForHeight(Context c, String name, float height)", read("Symbols.java"))
        for name in ("wifi", "ethernet", "usb"):
            self.assertTrue((UI / "app/assets/symbols" / (name + ".png")).is_file(), name)

    def test_no_bluetooth_and_no_new_permission(self):
        self.assertNotIn("BluetoothAdapter", self.view)
        self.assertNotIn("BluetoothProfile", self.view)
        manifest = (UI / "app/AndroidManifest.xml").read_text(encoding="utf-8")
        self.assertNotIn("BLUETOOTH", manifest)
        self.assertNotIn("READ_EXTERNAL_STORAGE", manifest)


if __name__ == "__main__":
    unittest.main()
