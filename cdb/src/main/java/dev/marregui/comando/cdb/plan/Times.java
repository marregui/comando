package dev.marregui.comando.cdb.plan;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import dev.marregui.comando.cdb.CdbException;

/**
 * Timestamp text as QuestDB reads it in a comparison or an interval ({@code '2026-11'}, {@code '2026-11-14'},
 * {@code '2026-11-14T10:00:00.000000Z'}, a space for the {@code T}), and {@code dateadd} on constants; UTC,
 * microseconds.
 */
final class Times {

    private static final long MICROS_PER_SECOND = 1_000_000L;
    // what a fraction of n digits covers, by n: QuestDB's parseInterval reads '.123' as its whole millisecond
    private static final long[] FRACTION_MICROS = {MICROS_PER_SECOND, 100_000L, 10_000L, 1_000L, 100L, 10L, 1L};
    // the length of a zone offset suffix, ±HH:mm
    private static final int OFFSET_LENGTH = 6;

    private Times() {
    }

    /** The instant {@code text} names, its first microsecond. */
    static long parse(final String text, final int pos) {
        return interval(text, pos)[0];
    }

    /**
     * {@code [from, to)} the text covers at its precision: {@code '2026-11'} the month, {@code '2026-11-14T10'} the
     * hour, a timestamp the second, or the unit of its fraction's last digit ({@code .123} the millisecond). A time
     * may end in {@code Z} or an offset {@code ±HH:mm}, as Python's {@code isoformat()} writes it; the bounds are then
     * moved to UTC.
     */
    static long[] interval(final String text, final int pos) {
        final int zone = zoneAt(text);
        final long offsetMicros = zone < 0 ? 0 : offsetMicros(text, zone, pos);
        final String s = zone >= 0 ? text.substring(0, zone)
                : text.endsWith("Z") ? text.substring(0, text.length() - 1) : text;
        final int[] f = new int[6];
        final int[] widths = {4, 2, 2, 2, 2, 2};
        final char[] separators = {'-', '-', 'T', ':', ':', '.'};
        int at = 0;
        int fields = 0;
        long micros = 0;
        int fractionDigits = 0;
        while (fields < 6 && at < s.length()) {
            if (fields > 0) {
                final char c = s.charAt(at);
                final boolean ok = c == separators[fields - 1] || fields == 3 && c == ' ';
                if (!ok) {
                    throw unparsable(text, pos);
                }
                at++;
            }
            final int end = at + widths[fields];
            if (end > s.length()) {
                throw unparsable(text, pos);
            }
            f[fields++] = digits(s, at, end, text, pos);
            at = end;
        }
        if (fields == 6 && at < s.length()) {
            if (s.charAt(at) != '.') {
                throw unparsable(text, pos);
            }
            at++;
            final int start = at;
            while (at < s.length() && fractionDigits < 6) {
                micros = micros * 10 + digit(s.charAt(at), text, pos);
                at++;
                fractionDigits++;
            }
            if (at == start) {
                throw unparsable(text, pos);
            }
            while (at < s.length()) {
                digit(s.charAt(at), text, pos);
                at++;
            }
            for (int i = fractionDigits; i < 6; i++) {
                micros *= 10;
            }
        }
        if (fields == 0 || at != s.length()) {
            throw unparsable(text, pos);
        }
        final ZonedDateTime from;
        try {
            // a field the text leaves out is the first month or day; one it gives as 00 is refused, never raised to 1
            from = ZonedDateTime.of(f[0], fields >= 2 ? f[1] : 1, fields >= 3 ? f[2] : 1, f[3], f[4], f[5], 0,
                    ZoneOffset.UTC);
        } catch (final DateTimeException _) {
            throw unparsable(text, pos);
        }
        final ZonedDateTime to = switch (fields) {
            case 1 -> from.plusYears(1);
            case 2 -> from.plusMonths(1);
            case 3 -> from.plusDays(1);
            case 4 -> from.plusHours(1);
            case 5 -> from.plusMinutes(1);
            default -> from;
        };
        final long loMicros = micros(from) + micros;
        final long hiMicros = fields == 6 ? loMicros + FRACTION_MICROS[fractionDigits] : micros(to);
        return new long[] {loMicros - offsetMicros, hiMicros - offsetMicros};
    }

    // where a trailing ±HH:mm after a time of day (a 'T' or ' ' at 10) starts, else -1
    private static int zoneAt(final String text) {
        final int at = text.length() - OFFSET_LENGTH;
        final boolean signed = at > 10 && (text.charAt(at) == '+' || text.charAt(at) == '-');
        return signed && text.charAt(at + 3) == ':' && (text.charAt(10) == 'T' || text.charAt(10) == ' ') ? at : -1;
    }

    // micros east of UTC of the ±HH:mm at {@code at}
    private static long offsetMicros(final String text, final int at, final int pos) {
        final int hours = digits(text, at + 1, at + 3, text, pos);
        final int minutes = digits(text, at + 4, at + OFFSET_LENGTH, text, pos);
        if (hours > 23 || minutes > 59) {
            throw unparsable(text, pos);
        }
        final long micros = (hours * 60L + minutes) * 60L * MICROS_PER_SECOND;
        return text.charAt(at) == '-' ? -micros : micros;
    }

