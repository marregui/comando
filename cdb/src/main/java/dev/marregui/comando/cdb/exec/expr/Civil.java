package dev.marregui.comando.cdb.exec.expr;

import dev.marregui.comando.cdb.exec.Vector;

/**
 * Calendar arithmetic over TIMESTAMP microseconds, UTC, proleptic Gregorian. A civil date is packed into one long as
 * {@code year * 10000 + month * 100 + day} and read back with {@link #year}, {@link #month} and {@link #day}. The
 * floors align to the epoch, as QuestDB's {@code timestamp_floor} does: {@code 3d} counts three-day buckets from
 * 1970-01-01, weeks start on Monday, months and years count from 1970-01.
 */
final class Civil {

    static final long MILLI_MICROS = 1_000L;
    static final long SECOND_MICROS = 1_000_000L;
    static final long MINUTE_MICROS = 60 * SECOND_MICROS;
    static final long HOUR_MICROS = 60 * MINUTE_MICROS;
    static final long DAY_MICROS = 24 * HOUR_MICROS;
    static final long WEEK_MICROS = 7 * DAY_MICROS;

    private static final int EPOCH_YEAR = 1970;
    // the lengths of a zone suffix: ±HH, ±HHmm, ±HH:mm
    private static final int ZONE_HOURS = 3;
    private static final int ZONE_COMPACT = 5;
    private static final int ZONE_COLON = 6;
    private static final int[] MONTH_DAYS = {31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};

    private Civil() {
    }

    /** Days since 1970-01-01 of the civil date, after H. Hinnant's {@code days_from_civil}. */
    static long days(final long year, final int month, final int day) {
        final long y = month <= 2 ? year - 1 : year;
        final long era = Math.floorDiv(y, 400);
        final long yearOfEra = y - era * 400;
        final long dayOfYear = (153L * (month > 2 ? month - 3 : month + 9) + 2) / 5 + day - 1;
        final long dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear;
        return era * 146_097 + dayOfEra - 719_468;
    }

