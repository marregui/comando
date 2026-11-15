package dev.marregui.comando.core.reduce;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.SessionEta;
import dev.marregui.comando.testkit.FakeClock;
import dev.marregui.comando.testkit.States;

import static org.assertj.core.api.Assertions.assertThat;

/// [Reducer] keeps a session's ETA, set by `session_eta`.
/// - each part is due its minutes after the intent, by the reducer's clock, which also stamps when it was set
/// - a part with no text or no minutes is cleared, and a later estimate replaces the earlier one whole
/// - the timebox's time given reads as today's `HH:mm` in UTC, yesterday's when still to come, an instant, or now
/// - a time given that does not read is refused, and leaves the estimate; an unknown session leaves the state
class ReducerEtaTest {

    /// 2026-11-15 12:00:00 UTC.
    private static final long NOON_MILLIS = 1_794_744_000_000L;
    private static final long MINUTE_MILLIS = 60_000L;

    private final FakeClock clock = new FakeClock(NOON_MILLIS);
    private final Reducer reducer = new Reducer(clock);

    /// `work` estimates 5 minutes for `build` and 20 for `shipped`, in a 30-minute box given at 11:50; a minute later,
    /// `test` in 3 with no goal text and no box; then 0 minutes for both, which clears them. An estimate for a session
    /// that does not exist changes nothing.
    @Test
    void anEstimateIsDueItsMinutesFromNowAndTheNextReplacesIt() {
        AppState s = reducer.apply(States.twoPanels(), new Intent.SessionEta("work", " build ", 5, "shipped", 20, 30, "11:50")).state();
        assertThat(s.session("work").orElseThrow().eta()).isEqualTo(new SessionEta("build", NOON_MILLIS + 5 * MINUTE_MILLIS,
                "shipped", NOON_MILLIS + 20 * MINUTE_MILLIS, 30, NOON_MILLIS - 10 * MINUTE_MILLIS, NOON_MILLIS));

        clock.advance(MINUTE_MILLIS);
        s = reducer.apply(s, new Intent.SessionEta("work", "test", 3, "", 20, 0, "")).state();
        assertThat(s.session("work").orElseThrow().eta()).as("no goal text, no box: both cleared")
                .isEqualTo(new SessionEta("test", NOON_MILLIS + 4 * MINUTE_MILLIS, "", 0L, 0, 0L, NOON_MILLIS + MINUTE_MILLIS));

        s = reducer.apply(s, new Intent.SessionEta("work", "test", 0, "", 0, 0, "")).state();
        assertThat(s.session("work").orElseThrow().eta().stepAtMillis()).as("0 minutes clears").isZero();

        final AppState before = s;
        assertThat(reducer.apply(before, new Intent.SessionEta("nobody", "x", 1, "", 0, 0, "")).state()).isEqualTo(before);
    }

    /// At 12:00 UTC: "11:50" is today's, "12:30" yesterday's, an instant is itself, empty is now; "noonish" is refused
    /// with the reason, and the estimate stays as it was.
    @Test
    void theTimeTheBoxWasGivenReadsAsTheLastSuchTimeInUtc() {
        assertThat(given("11:50")).isEqualTo(NOON_MILLIS - 10 * MINUTE_MILLIS);
        assertThat(given("12:30")).as("still to come today: yesterday's").isEqualTo(NOON_MILLIS + 30 * MINUTE_MILLIS - 86_400_000L);
        assertThat(given("2026-11-15T11:00:00Z")).isEqualTo(NOON_MILLIS - 60 * MINUTE_MILLIS);
        assertThat(given("")).isEqualTo(NOON_MILLIS);

        final Intent.SessionEta bad = new Intent.SessionEta("work", "build", 5, "", 0, 30, "noonish");
        assertThat(Reducer.refusal(States.twoPanels(), bad)).hasValueSatisfying(r -> assertThat(r).contains("noonish"));
        assertThat(reducer.apply(States.twoPanels(), bad).state()).isEqualTo(States.twoPanels());
    }

    private long given(final String text) {
        return reducer.apply(States.twoPanels(), new Intent.SessionEta("work", "", 0, "", 0, 30, text)).state()
                .session("work").orElseThrow().eta().boxGivenAtMillis();
    }
}
