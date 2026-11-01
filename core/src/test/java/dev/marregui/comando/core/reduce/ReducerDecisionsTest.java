package dev.marregui.comando.core.reduce;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.core.effect.Effect;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.Decision;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.LowerTab;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.testkit.FakeClock;
import dev.marregui.comando.testkit.States;

import static dev.marregui.comando.testkit.States.P2;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/// [Reducer] keeps the decisions automatic checkpoints held back (record 131): the Approvals tab's Later group.
///
/// - a decision held back is kept and saved, quietly, with an id never given before, across restarts too
/// - settled and dismissed decisions leave; decisions read at start replace the list unsaved
/// - Settle sends the checkpoint command without the automatic argument, to the decision's session or the focused one
/// - Settle is refused while the target session's checkpoint runs
class ReducerDecisionsTest {

    private final FakeClock clock = new FakeClock();
    private final Reducer reducer = new Reducer(clock);
    private AppState state = States.twoPanels();
    private List<Effect> effects = List.of();

    private void apply(final Intent intent) {
        final Transition t = reducer.apply(state, intent);
        state = t.state();
        effects = t.effects();
    }

    /// Two decisions held back get ids 1 and 2 and are saved; the Approvals tab does not come forward and no desktop
    /// notification goes out; a blank title is dropped.
    @Test
    void aDecisionHeldBackIsKeptAndSavedQuietly() {
        apply(new Intent.DecisionHeldBack("work", " Merge the two feedback entries? ", "both say SVG only"));
        apply(new Intent.DecisionHeldBack("work", "Archive the old lane?", null));
        assertThat(state.later()).extracting(Decision::id, Decision::session, Decision::title, Decision::detail)
                .containsExactly(tuple(1L, "work", "Merge the two feedback entries?", "both say SVG only"),
                        tuple(2L, "work", "Archive the old lane?", ""));
        assertThat(effects).containsExactly(new Effect.SaveDecisions(state.later(), state.nextId() - 1));
        assertThat(state.lowerTab()).isEqualTo(LowerTab.SESSION);

        apply(new Intent.DecisionHeldBack("work", "  ", "no title"));
        assertThat(state.later()).hasSize(2);
        assertThat(effects).isEmpty();
    }

    /// Ids stay unique across restarts: after the decisions read at start, the next one takes an id above both the
    /// highest kept and the highest given before the restart, which `decisions.json` keeps.
    @Test
    void decisionsReadAtStartReplaceTheListUnsavedAndIdsGoOnAfterThem() {
        apply(new Intent.DecisionsRead(List.of(new Decision(7, "work", "Old one", "", 1L))));
        assertThat(effects).isEmpty();
        apply(new Intent.DecisionHeldBack("work", "New one", ""));
        assertThat(state.later()).extracting(Decision::id).containsExactly(7L, 8L);

        state = States.twoPanels();
        apply(new Intent.DecisionsRead(List.of(new Decision(7, "work", "Old one", "", 1L)), 12L));
        apply(new Intent.DecisionHeldBack("work", "After a restart", ""));
        assertThat(state.later()).extracting(Decision::id).as("12 was given before the restart").containsExactly(7L, 13L);
    }

    /// An id is never given twice: session `work` holds back 1 and 2, and its Settle checkpoint reads both ids; meanwhile
    /// the user dismisses 2 and session `other` holds back a new decision; the checkpoint then settles 1 and 2. The new
    /// decision, never asked, settled nor dismissed, still waits.
    @Test
    void aSettleWithIdsReadEarlierLeavesADecisionHeldBackSinceAlone() {
        apply(new Intent.DecisionHeldBack("work", "One", ""));
        apply(new Intent.DecisionHeldBack("work", "Two", ""));
        final List<Long> readByTheCheckpoint = state.later().stream().map(Decision::id).toList();
        apply(new Intent.DismissDecision(readByTheCheckpoint.get(1)));
        apply(new Intent.DecisionHeldBack("other", "Three", ""));
        apply(new Intent.DecisionsSettled(readByTheCheckpoint));
        assertThat(state.later()).extracting(Decision::title).as("Three was neither asked, settled nor dismissed").containsExactly("Three");
    }

