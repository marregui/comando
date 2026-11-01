package dev.marregui.comando.ui.kit.format;

/** Dotted version texts compared by their numbers: Claude Code's "2.1.316", Node's "v22.20.0". */
public final class Versions {

    private Versions() {
    }

    /**
     * Negative, zero or positive as {@code a} is older than, the same as or newer than {@code b}. An empty text is
     * oldest; a leading {@code v} is ignored; each dot part is read by its leading digits, none being 0, so
     * "316-rc1" is 316; a missing part is 0, so "2.1" equals "2.1.0".
     */
    public static int compare(final String a, final String b) {
        if (a.isEmpty() || b.isEmpty()) {
            return Boolean.compare(!a.isEmpty(), !b.isEmpty());
        }
        final String[] x = parts(a);
        final String[] y = parts(b);
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            final int c = Long.compare(number(x, i), number(y, i));
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    private static String[] parts(final String version) {
        return (version.startsWith("v") ? version.substring(1) : version).split("\\.");
    }

    /** The leading digits of part {@code i}, 0 when it has none or is missing; a number past a long's range is capped. */
    private static long number(final String[] parts, final int i) {
        if (i >= parts.length) {
            return 0;
        }
        final String part = parts[i];
        long n = 0;
        for (int j = 0; j < part.length() && part.charAt(j) >= '0' && part.charAt(j) <= '9'; j++) {
            n = n > (Long.MAX_VALUE - 9) / 10 ? Long.MAX_VALUE : n * 10 + (part.charAt(j) - '0');
        }
        return n;
    }
}
