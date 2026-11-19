package dev.marregui.comando.cdb.store;

import org.junit.jupiter.api.Test;

import static dev.marregui.comando.cdb.store.StoreRows.micros;
import static org.assertj.core.api.Assertions.assertThat;

/// [Retention] floors timestamps to their partition's start, UTC, and expires a partition once it ends a TTL or more
/// before the newest row or now, whichever is earlier.
class RetentionTest {

    /// Each partitioning floors to its start: the hour, the day, the ISO week's Monday, the month's first, January 1st;
    /// before the epoch too.
    @Test
    void floorsToThePartitionStart() {
        final long t = micros("2026-11-14T13:45:10Z");
        assertThat(Retention.floor(t, PartitionBy.HOUR)).isEqualTo(micros("2026-11-14T13:00:00Z"));
        assertThat(Retention.floor(t, PartitionBy.DAY)).isEqualTo(micros("2026-11-14T00:00:00Z"));
        assertThat(Retention.floor(t, PartitionBy.WEEK)).isEqualTo(micros("2026-11-09T00:00:00Z"));
        assertThat(Retention.floor(t, PartitionBy.MONTH)).isEqualTo(micros("2026-11-01T00:00:00Z"));
        assertThat(Retention.floor(t, PartitionBy.YEAR)).isEqualTo(micros("2026-01-01T00:00:00Z"));
        assertThat(Retention.floor(t, PartitionBy.NONE)).isEqualTo(Long.MIN_VALUE);
        final long before = micros("1969-12-31T23:59:59Z");
        assertThat(Retention.floor(before, PartitionBy.DAY)).isEqualTo(micros("1969-12-31T00:00:00Z"));
        assertThat(Retention.floor(before, PartitionBy.WEEK)).isEqualTo(micros("1969-12-29T00:00:00Z"));
        assertThat(Retention.floor(micros("2024-02-29T23:00:00Z"), PartitionBy.MONTH))
                .isEqualTo(micros("2024-02-01T00:00:00Z"));
    }

    /// The next partition's start crosses month and year ends.
    @Test
    void nextCrossesMonthAndYearEnds() {
        assertThat(Retention.next(micros("2026-12-01T00:00:00Z"), PartitionBy.MONTH))
                .isEqualTo(micros("2027-01-01T00:00:00Z"));
        assertThat(Retention.next(micros("2024-02-01T00:00:00Z"), PartitionBy.MONTH))
                .isEqualTo(micros("2024-03-01T00:00:00Z"));
        assertThat(Retention.next(micros("2026-01-01T00:00:00Z"), PartitionBy.YEAR))
                .isEqualTo(micros("2027-01-01T00:00:00Z"));
    }

    /// A partition expires when its end is at or before the newest row minus the TTL; a TTL of 0 never expires.
    @Test
    void expiresAPartitionWhoseEndIsATtlBeforeTheNewest() {
        final long day = micros("2026-11-01T00:00:00Z");
        final long ttl = Retention.DAY_MICROS;
        assertThat(Retention.expired(day, PartitionBy.DAY, ttl, micros("2026-11-03T00:00:00Z"))).isTrue();
        assertThat(Retention.expired(day, PartitionBy.DAY, ttl, micros("2026-11-03T00:00:00Z") - 1L)).isFalse();
        assertThat(Retention.expired(day, PartitionBy.DAY, 0L, Long.MAX_VALUE)).isFalse();
        assertThat(Retention.expired(Long.MIN_VALUE, PartitionBy.NONE, ttl, Long.MAX_VALUE)).isFalse();
    }

    /// A negative TTL is a count of calendar months from the partition's end, counted as `datediff('M')` counts them,
    /// and never expires a partition that ends after the reference.
    @Test
    void aMonthTtlCountsCalendarMonths() {
        final long jan1 = micros("2026-01-01T00:00:00Z");
        assertThat(Retention.expired(jan1, PartitionBy.DAY, -1L, micros("2026-02-01T12:00:00Z"))).isFalse();
        assertThat(Retention.expired(jan1, PartitionBy.DAY, -1L, micros("2026-02-02T00:00:00Z"))).isTrue();
        final long jan31 = micros("2026-01-31T00:00:00Z");
        assertThat(Retention.expired(jan31, PartitionBy.DAY, -1L, micros("2026-02-28T23:59:59Z"))).isFalse();
        assertThat(Retention.expired(jan31, PartitionBy.DAY, -1L, micros("2026-03-01T00:00:00Z"))).isTrue();
        assertThat(Retention.expired(micros("2026-03-01T00:00:00Z"), PartitionBy.DAY, -1L,
                micros("2026-02-01T00:00:00Z"))).isFalse();
        final long january = micros("2025-01-01T00:00:00Z");
        assertThat(Retention.expired(january, PartitionBy.MONTH, -12L, micros("2026-01-31T23:00:00Z"))).isFalse();
        assertThat(Retention.expired(january, PartitionBy.MONTH, -12L, micros("2026-02-01T00:00:00Z"))).isTrue();
    }

    /// The reference is the newest row, or now when that row lies ahead of now.
    @Test
    void theReferenceIsNeverAfterNow() {
        final long before = System.currentTimeMillis() * 1_000L;
        assertThat(Retention.referenceMicros(0L)).isZero();
        // the clock reads microseconds, so the bound after is the end of the millisecond it reads
        assertThat(Retention.referenceMicros(Long.MAX_VALUE))
                .isBetween(before, (System.currentTimeMillis() + 1L) * 1_000L);
    }
}
