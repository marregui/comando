package dev.marregui.comando.ui.kit.format;

import dev.marregui.comando.core.model.HomeDir;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Short human labels for status lines: elapsed time, start times, token counts. */
public final class Labels {

    private Labels() {
    }

    /** How long ago {@code thenMillis} was: "now" under 10 s, then "42 s", "5 min", "3 h", "2 d"; empty when unset. */
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

    /** A running duration: "4 s", "2 min 05 s", "1 h 02 min". */
    public static String elapsed(long millis) {
        long s = Math.max(0, millis / 1000);
        if (s < 60) {
            return s + " s";
        }
        if (s < 3600) {
            return String.format(Locale.ROOT, "%d min %02d s", s / 60, s % 60);
        }
        return String.format(Locale.ROOT, "%d h %02d min", s / 3600, (s % 3600) / 60);
    }

    /** How long something has run, to the minute: "under a minute", "12 min", "3 h 05 min", "2 d 4 h". */
    public static String uptime(long millis) {
        long m = Math.max(0, millis / 60_000);
        if (m < 1) {
            return "under a minute";
        }
        if (m < 60) {
            return m + " min";
        }
        if (m < 24 * 60) {
            return String.format(Locale.ROOT, "%d h %02d min", m / 60, m % 60);
        }
        return String.format(Locale.ROOT, "%d d %d h", m / (24 * 60), (m % (24 * 60)) / 60);
    }

    /** The wall-clock time of {@code millis}: "09:12" today, "Oct 15 09:12" on an earlier day. */
    public static String startedAt(long millis, long nowMillis, ZoneId zone) {
        LocalDateTime at = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), zone);
        LocalDate today = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), zone).toLocalDate();
        return at.format(at.toLocalDate().equals(today) ? TIME : DAY_TIME);
    }

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);
    private static final DateTimeFormatter DAY_TIME = DateTimeFormatter.ofPattern("MMM d HH:mm", Locale.ROOT);

    /** A token count: "950", "12.3k", "1.25M". */
    public static String tokens(long tokens) {
        if (tokens < 1000) {
            return Long.toString(tokens);
        }
        if (tokens < 1_000_000) {
            return String.format(Locale.ROOT, "%.1fk", tokens / 1000.0);
        }
        return String.format(Locale.ROOT, "%.2fM", tokens / 1_000_000.0);
    }

    /** {@code n} with thousands separators: "12,345". */
    public static String count(long n) {
        return String.format(Locale.ROOT, "%,d", n);
    }

    /** {@code ~/x} for a path under the home folder. */
    public static String shortPath(String path) {
        return HomeDir.shorten(path);
    }
}
