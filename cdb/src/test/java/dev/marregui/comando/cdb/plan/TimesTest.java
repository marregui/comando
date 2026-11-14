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

    private static long micros(final String instant) {
        final Instant i = Instant.parse(instant);
        return i.getEpochSecond() * 1_000_000L + i.getNano() / 1_000L;
    }
}
