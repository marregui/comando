package dev.marregui.comando.cdb.store;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// The applier's interval per table: it starts at 1 s, halves while readers wait down to 100 ms, doubles while a
/// batch takes more than half of it up to 5 s, and otherwise drifts back toward 1 s; a table is due by the age of its
/// oldest queued commit, compared by subtraction so a `nanoTime` past `Long.MAX_VALUE` still counts right.
class ApplyScheduleTest {

    private static final long MILLI = TimeUnit.MILLISECONDS.toNanos(1L);

    /// A fresh schedule's interval is the 1 s base, between the 100 ms and 5 s bounds.
    @Test
    void startsAtOneSecond() {
        final ApplySchedule schedule = new ApplySchedule();
        assertThat(schedule.intervalNanos()).isEqualTo(TimeUnit.SECONDS.toNanos(1L))
                .isEqualTo(ApplySchedule.BASE_NANOS);
        assertThat(ApplySchedule.MIN_NANOS).isEqualTo(100L * MILLI);
        assertThat(ApplySchedule.MAX_NANOS).isEqualTo(TimeUnit.SECONDS.toNanos(5L));
    }

    /// Each batch a reader waited for halves the interval, whatever the batch took, and it stops at 100 ms.
    @Test
    void halvesOnDemandDownToOneHundredMillis() {
        final ApplySchedule schedule = new ApplySchedule();
        schedule.applied(0L, true);
        assertThat(schedule.intervalNanos()).isEqualTo(500L * MILLI);
        // a slow batch still halves when a reader waited for it
        schedule.applied(TimeUnit.SECONDS.toNanos(10L), true);
        assertThat(schedule.intervalNanos()).isEqualTo(250L * MILLI);
        schedule.applied(0L, true);
        assertThat(schedule.intervalNanos()).isEqualTo(125L * MILLI);
        schedule.applied(0L, true);
        assertThat(schedule.intervalNanos()).isEqualTo(ApplySchedule.MIN_NANOS);
        for (int i = 0; i < 10; i++) {
            schedule.applied(0L, true);
        }
        assertThat(schedule.intervalNanos()).isEqualTo(ApplySchedule.MIN_NANOS);
    }

    /// A batch that took more than half the interval doubles it, up to 5 s; one that took exactly half does not.
    @Test
    void doublesOnSlowBatchesUpToFiveSeconds() {
        final ApplySchedule schedule = new ApplySchedule();
        schedule.applied(500L * MILLI + 1L, false);
        assertThat(schedule.intervalNanos()).isEqualTo(2_000L * MILLI);
        schedule.applied(1_000L * MILLI + 1L, false);
        assertThat(schedule.intervalNanos()).isEqualTo(4_000L * MILLI);
        schedule.applied(2_000L * MILLI + 1L, false);
        assertThat(schedule.intervalNanos()).isEqualTo(ApplySchedule.MAX_NANOS);
        schedule.applied(TimeUnit.SECONDS.toNanos(60L), false);
        assertThat(schedule.intervalNanos()).isEqualTo(ApplySchedule.MAX_NANOS);

        final ApplySchedule half = new ApplySchedule();
        half.applied(500L * MILLI, false);
        assertThat(half.intervalNanos()).as("a batch of exactly half the interval drifts, not doubles")
                .isEqualTo(ApplySchedule.BASE_NANOS);
    }

    /// A failed batch doubles the interval, up to 5 s: the retries back off.
    @Test
    void failuresBackOffUpToFiveSeconds() {
        final ApplySchedule schedule = new ApplySchedule();
        schedule.failed();
        assertThat(schedule.intervalNanos()).isEqualTo(2_000L * MILLI);
        schedule.failed();
        assertThat(schedule.intervalNanos()).isEqualTo(4_000L * MILLI);
        schedule.failed();
        assertThat(schedule.intervalNanos()).isEqualTo(ApplySchedule.MAX_NANOS);
    }

