package dev.marregui.comando.cdb.store;

import java.time.Instant;

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
     * True when the partition starting at {@code lowerBound} ends at least the TTL before {@code referenceMicros}, as
     * QuestDB's {@code isOlderThanTtl} drops it: a positive {@code ttlMicrosOrMonths} is a span in microseconds, a
     * negative one a count of whole calendar months, counted as {@code datediff('M')} counts them; never for 0, nor for
     * a partition that ends after the reference.
     */
    static boolean expired(final long lowerBound, final PartitionBy partitionBy, final long ttlMicrosOrMonths,
            final long referenceMicros) {
        if (ttlMicrosOrMonths == 0L || partitionBy == PartitionBy.NONE) {
            return false;
        }
        final long ceilingMicros = next(lowerBound, partitionBy);
        if (ttlMicrosOrMonths > 0L) {
            return ceilingMicros <= referenceMicros - ttlMicrosOrMonths;
        }
        // QuestDB's month count is symmetric and its loop stops at the first partition kept; cdb tests every
        // partition, so one ending after the reference must not count its distance the other way
        return ceilingMicros <= referenceMicros && monthsBetween(ceilingMicros, referenceMicros) >= -ttlMicrosOrMonths;
    }

    /**
     * The instant a TTL counts back from when the newest row is at {@code newestMicros}: that row, or now when it lies
     * in the future, as QuestDB 9.2.3 and later count ({@code cairo.ttl.use.wall.clock}), so one row stamped ahead
     * does not expire the history before it.
     */
    static long referenceMicros(final long newestMicros) {
        final Instant now = Instant.now();
        return Math.min(newestMicros, now.getEpochSecond() * 1_000_000L + now.getNano() / 1_000);
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

    // whole months from loMicros to hiMicros, loMicros <= hiMicros: the difference of their months, less one when
    // loMicros lies further into its month than hiMicros does into its own
    private static long monthsBetween(final long loMicros, final long hiMicros) {
        final long loMonth = civil(Math.floorDiv(loMicros, DAY_MICROS));
        final long hiMonth = civil(Math.floorDiv(hiMicros, DAY_MICROS));
        final long months = (hiMonth / 100L - loMonth / 100L) * 12L + hiMonth % 100L - loMonth % 100L;
        final long loIntoMicros = loMicros - floor(loMicros, PartitionBy.MONTH);
        final long hiIntoMicros = hiMicros - floor(hiMicros, PartitionBy.MONTH);
        return loIntoMicros > hiIntoMicros ? months - 1L : months;
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
