package dev.marregui.comando.ui.panels;

import java.awt.Color;
import java.util.List;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.core.model.Approval;
import dev.marregui.comando.core.model.ApprovalKind;
import dev.marregui.comando.core.model.Decision;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.model.Status;
import dev.marregui.comando.core.result.ResultRegistry;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.core.transcript.TranscriptRegistry;
import dev.marregui.comando.ui.kit.action.ActionRegistry;
import dev.marregui.comando.ui.kit.theme.Theme;
import dev.marregui.comando.ui.widget.WidgetContext;
import dev.marregui.comando.ui.widget.WidgetRegistry;

import static org.assertj.core.api.Assertions.assertThat;

/// [LowerPanel]'s Approvals tab and Session veil with decisions held back (record 131).
/// - the tab's title counts live cards and decisions held back, in its three forms; only a live card takes the
///   attention colour
/// - the Session tab's veil lifts while the checkpointing session has a waiting card and returns when none waits
class LowerPanelApprovalsTest {

    private static final PanelId OWN = new PanelId("own");
    private static final PanelId OTHER = new PanelId("other");

    /// No card and no decision: `Approvals`; live cards: `Approvals (N)`; both: `Approvals (N · M later)`; decisions
    /// only: `Approvals (M later)`. The attention colour shows only while a live card waits.
    @Test
    void theTabTitleCountsLiveCardsAndDecisionsHeldBack() throws Exception {
        final LowerPanel lower = lower();
        final AppState base = state(false);
        final List<Approval> two = List.of(card(1, "other"), card(2, "work"));
        final List<Decision> three = List.of(held(5), held(6), held(7));

        SwingUtilities.invokeAndWait(() -> lower.render(base));
        assertThat(title(lower)).isEqualTo("Approvals");
        assertThat(colour(lower)).isNotEqualTo(Theme.ATTENTION);

        SwingUtilities.invokeAndWait(() -> lower.render(base.withApprovals(two)));
        assertThat(title(lower)).isEqualTo("Approvals (2)");
        assertThat(colour(lower)).isEqualTo(Theme.ATTENTION);

        SwingUtilities.invokeAndWait(() -> lower.render(base.withApprovals(two).withLater(three)));
        assertThat(title(lower)).isEqualTo("Approvals (2 · 3 later)");
        assertThat(colour(lower)).isEqualTo(Theme.ATTENTION);

        SwingUtilities.invokeAndWait(() -> lower.render(base.withLater(three)));
        assertThat(title(lower)).isEqualTo("Approvals (3 later)");
        assertThat(colour(lower)).as("a decision held back brings nothing forward").isNotEqualTo(Theme.ATTENTION);
    }

    /// The title's text in its three forms, from the counts alone.
    @Test
    void theTitleHasThreeForms() {
        assertThat(LowerPanel.approvalsTitle(0, 0)).isEqualTo("Approvals");
        assertThat(LowerPanel.approvalsTitle(4, 0)).isEqualTo("Approvals (4)");
        assertThat(LowerPanel.approvalsTitle(4, 1)).isEqualTo("Approvals (4 · 1 later)");
        assertThat(LowerPanel.approvalsTitle(0, 1)).isEqualTo("Approvals (1 later)");
    }

    /// While `work` runs its checkpoint the Session tab is greyed; a card of `work` waiting lifts the veil, a card of
    /// another session does not; once no card of `work` waits and the checkpoint still runs, the veil returns; when the
    /// checkpoint ends it is off whatever waits.
    @Test
    void theVeilLiftsWhileTheCheckpointingSessionHasAWaitingCard() throws Exception {
        final LowerPanel lower = lower();
        SwingUtilities.invokeAndWait(() -> lower.render(state(true)));
        assertThat(lower.sessionVeiled()).as("checkpointing, nothing waits").isTrue();

        SwingUtilities.invokeAndWait(() -> lower.render(state(true).withApprovals(List.of(card(1, "other")))));
        assertThat(lower.sessionVeiled()).as("another session's card").isTrue();

        SwingUtilities.invokeAndWait(() -> lower.render(state(true).withApprovals(List.of(card(1, "other"), card(2, "work")))));
        assertThat(lower.sessionVeiled()).as("its own card waits").isFalse();

        SwingUtilities.invokeAndWait(() -> lower.render(state(true).withApprovals(List.of(card(1, "other")))));
        assertThat(lower.sessionVeiled()).as("answered, the checkpoint still runs").isTrue();

        SwingUtilities.invokeAndWait(() -> lower.render(state(false).withApprovals(List.of(card(2, "work")))));
        assertThat(lower.sessionVeiled()).as("the checkpoint ended").isFalse();
    }

    private static LowerPanel lower() throws Exception {
        final LowerPanel[] lower = new LowerPanel[1];
        SwingUtilities.invokeAndWait(() -> lower[0] = new LowerPanel(new WidgetContext(i -> { }, new ActionRegistry()), new ResultRegistry(),
                new TranscriptRegistry(), new WidgetRegistry()));
        return lower[0];
    }

    private static String title(final LowerPanel lower) {
        return ((JTabbedPane) lower.component()).getTitleAt(LowerPanel.APPROVALS);
    }

    private static Color colour(final LowerPanel lower) {
        return ((JTabbedPane) lower.component()).getForegroundAt(LowerPanel.APPROVALS);
    }

    private static Approval card(final long id, final String session) {
        return new Approval(id, session, ApprovalKind.TOOL, "Bash", "{\"command\":\"ls\"}", "ls", 1_000L + id);
    }

    private static Decision held(final long id) {
        return new Decision(id, "work", "Decide " + id, "", 1_000L + id);
    }

    /** Two sessions working: `work` on its checkpoint when {@code checkpointing}; the focused panel is `work`'s own. */
    private static AppState state(final boolean checkpointing) {
        final SessionState busy = SessionState.closed(SessionSpec.fresh("work", "/w").withCheckpoint("/reflect"))
                .withStatus(Status.OPEN, "").withTurns(1, 1L);
        final SessionState work = checkpointing ? busy.withCheckpointTurns(1) : busy;
        final SessionState other = SessionState.closed(SessionSpec.fresh("other", "/o").withCheckpoint("/reflect"))
                .withStatus(Status.OPEN, "").withTurns(1, 1L);
        final List<PanelState> panels = List.of(new PanelState(OWN, "work", "", "work"), new PanelState(OTHER, "other", "", "other"));
        return AppState.initial(panels, List.of(), List.of(work, other)).withFocused(OWN);
    }
}