    /**
     * {@code dateadd(unit, amount, micros)}: units {@code y M w d h m s T u}, years and months on the calendar, as
     * QuestDB's {@code dateadd}; a year or month past the calendar's range is refused.
     */
    static long dateadd(final String unit, final long amount, final long micros, final int pos) {
        return switch (unit) {
            case "y", "M" -> {
                try {
                    final ZonedDateTime at = "y".equals(unit) ? zoned(micros).plusYears(amount)
                            : zoned(micros).plusMonths(amount);
                    yield micros(at) + Math.floorMod(micros, MICROS_PER_SECOND);
                } catch (final DateTimeException _) {
                    throw new CdbException("cannot add out of range [unit=" + unit + ", amount=" + amount + ']', pos);
                }
            }
            case "w" -> micros + amount * TimeUnit.DAYS.toMicros(7);
            case "d" -> micros + amount * TimeUnit.DAYS.toMicros(1);
            case "h" -> micros + amount * TimeUnit.HOURS.toMicros(1);
            case "m" -> micros + amount * TimeUnit.MINUTES.toMicros(1);
            case "s" -> micros + amount * MICROS_PER_SECOND;
            case "T" -> micros + amount * 1_000L;
            case "u" -> micros + amount;
            default -> throw new CdbException("cannot add unknown unit [unit=" + unit + ']', pos);
        };
    }

    /**
     * A {@code CREATE TABLE}'s {@code TTL n UNIT}, HOUR, DAY, WEEK, MONTH or YEAR, singular, plural or QuestDB's
     * one-letter form, as the store keeps it: HOUR, DAY and WEEK as microseconds, MONTH and YEAR as a negative count of
     * calendar months, as QuestDB's {@code ttlHoursOrMonths} keeps them apart. A negative amount, or one past
     * QuestDB's range ({@code Integer.MAX_VALUE} hours or months), is refused, as QuestDB refuses it.
     */
    static long ttlMicrosOrMonths(final String ttl, final int pos) {
        final String[] parts = ttl.trim().split("\\s+");
        final String amount;
        final String unit;
        if (parts.length == 2) {
            amount = parts[0];
            unit = parts[1];
        } else if (parts.length == 1 && parts[0].length() > 1) {
            amount = parts[0].substring(0, parts[0].length() - 1);
            unit = parts[0].substring(parts[0].length() - 1);
        } else {
            throw new CdbException("cannot read TTL [ttl=" + ttl + ']', pos);
        }
        final long n;
        try {
            n = Long.parseLong(amount);
        } catch (final NumberFormatException _) {
            throw new CdbException("cannot read TTL amount [ttl=" + ttl + ']', pos);
        }
        if (n < 0 || n > Integer.MAX_VALUE) {
            throw outOfRange(ttl, pos);
        }
        // hours when positive, months when negative, as QuestDB's toHoursOrMonths counts them
        final long hoursOrMonths = switch (unit.toUpperCase(Locale.ROOT)) {
            case "H", "HOUR", "HOURS" -> n;
            case "D", "DAY", "DAYS" -> n * TimeUnit.DAYS.toHours(1);
            case "W", "WEEK", "WEEKS" -> n * TimeUnit.DAYS.toHours(7);
            case "M", "MONTH", "MONTHS" -> -n;
            case "Y", "YEAR", "YEARS" -> -n * 12;
            default -> throw new CdbException("cannot read TTL unit [ttl=" + ttl + ']', pos);
        };
        if (Math.abs(hoursOrMonths) > Integer.MAX_VALUE) {
            throw outOfRange(ttl, pos);
        }
        return hoursOrMonths < 0 ? hoursOrMonths : TimeUnit.HOURS.toMicros(hoursOrMonths);
    }

    private static CdbException outOfRange(final String ttl, final int pos) {
        return new CdbException("cannot read TTL out of range [ttl=" + ttl + ", maxHoursOrMonths=" + Integer.MAX_VALUE
                + ']', pos);
    }

    private static ZonedDateTime zoned(final long micros) {
        return Instant.ofEpochSecond(Math.floorDiv(micros, MICROS_PER_SECOND)).atZone(ZoneOffset.UTC);
    }

    private static long micros(final ZonedDateTime t) {
        return t.toEpochSecond() * MICROS_PER_SECOND;
    }

    private static int digits(final String s, final int from, final int to, final String text, final int pos) {
        int v = 0;
        for (int i = from; i < to; i++) {
            v = v * 10 + digit(s.charAt(i), text, pos);
        }
        return v;
    }

    private static int digit(final char c, final String text, final int pos) {
        if (c < '0' || c > '9') {
            throw unparsable(text, pos);
        }
        return c - '0';
    }

    private static CdbException unparsable(final String text, final int pos) {
        return new CdbException("cannot parse timestamp [value=" + text + ']', pos);
    }
}
