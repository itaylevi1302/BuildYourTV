package io.cleanyourtv.ui;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * The text rules for watch providers (docs/specs/provider.md, #106): what a provider may call itself, and how text an app
 * hands over is cleaned before it is shown. The label rules are a module card's (Cards.checkLabel in the other launcher).
 * Plain Java with no android.* or org.json, so the JDK can test it.
 */
final class WatchProviderLabels {
    private WatchProviderLabels() {}

    static final int LABEL_MAX = 24, DETAIL_MAX = 60;
    private static final String TEXT_PUNCTUATION = ".&+'-";
    private static final String[] ANYWHERE = {"verified", "official"};
    private static final String PREFIX = "hdmi";
    private static final Set<String> LAUNCHER_NAMES = new HashSet<>(Arrays.asList(
            "cleanyourtv", "cleanyourtvui", "tvvibehack", "settings", "inputs", "livetv", "home", "defaulthome", "wifi", "av",
            "explore", "allapps"));
    /** A label that contains one of these imitates a streaming service. */
    private static final String[] STREAMING = {"netflix", "primevideo", "amazonprime", "disneyplus", "disney", "hbomax", "appletv",
            "globoplay", "paramount", "crunchyroll", "youtube", "plutotv", "hulu", "peacock", "clarotv", "telecine"};
    /** Too short to refuse inside other words: refused only when the whole label is one of them. */
    private static final Set<String> STREAMING_SHORT = new HashSet<>(Arrays.asList("max", "mubi", "looke", "univer", "hbo"));

    /** Null when the label is acceptable, else the reason. */
    static String check(String label) {
        if (label == null || label.isEmpty()) return "label is missing";
        if (label.length() > LABEL_MAX) return "label is longer than " + LABEL_MAX + " characters";
        if (label.startsWith("@") || label.startsWith("?")) return "label is a resource reference; write the text itself";
        if (!isText(label)) return "label has characters outside A-Z a-z 0-9 space . & + ' -";
        if (label.charAt(0) == ' ' || label.charAt(label.length() - 1) == ' ') return "label starts or ends with a space";
        if (fold(label).isEmpty()) return "label has no letter or digit";
        return null;
    }

    /** Lower case with everything but ASCII letters and digits removed: the form every name comparison uses. */
    static String fold(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = Character.toLowerCase(s.charAt(i));
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) b.append(c);
        }
        return b.toString();
    }

    /** True when a folded label claims endorsement, imitates a streaming service or a name the launcher uses (extraFolded
     *  holds the launcher's own screen names and the services' names, folded too). */
    static boolean reserved(String folded, Set<String> extraFolded) {
        if (folded == null || folded.isEmpty()) return true;
        for (String word : ANYWHERE) if (folded.contains(word)) return true;
        if (folded.startsWith(PREFIX)) return true;
        if (LAUNCHER_NAMES.contains(folded) || STREAMING_SHORT.contains(folded)) return true;
        for (String name : STREAMING) if (folded.contains(name)) return true;
        return extraFolded != null && extraFolded.contains(folded);
    }

    /**
     * Text from another app as it is shown: control, format (bidi overrides, zero width joiners), line and paragraph
     * separator characters become spaces, runs of spaces collapse, at most max characters (never cutting a surrogate
     * pair). Empty when there is nothing.
     */
    static String clean(String s, int max) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(Math.min(s.length(), max + 2));
        boolean space = true;   // leading spaces are dropped too
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int type = Character.getType(c);
            boolean blank = c == ' ' || Character.isISOControl(c) || Character.isWhitespace(c) || type == Character.FORMAT
                    || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR
                    || type == Character.SPACE_SEPARATOR;
            if (blank) {
                if (!space) b.append(' ');
                space = true;
                continue;
            }
            boolean pair = Character.isHighSurrogate(c) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1));
            if (b.length() + (pair ? 2 : 1) > max) break;
            b.append(c);
            if (pair) b.append(s.charAt(++i));
            space = false;
        }
        return b.toString().trim();
    }

    /** Seconds as a clock: 1410 is "23:30", 3725 is "1:02:05". */
    static String clock(int seconds) {
        if (seconds < 0) seconds = 0;
        int h = seconds / 3600, m = seconds / 60 % 60, s = seconds % 60;
        return h > 0 ? h + ":" + two(m) + ":" + two(s) : m + ":" + two(s);
    }

    private static String two(int n) { return n < 10 ? "0" + n : String.valueOf(n); }

    private static boolean isText(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == ' '
                    || TEXT_PUNCTUATION.indexOf(c) >= 0;
            if (!ok) return false;
        }
        return true;
    }
}
