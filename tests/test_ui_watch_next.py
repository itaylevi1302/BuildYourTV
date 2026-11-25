"""CleanYourTV UI: Continue Watching from Android TV's Watch Next list (#66).

From Free My TV PR 42 (WatchNext.java, WatchArt.java) and 8490c56 (sampled decoding). Off by default: Home Settings →
Top of the screen (or config.json's `top_of_screen`) picks it, and READ_TV_LISTINGS comes only from ADB
(build-and-install.sh WATCH_NEXT=1). This reads the sources and checks the promises the README makes: the list is read
only while it's picked and only with the permission, never polled, capped, the pictures are sampled before decoding and
come only from the TV, and a link another app wrote opens only that app.

Run from the repository root: python3 -m unittest discover -s tests -p test_ui_watch_next.py -v
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
UI = ROOT / "launcher-ui"
SRC = UI / "app/src/io/cleanyourtv/ui"


def read(name):
    return (SRC / name).read_text(encoding="utf-8")


def method(text, signature):
    """From the signature to the method's closing brace at the same indentation."""
    start = text.index(signature)
    indent = signature[:len(signature) - len(signature.lstrip())]
    return text[start:text.index("\n" + indent + "}\n", start)]


class WatchNextReader(unittest.TestCase):
    def setUp(self):
        self.src = read("WatchNext.java")

    def test_the_permission_comes_before_any_query(self):
        load = method(self.src, "    static List<Item> load(Context c) {")
        self.assertTrue(load.split("\n")[1].strip().startswith("if (!allowed(c)) return Collections.emptyList();"))
        self.assertIn('PERMISSION = "android.permission.READ_TV_LISTINGS"', self.src)

    def test_a_refusing_or_missing_provider_is_an_empty_list(self):
        for sig in ("    private static List<Item> read(Context c, ContentResolver cr, Uri uri) {",
                    "    private static List<Long> continueChannels(ContentResolver cr, String self) {"):
            body = method(self.src, sig)
            self.assertIn("catch (RuntimeException e)", body)
            self.assertIn("if (cur == null) return", body)

    def test_capped(self):
        self.assertIn("static final int PER_APP = 2, MAX = 6;", self.src)
        self.assertIn("private static final int READ_MAX = 60;", self.src)
        self.assertIn("rows++ < READ_MAX", self.src)
        self.assertIn("if (out.size() >= MAX) break;", self.src)

    def test_watchlist_entries_are_not_half_watched(self):
        self.assertIn("WATCH_NEXT_TYPE_WATCHLIST) continue;", self.src)

    def test_pictures_from_the_tv_or_https(self):
        # https ones are fetched only by NetPicture (tests/test_ui_net_picture.py)
        body = method(self.src, "    static Uri picture(String s) {")
        self.assertIn('"content".equalsIgnoreCase(scheme)', body)
        self.assertIn('"android.resource".equalsIgnoreCase(scheme)', body)
        self.assertIn('"https".equalsIgnoreCase(scheme)', body)
        self.assertNotIn('"file"', body)
        self.assertNotIn('"http"', body)

    def test_https_pictures_only_from_apps_with_network(self):
        self.assertIn("android.Manifest.permission.INTERNET", self.src)

    def test_a_link_opens_only_its_own_app(self):
        body = method(self.src, "    static Intent linkIntent(String uri, String pkg, String self) {")
        self.assertIn("pkg.equals(self)", body)
        self.assertIn("!intent.getComponent().getPackageName().equals(pkg)) return null;", body)
        self.assertIn("intent.setSelector(null);", body)
        self.assertIn("intent.setPackage(pkg);", body)
        for flag in ("FLAG_GRANT_READ_URI_PERMISSION", "FLAG_GRANT_WRITE_URI_PERMISSION",
                     "FLAG_GRANT_PERSISTABLE_URI_PERMISSION", "FLAG_GRANT_PREFIX_URI_PERMISSION"):
            self.assertIn(flag, body)


class WatchArtPictures(unittest.TestCase):
    def setUp(self):
        self.src = read("WatchArt.java")

    def test_sampled_before_decoding(self):
        load = method(self.src, "    private Bitmap load(Uri uri, int cw, int ch) throws IOException {")
        self.assertIn("bounds.inJustDecodeBounds = true;", load)
        self.assertIn("> MAX_PIXELS", load)
        self.assertLess(load.index("opts.inSampleSize = s * 2;"), load.index("decodeByteArray(data, 0, data.length, opts)"))
        self.assertIn("private static final long MAX_PIXELS = 2_000_000;", self.src)

    def test_memory_is_capped_and_let_go(self):
        self.assertRegex(self.src, r"CACHE_MAX = [1-8];")
        self.assertIn("if (size() <= CACHE_MAX) return false;", self.src)
        self.assertIn("WatchArt.trimLoaded();", read("App.java"))
        self.assertIn("watchArt.close();", method(read("HomeView.java"), "    void destroy() {"))

    def test_reads_are_capped_in_size_and_time(self):
        self.assertIn("MAX_BYTES = 8 << 20", self.src)
        self.assertIn("readAll(in, MAX_BYTES, System.currentTimeMillis() + READ_MS)", self.src)

    def test_a_provider_that_blocks_is_cut_off(self):
        self.assertIn("openTypedAssetFileDescriptor(", self.src)
        self.assertIn("CancellationSignal", self.src)
        self.assertIn("WATCHDOG.schedule(", self.src)

    def test_logs_never_carry_the_address(self):
        self.assertNotIn("e.getMessage()", self.src)
        self.assertNotIn("e.getMessage()", read("WatchNext.java"))