    /// Quick batches nobody waited for draw the interval a quarter of the way back to 1 s each time, from either
    /// bound, never past it; it ends within a few nanoseconds of the base.
    @Test
    void driftsBackTowardOneSecond() {
        final ApplySchedule high = new ApplySchedule();
        for (int i = 0; i < 3; i++) {
            high.failed();
        }
        assertThat(high.intervalNanos()).isEqualTo(ApplySchedule.MAX_NANOS);
        high.applied(0L, false);
        assertThat(high.intervalNanos()).isEqualTo(4_000L * MILLI);
        high.applied(0L, false);
        assertThat(high.intervalNanos()).isEqualTo(3_250L * MILLI);
        long previous = high.intervalNanos();
        for (int i = 0; i < 200; i++) {
            high.applied(0L, false);
            assertThat(high.intervalNanos()).isLessThanOrEqualTo(previous)
                    .isGreaterThanOrEqualTo(ApplySchedule.BASE_NANOS);
            previous = high.intervalNanos();
        }
        assertThat(high.intervalNanos() - ApplySchedule.BASE_NANOS).isBetween(0L, 3L);

        final ApplySchedule low = new ApplySchedule();
        for (int i = 0; i < 5; i++) {
            low.applied(0L, true);
        }
        assertThat(low.intervalNanos()).isEqualTo(ApplySchedule.MIN_NANOS);
        low.applied(0L, false);
        assertThat(low.intervalNanos()).isEqualTo(325L * MILLI);
        previous = low.intervalNanos();
        for (int i = 0; i < 200; i++) {
            low.applied(0L, false);
            assertThat(low.intervalNanos()).isGreaterThanOrEqualTo(previous)
                    .isLessThanOrEqualTo(ApplySchedule.BASE_NANOS);
            previous = low.intervalNanos();
        }
        assertThat(ApplySchedule.BASE_NANOS - low.intervalNanos()).isBetween(0L, 3L);
    }

    /// A table is due once its oldest commit is as old as the interval, or at once when a reader waits or the store's
    /// queued bytes press; until then `untilDueNanos` is the rest of the interval, and 0 once due.
    @Test
    void dueByTheOldestCommitsAge() {
        final ApplySchedule schedule = new ApplySchedule();
        final long intervalNanos = schedule.intervalNanos();
        final long oldestNanos = 1_000_000L;
        assertThat(schedule.due(oldestNanos, oldestNanos, false, false)).isFalse();
        assertThat(schedule.untilDueNanos(oldestNanos, oldestNanos)).isEqualTo(intervalNanos);
        assertThat(schedule.due(oldestNanos + intervalNanos - 1L, oldestNanos, false, false)).isFalse();
        assertThat(schedule.untilDueNanos(oldestNanos + intervalNanos - 1L, oldestNanos)).isEqualTo(1L);
        assertThat(schedule.due(oldestNanos + intervalNanos, oldestNanos, false, false)).isTrue();
        assertThat(schedule.untilDueNanos(oldestNanos + intervalNanos, oldestNanos)).isZero();
        assertThat(schedule.untilDueNanos(oldestNanos + 10L * intervalNanos, oldestNanos)).isZero();
        assertThat(schedule.due(oldestNanos, oldestNanos, true, false)).as("a reader waits").isTrue();
        assertThat(schedule.due(oldestNanos, oldestNanos, false, true)).as("queued bytes press").isTrue();
    }

    /// With the oldest commit queued just before `nanoTime` wraps past `Long.MAX_VALUE`, the age is still the
    /// difference: due after the interval, not before, and the time left counts down through the wrap.
    @Test
    void dueBySubtractionAcrossTheNanoTimeWrap() {
        final ApplySchedule schedule = new ApplySchedule();
        final long intervalNanos = schedule.intervalNanos();
        final long oldestNanos = Long.MAX_VALUE - intervalNanos / 2L;
        final long beforeDueNanos = oldestNanos + intervalNanos - 1L;
        final long atDueNanos = oldestNanos + intervalNanos;
        assertThat(beforeDueNanos).as("the clock has wrapped").isNegative();
        assertThat(schedule.due(beforeDueNanos, oldestNanos, false, false)).isFalse();
        assertThat(schedule.untilDueNanos(beforeDueNanos, oldestNanos)).isEqualTo(1L);
        assertThat(schedule.due(atDueNanos, oldestNanos, false, false)).isTrue();
        assertThat(schedule.untilDueNanos(atDueNanos, oldestNanos)).isZero();
        final long halfwayNanos = oldestNanos + intervalNanos / 4L;
        assertThat(schedule.due(halfwayNanos, oldestNanos, false, false)).isFalse();
        assertThat(schedule.untilDueNanos(halfwayNanos, oldestNanos)).isEqualTo(intervalNanos - intervalNanos / 4L);
    }
}
