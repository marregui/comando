package dev.marregui.comando.core.reduce;

import dev.marregui.comando.core.effect.Effect;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.core.state.SessionInfo;
import dev.marregui.comando.testkit.FakeClock;
import dev.marregui.comando.testkit.States;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// [Reducer] opens a session's child conversation only when that session has a recorded conversation identifier.
///
/// - an initialised session forwards its conversation id
/// - missing and unknown sessions receive an informational notice
class ReducerSubagentTest {

    private final Reducer reducer = new Reducer(new FakeClock());
    private AppState state = States.twoPanels();
    private List<Effect> effects = List.of();

    private void apply(Intent intent) {
        Transition t = reducer.apply(state, intent);
        state = t.state();
        effects = t.effects();
    }

    /// An initialised `work` session routes child conversation `toolu_7` to the display effect with conversation
    /// `conv-1`.
    @Test
    void openingASubagentAsksTheAdapterWithTheSessionsConversation() {
        apply(new Intent.StartSession("work"));
        apply(new Intent.SessionStarted("work"));
        apply(new Intent.SessionInit("work", new SessionInfo("conv-1", "opus", "/w", "2.1", "", List.of())));

        apply(new Intent.OpenSubagent("work", "toolu_7"));

        assertThat(effects).containsExactly(new Effect.ShowSubagent("work", "conv-1", "toolu_7"));
    }

    /// A known session without an initialised conversation produces no display effect and explains why.
    @Test
    void aSessionWithoutAConversationHasNoSubagentToShow() {
        apply(new Intent.OpenSubagent("work", "toolu_7"));

        assertThat(effects).isEmpty();
        assertThat(state.notice().level()).isEqualTo(Notice.Level.INFO);
        assertThat(state.notice().text()).isEqualTo("Session work: the session has no conversation yet");
    }

    /// An unknown session produces no display effect and receives the same no-conversation outcome.
    @Test
    void anUnknownSessionHasNoSubagentToShow() {
        apply(new Intent.OpenSubagent("gone", "toolu_7"));

        assertThat(effects).isEmpty();
        assertThat(state.notice().text()).contains("no conversation yet");
    }
}
