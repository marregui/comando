package dev.marregui.comando.core.reduce;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.SessionEta;
import dev.marregui.comando.testkit.FakeClock;
import dev.marregui.comando.testkit.States;

import static org.assertj.core.api.Assertions.assertThat;

/// [Reducer] keeps a session's ETA, set by `session_eta`.
/// - each part is due its minutes after the intent, by the reducer's clock
/// - a part with no text or no minutes is cleared, and a later estimate replaces the earlier one whole
/// - an unknown session leaves the state as it was
class ReducerEtaTest {

    /// An arbitrary start, so the times are not 0, which means "no estimate".
    private final FakeClock clock = new FakeClock(1_000_000L);
    private final Reducer reducer = new Reducer(clock);

    /// `work` estimates 5 minutes for `build` and 20 for `shipped`; a minute later, `test` in 3 with no goal text; then
    /// 0 minutes for both, which clears them. An estimate for a session that does not exist changes nothing.
    @Test
    void anEstimateIsDueItsMinutesFromNowAndTheNextReplacesIt() {
        AppState s = reducer.apply(States.twoPanels(), new Intent.SessionEta("work", " build ", 5, "shipped", 20)).state();
        assertThat(s.session("work").orElseThrow().eta())
                .isEqualTo(new SessionEta("build", 1_000_000L + 300_000L, "shipped", 1_000_000L + 1_200_000L));

        clock.advance(60_000L);
        s = reducer.apply(s, new Intent.SessionEta("work", "test", 3, "", 20)).state();
        assertThat(s.session("work").orElseThrow().eta()).as("no goal text: the goal is cleared")
                .isEqualTo(new SessionEta("test", 1_060_000L + 180_000L, "", 0L));

        s = reducer.apply(s, new Intent.SessionEta("work", "test", 0, "", 0)).state();
        assertThat(s.session("work").orElseThrow().eta()).as("0 minutes clears").isEqualTo(SessionEta.NONE);

        final AppState before = s;
        assertThat(reducer.apply(before, new Intent.SessionEta("nobody", "x", 1, "", 0)).state()).isEqualTo(before);
    }
}
