package dev.marregui.comando.core.reduce;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.Approval;
import dev.marregui.comando.core.model.ApprovalDecision;
import dev.marregui.comando.core.model.ApprovalKind;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.LowerTab;
import dev.marregui.comando.testkit.FakeClock;
import dev.marregui.comando.testkit.States;

import static dev.marregui.comando.testkit.States.P1;
import static dev.marregui.comando.testkit.States.P2;
import static org.assertj.core.api.Assertions.assertThat;

/// [Reducer] and [AppState] keep each panel's lower tab.
/// - a panel focused again selects the tab it had; one never focused, or whose tab is gone, selects Session
/// - a request selects Approvals on the panel in front and on the asking session's panels, and nowhere else
/// - answering the last request selects Session on the asking session's panel, which comes to the front
class ReducerLowerTabTest {

    private final Reducer reducer = new Reducer(new FakeClock());
    private AppState state = States.twoPanels();

    /// p1 on Terminal, p2 on Results; each comes back on its own, and a closed view's tab falls back to Session.
    @Test
    void eachPanelKeepsItsOwnTab() {
        apply(new Intent.SelectLowerTab(LowerTab.TERMINAL));
        apply(new Intent.FocusPanel(P2));
        assertThat(state.lowerTab()).as("p2, never focused before").isEqualTo(LowerTab.SESSION);
        apply(new Intent.SelectLowerTab(LowerTab.RESULTS));
        apply(new Intent.FocusPanel(P1));
        assertThat(state.lowerTab()).isEqualTo(LowerTab.TERMINAL);
        apply(new Intent.FocusPanel(P2));
        assertThat(state.lowerTab()).isEqualTo(LowerTab.RESULTS);

        apply(new Intent.SelectLowerTab(LowerTab.view(99L)));
        apply(new Intent.FocusPanel(P1));
        apply(new Intent.FocusPanel(P2));
        assertThat(state.lowerTab()).as("a view that is not open").isEqualTo(LowerTab.SESSION);
    }

    /// p2 in front, `work` (p1's session) asks: Approvals on p2 and on p1. Answered from p2, p1 comes forward on
    /// Session; p2, focused again with nothing waiting, selects Session rather than an empty Approvals.
    @Test
    void aRequestSelectsApprovalsWhereItIsSeenAndAnsweringGoesBackToSession() {
        apply(new Intent.FocusPanel(P2));
        apply(new Intent.ApprovalRequested(new Approval(5L, "work", ApprovalKind.TOOL, "Bash", "{}", "ls", 1L, "toolu_5")));
        assertThat(state.lowerTab()).isEqualTo(LowerTab.APPROVALS);
        assertThat(state.panelTabs()).containsEntry(P1.value(), LowerTab.APPROVALS);

        apply(new Intent.ApprovalAnswered(5L, new ApprovalDecision.Deny("no")));
        assertThat(state.focused()).isEqualTo(P1);
        assertThat(state.lowerTab()).isEqualTo(LowerTab.SESSION);
        apply(new Intent.FocusPanel(P2));
        assertThat(state.lowerTab()).as("Approvals with nothing waiting").isEqualTo(LowerTab.SESSION);
    }

    /// A tab chosen on one panel stays there: the next panel selects its own.
    @Test
    void approvalsChosenOnOnePanelDoNotFollowToTheNext() {
        apply(new Intent.ApprovalRequested(new Approval(6L, "other", ApprovalKind.TOOL, "Bash", "{}", "ls", 1L, "toolu_6")));
        assertThat(state.lowerTab()).isEqualTo(LowerTab.APPROVALS);
        apply(new Intent.FocusPanel(P2));
        assertThat(state.lowerTab()).isEqualTo(LowerTab.SESSION);
        apply(new Intent.FocusPanel(P1));
        assertThat(state.lowerTab()).as("still waiting: p1's own Approvals").isEqualTo(LowerTab.APPROVALS);
    }

    /// A request answered from elsewhere leaves no stored Approvals behind: a later request of a session p2 does not
    /// work for selects Approvals on p1, in front, and leaves p2 on Session.
    @Test
    void aStoredApprovalsTabGoesWithTheLastRequest() {
        apply(new Intent.ApprovalRequested(new Approval(8L, "other", ApprovalKind.TOOL, "Bash", "{}", "ls", 1L, "toolu_8")));
        apply(new Intent.FocusPanel(P2));
        apply(new Intent.SelectLowerTab(LowerTab.APPROVALS));
        apply(new Intent.FocusPanel(P1));
        apply(new Intent.ApprovalAnswered(8L, new ApprovalDecision.Deny("no")));
        assertThat(state.panelTabs()).as("p2's Approvals dropped").doesNotContainValue(LowerTab.APPROVALS);

        apply(new Intent.ApprovalRequested(new Approval(9L, "third", ApprovalKind.TOOL, "Bash", "{}", "ls", 1L, "toolu_9")));
        apply(new Intent.FocusPanel(P2));
        assertThat(state.lowerTab()).isEqualTo(LowerTab.SESSION);
    }

    private void apply(final Intent intent) {
        state = reducer.apply(state, intent).state();
    }
}