    /** The packed civil date of {@code days} since 1970-01-01, after H. Hinnant's {@code civil_from_days}. */
    static long date(final long days) {
        final long z = days + 719_468;
        final long era = Math.floorDiv(z, 146_097);
        final long dayOfEra = z - era * 146_097;
        final long yearOfEra = (dayOfEra - dayOfEra / 1460 + dayOfEra / 36_524 - dayOfEra / 146_096) / 365;
        final long dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100);
        final long shifted = (5 * dayOfYear + 2) / 153;
        final long day = dayOfYear - (153 * shifted + 2) / 5 + 1;
        final long month = shifted < 10 ? shifted + 3 : shifted - 9;
        final long year = yearOfEra + era * 400 + (month <= 2 ? 1 : 0);
        return year * 10_000 + month * 100 + day;
    }

    static long year(final long packed) {
        return Math.floorDiv(packed, 10_000);
    }

    static int month(final long packed) {
        return Math.floorMod(packed, 10_000) / 100;
    }

    static int day(final long packed) {
        return Math.floorMod(packed, 100);
    }

    static boolean isLeap(final long year) {
        return year % 4 == 0 && (year % 100 != 0 || year % 400 == 0);
    }

    static int daysInMonth(final long year, final int month) {
        return month == 2 && isLeap(year) ? 29 : MONTH_DAYS[month - 1];
    }

    /** ISO day of week of {@code micros}: 1 is Monday, 7 is Sunday. */
    static int dayOfWeek(final long micros) {
        return Math.floorMod(Math.floorDiv(micros, DAY_MICROS) + 3, 7) + 1;
    }

    /** {@code micros} moved by {@code months}; the day is clamped to the last day of the month it lands in. */
    static long addMonths(final long micros, final long months) {
        final long days = Math.floorDiv(micros, DAY_MICROS);
        final long packed = date(days);
        final long total = year(packed) * 12 + month(packed) - 1 + months;
        final long year = Math.floorDiv(total, 12);
        final int month = Math.floorMod(total, 12) + 1;
        final int day = Math.min(day(packed), daysInMonth(year, month));
        return days(year, month, day) * DAY_MICROS + Math.floorMod(micros, DAY_MICROS);
    }

    /**
     * Whole months between {@code a} and {@code b}, in either order: the difference of their months, less one when the
     * earlier instant lies further into its month than the later one does, as QuestDB's {@code datediff('M')} counts.
     */
    static long monthsBetween(final long a, final long b) {
        final long lo = Math.min(a, b);
        final long hi = Math.max(a, b);
        final long loDate = date(Math.floorDiv(lo, DAY_MICROS));
        final long hiDate = date(Math.floorDiv(hi, DAY_MICROS));
        final long months = (year(hiDate) - year(loDate)) * 12 + month(hiDate) - month(loDate);
        return lo - monthStart(loDate) > hi - monthStart(hiDate) ? months - 1 : months;
    }

    static long floorFixed(final long micros, final long unitMicros) {
        return micros - Math.floorMod(micros, unitMicros);
    }

    /** The Monday that starts the {@code stride}-week bucket of {@code micros}, buckets counted from 1970-01-05. */
    static long floorWeeks(final long micros, final long stride) {
        return micros - Math.floorMod(micros + 3 * DAY_MICROS, stride * WEEK_MICROS);
    }

    static long floorMonths(final long micros, final long stride) {
        final long packed = date(Math.floorDiv(micros, DAY_MICROS));
        final long total = (year(packed) - EPOCH_YEAR) * 12 + month(packed) - 1;
        final long floored = total - Math.floorMod(total, stride);
        return days(EPOCH_YEAR + Math.floorDiv(floored, 12), Math.floorMod(floored, 12) + 1, 1) * DAY_MICROS;
    }

    static long floorYears(final long micros, final long stride) {
        final long year = year(date(Math.floorDiv(micros, DAY_MICROS)));
        return days(year - Math.floorMod(year - EPOCH_YEAR, stride), 1, 1) * DAY_MICROS;
    }

    /** Appends {@code micros} as {@code yyyy-MM-ddTHH:mm:ss.ffffffZ}. */
    static void appendIso(final StringBuilder sink, final long micros) {
        final long packed = date(Math.floorDiv(micros, DAY_MICROS));
        final long timeOfDay = Math.floorMod(micros, DAY_MICROS);
        pad(sink, year(packed), 4);
        sink.append('-');
        pad(sink, month(packed), 2);
        sink.append('-');
        pad(sink, day(packed), 2);
        sink.append('T');
        pad(sink, timeOfDay / HOUR_MICROS, 2);
        sink.append(':');
        pad(sink, timeOfDay / MINUTE_MICROS % 60, 2);
        sink.append(':');
        pad(sink, timeOfDay / SECOND_MICROS % 60, 2);
        sink.append('.');
        pad(sink, timeOfDay % SECOND_MICROS, 6);
        sink.append('Z');
    }

    /** Appends {@code value} with leading zeros up to {@code width} digits; a negative value keeps its sign. */
    static void pad(final StringBuilder sink, final long value, final int width) {
        if (value < 0) {
            sink.append('-');
            pad(sink, -value, width);
            return;
        }
        long limit = 10;
        for (int digits = 1; digits < width; digits++) {
            if (value < limit) {
                sink.append('0');
            }
            limit *= 10;
        }
        sink.append(value);
    }

    /**
     * Micros of {@code text} in the ISO subset QuestDB casts to TIMESTAMP:
     * {@code yyyy[-MM[-dd[(T| )HH[:mm[:ss[.f]]]]]]}, a dot without digits allowed, then, only after the seconds,
     * {@code Z}, {@code ±HH:mm}, {@code ±HHmm}, {@code ±HH} or nothing (UTC). Fraction digits past the sixth are
     * dropped. NULL when the text does not match.
     */
    static long parse(final CharSequence text) {
        final int n = text.length();
        final int year = digits(text, 0, 4);
        if (year < 0) {
            return Vector.NULL_LONG;
        }
        int month = 1;
        int day = 1;
        int hour = 0;
        int minute = 0;
        int second = 0;
        long fraction = 0;
        boolean zoned = false;
        int i = 4;
        if (i < n && text.charAt(i) == '-') {
            month = digits(text, i + 1, 2);
            if (month < 1 || month > 12) {
                return Vector.NULL_LONG;
            }
            i += 3;
            if (i < n && text.charAt(i) == '-') {
                day = digits(text, i + 1, 2);
                if (day < 1 || day > daysInMonth(year, month)) {
                    return Vector.NULL_LONG;
                }
                i += 3;
                if (i < n && (text.charAt(i) == 'T' || text.charAt(i) == ' ')) {
                    hour = digits(text, i + 1, 2);
                    if (hour < 0 || hour > 23) {
                        return Vector.NULL_LONG;
                    }
                    i += 3;
                    if (i < n && text.charAt(i) == ':') {
                        minute = digits(text, i + 1, 2);
                        if (minute < 0 || minute > 59) {
                            return Vector.NULL_LONG;
                        }
                        i += 3;
                        if (i < n && text.charAt(i) == ':') {
                            second = digits(text, i + 1, 2);
                            if (second < 0 || second > 59) {
                                return Vector.NULL_LONG;
                            }
                            i += 3;
                            zoned = true;
                            if (i < n && text.charAt(i) == '.') {
                                final int start = ++i;
                                while (i < n && text.charAt(i) >= '0' && text.charAt(i) <= '9') {
                                    if (i - start < 6) {
                                        fraction = fraction * 10 + text.charAt(i) - '0';
                                    }
                                    i++;
                                }
                                for (int k = i - start; k < 6; k++) {
                                    fraction *= 10;
                                }
                            }
                        }
                    }
                }
            }
        }
        final long local = days(year, month, day) * DAY_MICROS + hour * HOUR_MICROS + minute * MINUTE_MICROS
                + second * SECOND_MICROS + fraction;
        final long offset = offset(text, i, zoned);
        return offset == Vector.NULL_LONG ? Vector.NULL_LONG : local - offset;
    }

    /**
     * Micros east of UTC of the zone suffix at {@code i}: none is 0; after the seconds ({@code zoned}) {@code Z} is 0
     * and {@code ±HH:mm}, {@code ±HHmm} or {@code ±HH} the offset; NULL for anything else.
     */
    private static long offset(final CharSequence text, final int i, final boolean zoned) {
        final int n = text.length();
        if (i == n) {
            return 0;
        }
        if (!zoned) {
            return Vector.NULL_LONG;
        }
        final char sign = text.charAt(i);
        if (sign == 'Z' && i == n - 1) {
            return 0;
        }
        final int left = n - i;
        if ((sign != '+' && sign != '-') || left != ZONE_HOURS && left != ZONE_COMPACT && left != ZONE_COLON) {
            return Vector.NULL_LONG;
        }
        final boolean colon = left == ZONE_COLON;
        if (colon && text.charAt(i + 3) != ':') {
            return Vector.NULL_LONG;
        }
        final int hours = digits(text, i + 1, 2);
        final int minutes = left == ZONE_HOURS ? 0 : digits(text, colon ? i + 4 : i + 3, 2);
        if (hours < 0 || hours > 23 || minutes < 0 || minutes > 59) {
            return Vector.NULL_LONG;
        }
        final long micros = hours * HOUR_MICROS + minutes * MINUTE_MICROS;
        return sign == '+' ? micros : -micros;
    }

    /** The decimal of {@code count} digits at {@code from}, or -1 when the text is short or not all digits. */
    private static int digits(final CharSequence text, final int from, final int count) {
        if (from + count > text.length()) {
            return -1;
        }
        int value = 0;
        for (int i = from; i < from + count; i++) {
            final char c = text.charAt(i);
            if (c < '0' || c > '9') {
                return -1;
            }
            value = value * 10 + c - '0';
        }
        return value;
    }

    private static long monthStart(final long packed) {
        return days(year(packed), month(packed), 1) * DAY_MICROS;
    }
}
