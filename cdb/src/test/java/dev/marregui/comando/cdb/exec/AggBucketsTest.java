package dev.marregui.comando.cdb.exec;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.cdb.CdbException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// [AggBuckets] floors a UTC timestamp to the start of its calendar-aligned bucket, months and years counted from
/// 1970-01, the rest in fixed strides from the epoch. Expected instants come from `java.time`; the 7m, 3d, 2M and 5y
/// floors of 2023-11-14T22:13:20Z match QuestDB's `SAMPLE BY` over one row at `1700000000000000`.
class AggBucketsTest {

    /// Month and year buckets start on the first of their month, a stride of months counted from 1970-01.
    @Test
    void monthsAndYearsAlignToTheCalendar() {
        final long ts = micros("2023-11-14T22:13:20Z");
        assertThat(AggBuckets.floor(ts, 1, "M")).isEqualTo(micros("2023-11-01T00:00:00Z"));
        assertThat(AggBuckets.floor(ts, 2, "M")).isEqualTo(micros("2023-11-01T00:00:00Z"));
        assertThat(AggBuckets.floor(micros("2023-10-14T00:00:00Z"), 2, "M")).isEqualTo(micros("2023-09-01T00:00:00Z"));
        assertThat(AggBuckets.floor(ts, 1, "y")).isEqualTo(micros("2023-01-01T00:00:00Z"));
        assertThat(AggBuckets.floor(ts, 5, "y")).isEqualTo(micros("2020-01-01T00:00:00Z"));
    }

    /// Day, hour and minute buckets are fixed strides from the epoch.
    @Test
    void fixedStridesCountFromTheEpoch() {
        final long ts = micros("2023-11-14T22:13:20Z");
        assertThat(AggBuckets.floor(ts, 3, "d")).isEqualTo(micros("2023-11-13T00:00:00Z"));
        assertThat(AggBuckets.floor(ts, 7, "m")).isEqualTo(micros("2023-11-14T22:13:00Z"));
        assertThat(AggBuckets.floor(ts, 1, "s")).isEqualTo(ts);
        assertThat(AggBuckets.floor(ts + 999, 1, "T")).isEqualTo(ts);
        assertThat(AggBuckets.floor(ts + 7, 5, "U")).isEqualTo(ts + 5);
    }

    /// The next bucket of a month or year stride crosses month lengths and year ends.
    @Test
    void nextCrossesMonthLengthsAndYearEnds() {
        assertThat(AggBuckets.next(micros("2023-12-01T00:00:00Z"), 1, "M")).isEqualTo(micros("2024-01-01T00:00:00Z"));
        assertThat(AggBuckets.next(micros("2024-01-01T00:00:00Z"), 2, "M")).isEqualTo(micros("2024-03-01T00:00:00Z"));
        assertThat(AggBuckets.next(micros("2023-01-01T00:00:00Z"), 1, "y")).isEqualTo(micros("2024-01-01T00:00:00Z"));
        assertThat(AggBuckets.next(micros("2023-11-14T00:00:00Z"), 1, "d")).isEqualTo(micros("2023-11-15T00:00:00Z"));
    }

    /// A timestamp before 1970 floors down, not toward zero.
    @Test
    void timestampsBefore1970FloorDown() {
        final long ts = micros("1969-12-31T23:59:59Z");
        assertThat(AggBuckets.floor(ts, 1, "d")).isEqualTo(micros("1969-12-31T00:00:00Z"));
        assertThat(AggBuckets.floor(ts, 1, "M")).isEqualTo(micros("1969-12-01T00:00:00Z"));
        assertThat(AggBuckets.floor(ts, 1, "y")).isEqualTo(micros("1969-01-01T00:00:00Z"));
        assertThat(AggBuckets.floor(ts, 1, "h")).isEqualTo(micros("1969-12-31T23:00:00Z"));
    }

    /// An unknown unit or a count below one fails.
    @Test
    void anUnknownUnitOrABadCountFails() {
        assertThatThrownBy(() -> AggBuckets.floor(0, 1, "w")).isInstanceOf(CdbException.class);
        assertThatThrownBy(() -> AggBuckets.floor(0, 0, "h")).isInstanceOf(CdbException.class);
    }

    private static long micros(final String instant) {
        return ChronoUnit.MICROS.between(Instant.EPOCH, Instant.parse(instant));
    }
}
