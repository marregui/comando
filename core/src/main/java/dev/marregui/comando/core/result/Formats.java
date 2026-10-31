package dev.marregui.comando.core.result;

/** Allocation-free formatting into a caller's buffer, for cells painted on every frame. */
public final class Formats {

    private Formats() {
    }

    /** Appends {@code yyyy-MM-ddTHH:mm:ss.SSSSSSZ} for epoch microseconds UTC. */
    public static void appendMicrosUtc(final StringBuilder sink, final long micros) {
        final long seconds = Math.floorDiv(micros, 1_000_000L);
        final int fraction = (int) Math.floorMod(micros, 1_000_000L);
        final long days = Math.floorDiv(seconds, 86_400L);
        final int secOfDay = (int) Math.floorMod(seconds, 86_400L);
        // civil-from-days (Howard Hinnant), valid for the whole proleptic Gregorian range of long days
        final long z = days + 719_468L;
        final long era = Math.floorDiv(z, 146_097L);
        final long doe = z - era * 146_097L;
        final long yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
        long y = yoe + era * 400;
        final long doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
        final long mp = (5 * doy + 2) / 153;
        final long d = doy - (153 * mp + 2) / 5 + 1;
        final long m = mp < 10 ? mp + 3 : mp - 9;
        if (m <= 2) {
            y++;
        }
        pad(sink, y, 4).append('-');
        pad(sink, m, 2).append('-');
        pad(sink, d, 2).append('T');
        pad(sink, secOfDay / 3600, 2).append(':');
        pad(sink, (secOfDay / 60) % 60, 2).append(':');
        pad(sink, secOfDay % 60, 2).append('.');
        pad(sink, fraction, 6).append('Z');
    }

    /** Appends a duration in milliseconds as {@code 850 ms}, {@code 12.4 s} or {@code 3 m 05 s}. */
    public static void appendMillis(final StringBuilder sink, final long millis) {
        if (millis < 1000) {
            sink.append(millis).append(" ms");
        } else if (millis < 60_000) {
            sink.append(millis / 1000).append('.').append((millis % 1000) / 100).append(" s");
        } else {
            sink.append(millis / 60_000).append(" m ");
            pad(sink, (millis / 1000) % 60, 2).append(" s");
        }
    }

    /** Appends a size in bytes as {@code 500 B}, {@code 12 kB} or {@code 3 MB}, each unit 1024 of the one below. */
    public static void appendBytes(final StringBuilder sink, final long bytes) {
        if (bytes < 1024) {
            sink.append(bytes).append(" B");
        } else if (bytes < 1024 * 1024) {
            sink.append(bytes / 1024).append(" kB");
        } else {
            sink.append(bytes / (1024 * 1024)).append(" MB");
        }
    }

    /** {@link #appendBytes} as a string, for labels made once. */
    public static String bytes(final long bytes) {
        final StringBuilder sb = new StringBuilder(8);
        appendBytes(sb, bytes);
        return sb.toString();
    }

    private static StringBuilder pad(final StringBuilder sink, long value, final int width) {
        if (value < 0) {
            sink.append('-');
            value = -value;
        }
        int digits = 1;
        for (long v = value; v >= 10; v /= 10) {
            digits++;
        }
        for (int i = digits; i < width; i++) {
            sink.append('0');
        }
        return sink.append(value);
    }
}
