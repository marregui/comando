package dev.marregui.comando.cdb.store;

/** Which partitions a table's TTL drops. */
final class Retention {

    static final long HOUR_MICROS = 3_600_000_000L;
    static final long DAY_MICROS = 24L * HOUR_MICROS;
    static final long WEEK_MICROS = 7L * DAY_MICROS;

    // 1970-01-01 is a Thursday, three days after a Monday
    private static final long EPOCH_DAYS_AFTER_MONDAY = 3L;
    // the civil-date conversion counts days from 0000-03-01, 719,468 days before the epoch, in 400-year eras of
    // 146,097 days
    private static final long DAYS_TO_EPOCH = 719_468L;
    private static final long DAYS_PER_ERA = 146_097L;

    private Retention() {
    }

    /**
     * True when the partition starting at {@code lowerBound} lies wholly before {@code newestMicros - ttlMicros}, as
     * QuestDB's TTL drops it; never for a TTL of 0.
     */
    static boolean expired(final long lowerBound, final PartitionBy partitionBy, final long ttlMicros,
            final long newestMicros) {
        if (ttlMicros <= 0L || partitionBy == PartitionBy.NONE) {
            return false;
        }
        return next(lowerBound, partitionBy) <= newestMicros - ttlMicros;
    }

    /** The start of the partition holding {@code micros} under {@code partitionBy}, UTC; Long.MIN_VALUE for NONE. */
    static long floor(final long micros, final PartitionBy partitionBy) {
        return switch (partitionBy) {
            case NONE -> Long.MIN_VALUE;
            case HOUR -> Math.floorDiv(micros, HOUR_MICROS) * HOUR_MICROS;
            case DAY -> Math.floorDiv(micros, DAY_MICROS) * DAY_MICROS;
            case WEEK -> {
                final long days = Math.floorDiv(micros, DAY_MICROS);
                yield (days - Math.floorMod(days + EPOCH_DAYS_AFTER_MONDAY, 7L)) * DAY_MICROS;
            }
            case MONTH -> {
                final long civil = civil(Math.floorDiv(micros, DAY_MICROS));
                yield epochDay(civil / 100L, (int) (civil % 100L)) * DAY_MICROS;
            }
            case YEAR -> epochDay(civil(Math.floorDiv(micros, DAY_MICROS)) / 100L, 1) * DAY_MICROS;
        };
    }

    /** The start of the partition after the one starting at {@code lowerBound}; {@link Long#MAX_VALUE} for NONE. */
    static long next(final long lowerBound, final PartitionBy partitionBy) {
        return switch (partitionBy) {
            case NONE -> Long.MAX_VALUE;
            case HOUR -> lowerBound + HOUR_MICROS;
            case DAY -> lowerBound + DAY_MICROS;
            case WEEK -> lowerBound + WEEK_MICROS;
            case MONTH -> {
                final long civil = civil(Math.floorDiv(lowerBound, DAY_MICROS));
                final long year = civil / 100L;
                final int month = (int) (civil % 100L);
                yield (month == 12 ? epochDay(year + 1L, 1) : epochDay(year, month + 1)) * DAY_MICROS;
            }
            case YEAR -> epochDay(civil(Math.floorDiv(lowerBound, DAY_MICROS)) / 100L + 1L, 1) * DAY_MICROS;
        };
    }

    /** The year and month of {@code epochDay}, as {@code year * 100 + month}. */
    private static long civil(final long epochDay) {
        final long z = epochDay + DAYS_TO_EPOCH;
        final long era = Math.floorDiv(z, DAYS_PER_ERA);
        final long dayOfEra = z - era * DAYS_PER_ERA;
        final long yearOfEra = (dayOfEra - dayOfEra / 1_460L + dayOfEra / 36_524L - dayOfEra / 146_096L) / 365L;
        final long dayOfYear = dayOfEra - (365L * yearOfEra + yearOfEra / 4L - yearOfEra / 100L);
        final long shiftedMonth = (5L * dayOfYear + 2L) / 153L;
        final long month = shiftedMonth < 10L ? shiftedMonth + 3L : shiftedMonth - 9L;
        final long year = yearOfEra + era * 400L + (month <= 2L ? 1L : 0L);
        return year * 100L + month;
    }

    /** The epoch day of the first day of {@code month} of {@code year}. */
    private static long epochDay(final long year, final int month) {
        final long y = month <= 2 ? year - 1L : year;
        final long era = Math.floorDiv(y, 400L);
        final long yearOfEra = y - era * 400L;
        final long dayOfYear = (153L * (month > 2 ? month - 3 : month + 9) + 2L) / 5L;
        final long dayOfEra = yearOfEra * 365L + yearOfEra / 4L - yearOfEra / 100L + dayOfYear;
        return era * DAYS_PER_ERA + dayOfEra - DAYS_TO_EPOCH;
    }
}