class HomeScreen(unittest.TestCase):
    def setUp(self):
        self.view = read("HomeView.java")

    def test_read_only_while_picked_and_allowed(self):
        body = method(self.view, "    private void reload() {")
        self.assertIn("final boolean wm = Prefs.continueWatching();", body)
        self.assertIn("final boolean allowed = wm && WatchNext.allowed(c);", body)
        self.assertIn("final List<WatchNext.Item> w = allowed ? WatchNext.load(c) : null;", body)
        self.assertIn("setWatching(wm, allowed, w);", body)
        self.assertEqual(self.view.count("WatchNext.load("), 1)

    def test_never_polled(self):
        start = self.view.index("    private final Runnable tick = new Runnable() {")
        self.assertNotIn("WatchNext", self.view[start:self.view.index("\n    };\n", start)])
        self.assertNotIn("ContentObserver", self.view)

    def test_the_picks_step_aside(self):
        self.assertIn("return !watchMode && Recs.hasKey(getContext());", method(self.view, "    private boolean picksOn() {"))
        self.assertIn("tabPicks[t] = watchMode ? new ArrayList<Recs.Pick>() : recs.all(TAB_KIND[t]);",
                      method(self.view, "    private void updateCards() {"))

    def test_ok_resumes_through_the_link(self):
        self.assertIn("WatchNext.open(activity, w)", method(self.view, "    private void activate() {"))

    def test_progress_bar_and_focus(self):
        card = method(self.view, "    private void drawWatchCard(Canvas c, int i) {")
        self.assertIn("it.progress()", card)
        self.assertIn("heroChanged |= aim(watchFocus, !sheetOpen && zone == Z_WATCH ? watchIndex : -1, snap);", self.view)
        self.assertIn("step(watchFocus, dt)", self.view)


class Settings(unittest.TestCase):
    def test_off_by_default(self):
        prefs = read("Prefs.java")
        self.assertIn('sp().getString("topOfScreen", TOP_PICKS)', prefs)
        self.assertIn('"top_of_screen": "picks"', (UI / "config.example.json").read_text(encoding="utf-8"))

    def test_home_settings_row(self):
        settings = read("SettingsActivity.java")
        self.assertIn("l.add(Strings.get(R.string.top_of_screen));", settings)
        self.assertIn("case TOP_ROW:", method(settings, "        private void adjust(int dir) {"))
        self.assertRegex(settings, r"\bTOP_ROW = \d+, CLOCK_ROW = \d+")
        # Home Settings has room for every row: pop is indexed by row.
        rows = len(re.findall(r"^            l\.add\(", method(settings, "        private String[] settingLabels() {"), re.M))
        pop = int(re.search(r"private final Spring\[\] pop = new Spring\[(\d+)\];", settings).group(1))
        self.assertGreaterEqual(pop, rows + 1)   # + the dollar (`if (dollar()) l.add(...)`)

    def test_config_value_is_checked(self):
        body = method(read("TvConfig.java"), "    static boolean apply(Context c) {")
        self.assertIn('AppLog.w("config.json: top_of_screen skipped")', body)


class Permission(unittest.TestCase):
    def test_declared_and_provider_visible(self):
        manifest = (UI / "app/AndroidManifest.xml").read_text(encoding="utf-8")
        self.assertIn('<uses-permission android:name="android.permission.READ_TV_LISTINGS" />', manifest)
        queries = manifest[manifest.index("<queries>"):manifest.index("</queries>")]
        self.assertIn('<provider android:authorities="android.media.tv" />', queries)

    def test_granted_only_when_asked_and_a_refusal_does_not_stop_the_install(self):
        script = (UI / "build-and-install.sh").read_text(encoding="utf-8")
        block = script[script.index('case "${WATCH_NEXT:-}" in'):]
        block = block[:block.index("\nesac\n")]
        self.assertIn("pm grant $PKG android.permission.READ_TV_LISTINGS", block)
        self.assertIn("|| true", block)
        self.assertIn("pm revoke $PKG android.permission.READ_TV_LISTINGS", block)
        # Nowhere else: without WATCH_NEXT=1 the permission is left as it is.
        self.assertEqual(script.count("pm grant"), 1)

    def test_documented(self):
        readme = (UI / "README.md").read_text(encoding="utf-8")
        self.assertIn("## Continue Watching (optional)", readme)
        self.assertIn("pm grant io.cleanyourtv.ui android.permission.READ_TV_LISTINGS", readme)
        self.assertIn("pm revoke io.cleanyourtv.ui android.permission.READ_TV_LISTINGS", readme)
        self.assertIn("WATCH_NEXT=1", readme)


if __name__ == "__main__":
    unittest.main()
