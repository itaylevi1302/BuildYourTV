package io.cleanyourtv.ui;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Cases for WatchProviderLabels (docs/specs/provider.md, #106). Run with java -ea. */
public final class WatchProviderLabelsTest {
    private static int count;

    private static void check(boolean ok, String what) {
        count++;
        if (!ok) throw new AssertionError(what);
    }

    private static boolean accepted(String label) {
        return WatchProviderLabels.check(label) == null
                && !WatchProviderLabels.reserved(WatchProviderLabels.fold(label), Collections.<String>emptySet());
    }

    public static void main(String[] args) {
        check(accepted("Open Movies"), "Open Movies");
        check(accepted("My TV"), "My TV");
        check(accepted("Mom's Cinema & Co."), "punctuation");
        for (String bad : new String[] {"Netflix", "netflix-4k", "Max", "CleanYourTV", "Official Movies", "HDMI 2", " x",
                "abcdefghijklmnopqrstuvwxy", "", "@string/x", "Filmes ão", "...", "Settings", "Disney Fans"}) {
            check(!accepted(bad), "refused: " + bad);
        }
        check(accepted("Maximum"), "Max is refused only on its own");
        Set<String> extra = new HashSet<>();
        extra.add("pluto");
        check(WatchProviderLabels.reserved("pluto", extra), "a name the UI uses");
        check(WatchProviderLabels.clean("a‮b\nc", 60).equals("a b c") || WatchProviderLabels.clean("a‮b\nc", 60).equals("ab c"),
                "bidi and newline");
        check(!WatchProviderLabels.clean("x‮y", 60).contains("‮"), "no bidi override");
        check(!WatchProviderLabels.clean("line\nbreak", 60).contains("\n"), "no newline");
        StringBuilder long80 = new StringBuilder();
        for (int i = 0; i < 80; i++) long80.append('a');
        check(WatchProviderLabels.clean(long80.toString(), 60).length() == 60, "cut at 60");
        check(WatchProviderLabels.clean("  a   b  ", 60).equals("a b"), "collapse");
        check(WatchProviderLabels.clean(null, 60).isEmpty(), "null");
        String pair = "ab🎬cd";   // a surrogate pair at index 2 and 3
        check(WatchProviderLabels.clean(pair, 3).equals("ab"), "a pair is not split");
        check(WatchProviderLabels.clean(pair, 4).equals("ab🎬"), "a pair fits whole");
        check(WatchProviderLabels.clock(1410).equals("23:30"), "clock 1410");
        check(WatchProviderLabels.clock(3725).equals("1:02:05"), "clock 3725");
        check(WatchProviderLabels.clock(5).equals("0:05"), "clock 5");
        System.out.println("WatchProviderLabels: " + count + " assertions passed");
    }
}
