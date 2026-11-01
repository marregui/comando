package dev.marregui.comando.core.reduce;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.core.effect.Effect;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.testkit.FakeClock;
import dev.marregui.comando.testkit.States;

import static org.assertj.core.api.Assertions.assertThat;

/// [Reducer] and [AppState] keep a session's creation family (record 121, rule 1: a card is never answered by its
/// asker's family): the session, its chain of creators, and every session it or any of them made.
///
/// - siblings and cousins stay one family when the user removes the session that made them, and the family is saved
/// - an edit keeps the recorded family; a new session never takes one from the intent that makes it
/// - the walk up the creators follows every link, however deep, and ends on a loop
class ReducerFamilyTest {

    private final Reducer reducer = new Reducer(new FakeClock());
    private AppState state = States.twoPanels();
    private List<Effect> effects = List.of();

    private void apply(final Intent intent) {
        final Transition t = reducer.apply(state, intent);
        state = t.state();
        effects = t.effects();
    }

    private SessionSpec spec(final String name) {
        return state.session(name).orElseThrow().spec();
    }

    /// The user's `lead` made `b` and `c`, and `b` made `d`; the user removes `lead`. `b`, `c` and `d` are still one
    /// family, so none answers another's card, while the user's own `work` is of none of theirs; the sessions saved carry
    /// the family. A session `c` makes afterwards joins it.
    @Test
    void siblingsAndCousinsStayOneFamilyWhenTheUserRemovesTheirCreator() {
        apply(new Intent.SaveSession("", SessionSpec.fresh("lead", "/tmp")));
        apply(new Intent.SaveSession("", SessionSpec.fresh("b", "/tmp").withCreatedBy("lead")));
        apply(new Intent.SaveSession("", SessionSpec.fresh("c", "/tmp").withCreatedBy("lead")));
        apply(new Intent.SaveSession("", SessionSpec.fresh("d", "/tmp").withCreatedBy("b")));
        assertThat(List.of(state.sameFamily("b", "c"), state.sameFamily("d", "c"))).as("before the removal").containsOnly(true);

        apply(new Intent.RemoveSession("lead"));

        assertThat(spec("b").createdBy()).as("no creator left").isEmpty();
        assertThat(List.of(state.sameFamily("b", "c"), state.sameFamily("c", "d"), state.sameFamily("d", "b")))
                .as("after the user removed their creator").containsOnly(true);
        assertThat(state.sameFamily("b", "work")).as("the user's own session").isFalse();
        assertThat(effects).filteredOn(Effect.SaveSessions.class::isInstance).singleElement()
                .satisfies(e -> assertThat(((Effect.SaveSessions) e).specs()).filteredOn(x -> x.name().equals("c"))
                        .extracting(SessionSpec::family).containsExactly("lead"));

        apply(new Intent.SaveSession("", SessionSpec.fresh("e", "/tmp").withCreatedBy("c")));
        assertThat(state.sameFamily("e", "b")).as("made by c afterwards").isTrue();
    }

    /// Sessions built straight into the state, with no family recorded, as a file from before the record was kept
    /// holds them: removing their creator records it then.
    @Test
    void sessionsFromBeforeTheFamilyWasKeptStayOneFamilyWhenTheirCreatorGoes() {
        final List<SessionState> sessions = new ArrayList<>(state.sessions());
        sessions.add(SessionState.closed(SessionSpec.fresh("lead", "/tmp")));
        sessions.add(SessionState.closed(SessionSpec.fresh("b", "/tmp").withCreatedBy("lead")));
        sessions.add(SessionState.closed(SessionSpec.fresh("c", "/tmp").withCreatedBy("lead")));
        state = state.withSessions(sessions);

        apply(new Intent.RemoveSession("lead"));

        assertThat(state.sameFamily("b", "c")).isTrue();
    }

    /// The form and the agents' edits carry no family: an edit, a rename too, keeps the one recorded. A new session
    /// takes none from its intent, so no caller can join a family, or leave one, by naming it.
    @Test
    void anEditKeepsTheFamilyAndANewSessionTakesNoneFromItsIntent() {
        apply(new Intent.SaveSession("", SessionSpec.fresh("lead", "/tmp")));
        apply(new Intent.SaveSession("", SessionSpec.fresh("b", "/tmp").withCreatedBy("lead")));
        apply(new Intent.SaveSession("", SessionSpec.fresh("c", "/tmp").withCreatedBy("lead")));
        apply(new Intent.RemoveSession("lead"));

        apply(new Intent.SaveSession("b", SessionSpec.fresh("b2", "/tmp").withModel("haiku")));
        assertThat(spec("b2").family()).as("renamed and edited").isEqualTo("lead");
        assertThat(state.sameFamily("b2", "c")).isTrue();

        apply(new Intent.SaveSession("", SessionSpec.fresh("solo", "/tmp").withFamily("lead")));
        assertThat(spec("solo").family()).as("taken from no intent").isEmpty();
        assertThat(state.sameFamily("solo", "c")).isFalse();
    }

    /// A chain of 40 sessions, each made by the one before, is one family from end to end: the walk follows every
    /// link to the root, with no depth at which it stops. A chain that loops ends where a name comes again.
    @Test
    void theWalkUpTheCreatorsFollowsEveryLinkAndEndsOnALoop() {
        final List<SessionState> sessions = new ArrayList<>(state.sessions());
        sessions.add(SessionState.closed(SessionSpec.fresh("s0", "/tmp")));
        for (int i = 1; i <= 40; i++) {
            sessions.add(SessionState.closed(SessionSpec.fresh("s" + i, "/tmp").withCreatedBy("s" + (i - 1))));
        }
        sessions.add(SessionState.closed(SessionSpec.fresh("x", "/tmp").withCreatedBy("y")));
        sessions.add(SessionState.closed(SessionSpec.fresh("y", "/tmp").withCreatedBy("x")));
        state = state.withSessions(sessions);

        assertThat(state.creators("s40")).hasSize(40).first().isEqualTo("s39");
        assertThat(state.creators("s40")).last().isEqualTo("s0");
        assertThat(state.sameFamily("s40", "s0")).as("40 links up").isTrue();
        assertThat(state.sameFamily("s40", "s1")).as("cousins far apart").isTrue();
        assertThat(state.sameFamily("s40", "work")).isFalse();

        assertThat(state.creators("x")).containsExactly("y", "x");
        assertThat(state.sameFamily("x", "y")).isTrue();
        assertThat(state.sameFamily("x", "s0")).isFalse();
    }
}
