package dev.marregui.comando.ui.kit.format;

import java.util.Locale;

/** Short human labels for status lines: elapsed time, money, token counts. */
public final class Labels {

    private Labels() {
    }

    public static String ago(long thenMillis, long nowMillis) {
        if (thenMillis <= 0) {
            return "";
        }
        long s = Math.max(0, (nowMillis - thenMillis) / 1000);
        if (s < 10) {
            return "now";
        }
        if (s < 60) {
            return s + " s";
        }
        if (s < 3600) {
            return s / 60 + " min";
        }
        if (s < 86_400) {
            return s / 3600 + " h";
        }
        return s / 86_400 + " d";
    }

    public static String usd(double usd) {
        if (usd <= 0) {
            return "$0";
        }
        return usd < 1 ? String.format(Locale.ROOT, "$%.3f", usd) : String.format(Locale.ROOT, "$%.2f", usd);
    }

    public static String tokens(long tokens) {
        if (tokens < 1000) {
            return Long.toString(tokens);
        }
        if (tokens < 1_000_000) {
            return String.format(Locale.ROOT, "%.1fk", tokens / 1000.0);
        }
        return String.format(Locale.ROOT, "%.2fM", tokens / 1_000_000.0);
    }

    public static String count(long n) {
        return String.format(Locale.ROOT, "%,d", n);
    }

    public static String shortPath(String path) {
        String home = System.getProperty("user.home");
        return path.startsWith(home) ? "~" + path.substring(home.length()) : path;
    }
}
