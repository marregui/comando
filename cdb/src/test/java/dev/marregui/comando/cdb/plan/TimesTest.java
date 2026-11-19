package dev.marregui.comando.cdb.plan;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.cdb.CdbException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Timestamp literals as the kit writes them: Python's `isoformat()` ends a time in `+00:00`, and QuestDB reads that
/// offset (`select '2026-11-10T23:07:49.418259+00:00'::timestamp` gives `2026-11-10T23:07:49.418259Z`).
class TimesTest {

    /// `+00:00` is UTC, `+01:00` an hour ahead of it, `-02:30` behind; `Z` and no suffix stay UTC.
    @Test
    void anOffsetMovesTheInstantToUtc() {
        final long utc = micros("2026-11-10T23:07:49.418259Z");
        assertThat(Times.parse("2026-11-10T23:07:49.418259+00:00", 0)).isEqualTo(utc);
        assertThat(Times.parse("2026-11-11T00:07:49.418259+01:00", 0)).isEqualTo(utc);
        assertThat(Times.parse("2026-11-10T20:37:49.418259-02:30", 0)).isEqualTo(utc);
        assertThat(Times.parse("2026-11-10T23:07:49.418259Z", 0)).isEqualTo(utc);
        assertThat(Times.parse("2026-11-10T23:07:49.418259", 0)).isEqualTo(utc);
    }

    /// A date alone takes no offset (its dashes are no zone), and an offset out of range is refused.
    @Test
    void aDateHasNoZoneAndABadOffsetIsRefused() {
        assertThat(Times.interval("2026-11-10", 0)).containsExactly(micros("2026-11-10T00:00:00Z"),
                micros("2026-11-11T00:00:00Z"));
        assertThatThrownBy(() -> Times.parse("2026-11-10T23:07:49+24:00", 7)).isInstanceOf(CdbException.class);
    }

    /// A fraction covers its own precision, as QuestDB's `parseInterval` reads it: `.123` the millisecond, `.1234`
    /// 100 microseconds, six digits one microsecond, and no fraction the whole second.
    @Test
    void aFractionCoversItsPrecision() {
        final long lo = micros("2026-11-14T10:00:00.123Z");
        assertThat(Times.interval("2026-11-14T10:00:00.123Z", 0)).containsExactly(lo, lo + 1_000L);
        assertThat(Times.interval("2026-11-14T10:00:00.1Z", 0)).containsExactly(lo - 23_000L, lo + 77_000L);
        assertThat(Times.interval("2026-11-14T10:00:00.1234Z", 0)).containsExactly(lo + 400L, lo + 500L);
        assertThat(Times.interval("2026-11-14T10:00:00.123456Z", 0)).containsExactly(lo + 456L, lo + 457L);
        assertThat(Times.interval("2026-11-14T10:00:00Z", 0)).containsExactly(lo - 123_000L, lo + 877_000L);
    }

    private static long micros(final String instant) {
        final Instant i = Instant.parse(instant);
        return i.getEpochSecond() * 1_000_000L + i.getNano() / 1_000L;
    }

    /// HOUR, DAY and WEEK are kept in microseconds, MONTH and YEAR as a negative count of months; a negative amount,
    /// or one past a long, is refused as a user error.
    @Test
    void aTtlKeepsMonthsApartFromSpans() {
        assertThat(Times.ttlMicrosOrMonths("30 DAYS", 0)).isEqualTo(30L * 86_400_000_000L);
        assertThat(Times.ttlMicrosOrMonths("2w", 0)).isEqualTo(14L * 86_400_000_000L);
        assertThat(Times.ttlMicrosOrMonths("1 MONTH", 0)).isEqualTo(-1L);
        assertThat(Times.ttlMicrosOrMonths("2 YEARS", 0)).isEqualTo(-24L);
        assertThat(Times.ttlMicrosOrMonths("0 HOURS", 0)).isZero();
        assertThatThrownBy(() -> Times.ttlMicrosOrMonths("-1 DAY", 4)).isInstanceOf(CdbException.class);
        assertThatThrownBy(() -> Times.ttlMicrosOrMonths("99999999999999 YEARS", 4)).isInstanceOf(CdbException.class);
        assertThatThrownBy(() -> Times.ttlMicrosOrMonths("9999999999999999 HOURS", 4))
                .isInstanceOf(CdbException.class);
    }

    /// A year or month added past the calendar's range is a user error, not a JDK exception.
    @Test
    void aDateaddPastTheCalendarIsRefused() {
        assertThat(Times.dateadd("M", 1, micros("2026-01-31T10:00:00.5Z"), 0))
                .isEqualTo(micros("2026-02-28T10:00:00.5Z"));
        assertThatThrownBy(() -> Times.dateadd("y", 1_000_000_000L, 0L, 7)).isInstanceOf(CdbException.class);
        assertThatThrownBy(() -> Times.dateadd("M", Long.MAX_VALUE, 0L, 7)).isInstanceOf(CdbException.class);
    }
}
