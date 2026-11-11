package dev.marregui.comando.ui.panels;

import java.awt.Color;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JLabel;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import javax.swing.border.Border;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.core.model.Approval;
import dev.marregui.comando.core.model.ApprovalKind;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.model.Status;
import dev.marregui.comando.core.result.ResultRegistry;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.core.transcript.TranscriptRegistry;
import dev.marregui.comando.ui.kit.action.ActionRegistry;
import dev.marregui.comando.ui.kit.theme.BracketBorder;
import dev.marregui.comando.ui.kit.theme.Ground;
import dev.marregui.comando.ui.kit.theme.NamedTheme;
import dev.marregui.comando.ui.kit.theme.Style;
import dev.marregui.comando.ui.kit.theme.Theme;
import dev.marregui.comando.ui.kit.theme.Tint;
import dev.marregui.comando.ui.widget.WidgetContext;
import dev.marregui.comando.ui.widget.WidgetRegistry;

import static org.assertj.core.api.Assertions.assertThat;

/// [LowerPanel]'s Approvals tab and Session veil.
/// - the tab's title counts the cards waiting, and nothing else; a card waiting takes the attention colour
/// - the Session tab's veil lifts while the checkpointing session has a waiting card and returns when none waits
class LowerPanelApprovalsTest {

    private static final PanelId OWN = new PanelId("own");
    private static final PanelId OTHER = new PanelId("other");

    /// No card: `Approvals`; cards waiting: `Approvals (N)`, with no later count. The attention colour shows only while
    /// a card waits.
    @Test
    void theTabTitleCountsTheCardsWaitingAndNothingElse() throws Exception {
        final LowerPanel lower = lower();
        final AppState base = state(false);
        final List<Approval> two = List.of(card(1, "other"), card(2, "work"));

        SwingUtilities.invokeAndWait(() -> lower.render(base));
        assertThat(title(lower)).isEqualTo("Approvals");
        assertThat(colour(lower)).isNotEqualTo(Theme.ATTENTION);

        SwingUtilities.invokeAndWait(() -> lower.render(base.withApprovals(two)));
        assertThat(title(lower)).isEqualTo("Approvals (2)");
        assertThat(colour(lower)).isEqualTo(Theme.ATTENTION);

        SwingUtilities.invokeAndWait(() -> lower.render(base));
        assertThat(title(lower)).as("answered").isEqualTo("Approvals");
        assertThat(colour(lower)).isNotEqualTo(Theme.ATTENTION);
    }

    /// The title's text in its two forms, from the count of cards waiting alone.
    @Test
    void theTitleHasTwoFormsAndNoLaterCount() {
        assertThat(LowerPanel.approvalsTitle(0)).isEqualTo("Approvals");
        assertThat(LowerPanel.approvalsTitle(4)).isEqualTo("Approvals (4)");
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

    /// Under a plain theme the lower panel's frame, kept through a look and feel update, leaves every part of the
    /// strip at the bounds it has with the strip's own border; under a console theme the tab shown lays out
    /// inside the frame, 3 px in at the sides and the bottom.
    @Test
    void theLowerPanelFrameTakesRoomOnlyUnderAConsoleTheme() throws Exception {
        final List<List<Rectangle>> bounds = new ArrayList<>();
        final Rectangle[] shown = new Rectangle[2];
        final Border[] kept = new Border[1];
        final Style[] style = new Style[1];
        SwingUtilities.invokeAndWait(() -> {
            final JTabbedPane tabs = (JTabbedPane) new LowerPanel(new WidgetContext(_ -> { }, new ActionRegistry()),
                    new ResultRegistry(), new TranscriptRegistry(), new WidgetRegistry()).component();
            tabs.addTab("Body", new JLabel("body"));
            tabs.setSelectedIndex(tabs.getTabCount() - 1);
            SwingUtilities.updateComponentTreeUI(tabs);
            kept[0] = tabs.getBorder();
            style[0] = Theme.style();
            bounds.add(ConsoleChecks.laidOut(tabs));
            shown[0] = tabs.getSelectedComponent().getBounds();
            tabs.setBorder(new JTabbedPane().getBorder());
            bounds.add(ConsoleChecks.laidOut(tabs));
            tabs.setBorder(kept[0]);
            final Ground ground = Theme.groundChosen();
            final Tint tint = Theme.tint();
            Theme.choose(NamedTheme.CONSOLE_AMBER.ground, NamedTheme.CONSOLE_AMBER.tint);
            try {
                ConsoleChecks.laidOut(tabs);
                shown[1] = tabs.getSelectedComponent().getBounds();
            } finally {
                Theme.choose(ground, tint);
            }
        });
        assertThat(style[0]).isEqualTo(Style.PLAIN);
        assertThat(kept[0]).isInstanceOf(BracketBorder.class);
        assertThat(bounds.get(0)).as("plain: as with no frame").isEqualTo(bounds.get(1));
        assertThat(shown[1].x).as("console: left").isEqualTo(shown[0].x + BracketBorder.INSET);
        assertThat(shown[1].width).as("console: width").isEqualTo(shown[0].width - 2 * BracketBorder.INSET);
        assertThat(shown[1].y + shown[1].height).as("console: bottom")
                .isEqualTo(shown[0].y + shown[0].height - BracketBorder.INSET);
    }
}
