package dev.marregui.comando.cdb.exec;

import dev.marregui.comando.cdb.CdbException;

/**
 * SAMPLE BY buckets: {@code count} units aligned to the Unix epoch, UTC, as QuestDB aligns them to calendar. Units
 * {@code U} (microseconds), {@code T} (milliseconds), {@code s}, {@code m}, {@code h}, {@code d} are fixed strides;
 * {@code M} and {@code y} are calendar months and years, counted from January 1970.
 */
final class AggBuckets {

    private static final long DAY_MICROS = 86_400_000_000L;

    private AggBuckets() {
    }

    /** The start of the bucket holding {@code tsMicros}. */
    static long floor(final long tsMicros, final long count, final String unit) {
        checkCount(count, unit);
        return switch (unit) {
            case "M" -> monthStart(Math.floorDiv(monthOf(tsMicros), count) * count);
            case "y" -> monthStart(Math.floorDiv(Math.floorDiv(monthOf(tsMicros), 12), count) * count * 12);
            default -> {
                final long stride = stride(count, unit);
                yield Math.floorDiv(tsMicros, stride) * stride;
            }
        };
    }

    /** The start of the bucket after the one starting at {@code bucketMicros}. */
    static long next(final long bucketMicros, final long count, final String unit) {
        return switch (unit) {
            case "M" -> monthStart(monthOf(bucketMicros) + count);
            case "y" -> monthStart(monthOf(bucketMicros) + count * 12);
            default -> Math.addExact(bucketMicros, stride(count, unit));
        };
    }

    /** Days since 1970-01-01 of a proleptic Gregorian date (H. Hinnant's {@code days_from_civil}). */
    static long daysFromCivil(final long year, final int month, final int day) {
        final long y = month <= 2 ? year - 1 : year;
        final long era = Math.floorDiv(y, 400);
        final long yoe = y - era * 400;
        final long doy = (153L * (month > 2 ? month - 3 : month + 9) + 2) / 5 + day - 1;
        final long doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
        return era * 146_097 + doe - 719_468;
    }

    /** Months since January 1970 of the date holding {@code tsMicros} (H. Hinnant's {@code civil_from_days}). */
    static long monthOf(final long tsMicros) {
        final long z = Math.floorDiv(tsMicros, DAY_MICROS) + 719_468;
        final long era = Math.floorDiv(z, 146_097);
        final long doe = z - era * 146_097;
        final long yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
        final long doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
        final long mp = (5 * doy + 2) / 153;
        final long month = mp < 10 ? mp + 3 : mp - 9;
        final long year = yoe + era * 400 + (month <= 2 ? 1 : 0);
        return (year - 1970) * 12 + month - 1;
    }

    private static void checkCount(final long count, final String unit) {
        if (count <= 0) {
            throw new CdbException("cannot sample by a non-positive stride [count=" + count + ", unit=" + unit + ']',
                    -1);
        }
    }

    private static long monthStart(final long month) {
        final long year = 1970 + Math.floorDiv(month, 12);
        final int monthOfYear = Math.floorMod(month, 12) + 1;
        return Math.multiplyExact(daysFromCivil(year, monthOfYear, 1), DAY_MICROS);
    }

    private static long stride(final long count, final String unit) {
        final long unitMicros = switch (unit) {
            case "U" -> 1L;
            case "T" -> 1_000L;
            case "s" -> 1_000_000L;
            case "m" -> 60_000_000L;
            case "h" -> 3_600_000_000L;
            case "d" -> DAY_MICROS;
            default -> throw new CdbException("cannot sample by an unknown unit [unit=" + unit + ']', -1);
        };
        try {
            return Math.multiplyExact(count, unitMicros);
        } catch (final ArithmeticException e) {
            throw new CdbException("cannot sample by a stride this long [count=" + count + ", unit=" + unit + ']', e);
        }
    }
}