    /// Settled ids leave and the rest is saved; ids no longer kept change nothing; Dismiss removes one the same way.
    @Test
    void settledAndDismissedDecisionsLeave() {
        apply(new Intent.DecisionHeldBack("work", "One", ""));
        apply(new Intent.DecisionHeldBack("work", "Two", ""));
        apply(new Intent.DecisionHeldBack("work", "Three", ""));
        apply(new Intent.DecisionsSettled(List.of(1L, 3L)));
        assertThat(state.later()).extracting(Decision::title).containsExactly("Two");
        assertThat(effects).containsExactly(new Effect.SaveDecisions(state.later(), state.nextId() - 1));

        apply(new Intent.DecisionsSettled(List.of(99L)));
        assertThat(effects).isEmpty();

        apply(new Intent.DismissDecision(2L));
        assertThat(state.later()).isEmpty();
        assertThat(effects).containsExactly(new Effect.SaveDecisions(List.of(), state.nextId() - 1));
    }

    /// At most [DecisionRules#MAX_KEPT] are kept: the oldest goes first.
    @Test
    void theOldestDecisionGoesWhenTooManyAreKept() {
        for (int i = 0; i <= DecisionRules.MAX_KEPT; i++) {
            apply(new Intent.DecisionHeldBack("work", "d" + i, ""));
        }
        assertThat(state.later()).hasSize(DecisionRules.MAX_KEPT);
        assertThat(state.later().getFirst().title()).isEqualTo("d1");
    }

    /// Settle sends the decision's session its checkpoint command as typed, without `--auto`, from its panel; the
    /// decision stays listed until the session settles it.
    @Test
    void settleSendsTheCheckpointCommandWithoutTheAutomaticArgument() {
        apply(new Intent.StartSession("work"));
        apply(new Intent.SessionStarted("work"));
        apply(new Intent.DecisionHeldBack("work", "One", ""));
        apply(new Intent.SettleDecision(1L));
        assertThat(effects).contains(new Effect.SendToClaude("work", "/reflect", "one"));
        assertThat(state.later()).hasSize(1);
    }

    /// A decision whose session is gone is settled in the focused panel's session, as [Reducer#settleTarget] names it
    /// beforehand; with neither, a warning says so and nothing is sent.
    @Test
    void aDecisionWhoseSessionIsGoneIsSettledInTheFocusedSession() {
        apply(new Intent.StartSession("work"));
        apply(new Intent.SessionStarted("work"));
        apply(new Intent.DecisionHeldBack("gone", "One", ""));
        assertThat(Reducer.settleTarget(state, 1L)).as("named before it is pressed").contains("work");
        apply(new Intent.SettleDecision(1L));
        assertThat(effects).contains(new Effect.SendToClaude("work", "/reflect", "one"));

        apply(new Intent.FocusPanel(P2));
        assertThat(Reducer.settleTarget(state, 1L)).isEmpty();
        assertThat(Reducer.settleTarget(state, 99L)).as("no such decision").isEmpty();
        apply(new Intent.SettleDecision(1L));
        assertThat(effects).doesNotHaveAnyElementsOfTypes(Effect.SendToClaude.class);
        assertThat(state.notice().level()).isEqualTo(Notice.Level.WARN);
    }

    /// While the target session's checkpoint runs, Settle is refused: nothing is sent (record 130).
    @Test
    void settleIsRefusedWhileTheTargetSessionsCheckpointRuns() {
        apply(new Intent.StartSession("work"));
        apply(new Intent.SessionStarted("work"));
        apply(new Intent.Checkpoint("work"));
        assertThat(state.session("work").orElseThrow().isCheckpointing()).isTrue();
        apply(new Intent.DecisionHeldBack("work", "One", ""));
        apply(new Intent.SettleDecision(1L));
        assertThat(effects).isEmpty();
        assertThat(Reducer.refusedWhileCheckpointing(state, new Intent.SettleDecision(1L))).isTrue();
    }
}
