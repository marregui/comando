package dev.marregui.comando.ui.panels;

import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JList;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.Approval;
import dev.marregui.comando.core.model.ApprovalKind;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.model.Status;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.BackgroundTask;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.core.state.RunStatus;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.core.state.SqlRun;
import dev.marregui.comando.ui.kit.async.OnEdt;

import static org.assertj.core.api.Assertions.assertThat;

/// [StatusBar]'s work list: what the count at its right stands for, line by line.
/// - who needs you comes first, then who works, then who waits; an idle or stopped session has no line
/// - a running query has a line of its own, under its panel's title
/// - a click on a line goes to its panel, the session's own; a session with none has nowhere to go
/// - a line says for how long: a query since it started, a request since the oldest arrived
@ExtendWith(OnEdt.class)
class StatusBarWorkTest {

    private static final PanelId ASKS = new PanelId("asks");
    private static final PanelId BUSY = new PanelId("busy");
    private static final PanelId NOTES = new PanelId("notes");
    private static final PanelId WAITS = new PanelId("waits");

    /// The rows read in rank order, each naming who and what, and the idle session has none.
    @Test
    void whoNeedsYouComesFirstThenWhoWorksThenWhoWaits() {
        final List<StatusBar.WorkRow> rows = StatusBar.workRows(state());
        assertThat(rows).extracting(StatusBar.WorkRow::rank).isSorted();
        assertThat(rows.getFirst().text()).startsWith("asks · needs you: 1 request · ");
        assertThat(rows).extracting(StatusBar.WorkRow::text)
                .anySatisfy(t -> assertThat(t).startsWith("busy · Bash · turn "))
                .anySatisfy(t -> assertThat(t).startsWith("no-panel · working · turn "))
                .anySatisfy(t -> assertThat(t).startsWith("Notes · query running · "))
                .noneSatisfy(t -> assertThat(t).startsWith("idle"));
        assertThat(rows.getLast().text()).startsWith("waits · waiting on review agent");
    }

    /// A click on a row closes the list and focuses its panel; on a session with no panel nothing happens.
    @Test
    void aClickOnALineGoesToItsPanel() {
        final List<Intent> intents = new CopyOnWriteArrayList<>();
        final StatusBar bar = new StatusBar(intents::add);
        bar.render(state());
        final AtomicInteger closed = new AtomicInteger();
        final JList<StatusBar.WorkRow> list = bar.workList(closed::incrementAndGet);
        list.setSize(600, 200);
        list.doLayout();
        click(list, "busy");
        assertThat(intents).containsExactly(new Intent.FocusPanel(BUSY));
        assertThat(closed.get()).isEqualTo(1);

        click(list, "no-panel");
        assertThat(intents).as("nowhere to go").hasSize(1);
        assertThat(closed.get()).isEqualTo(1);
    }

    /// The count in the status bar invites the click.
    @Test
    void theCountSaysItOpensTheList() {
        final StatusBar bar = new StatusBar(intent -> { });
        bar.render(state());
        assertThat(bar.workLabel().getToolTipText()).contains("Click to see who is working");
    }

    /// A query says how long it has run and a session that asks how long its oldest request has waited; a query still
    /// queued has not started, so it has no time.
    @Test
    void aLineSaysForHowLong() {
        final AppState s = state().withApprovals(List.of(approval(2L, 1), approval(1L, 5)));
        assertThat(StatusBar.workRows(s)).extracting(StatusBar.WorkRow::text)
                .anySatisfy(t -> assertThat(t).matches("asks · needs you: 2 requests · 5 min 0\\d s"))
                .anySatisfy(t -> assertThat(t).matches("Notes · query running · 5 min 0\\d s"));

        final SqlRun queued = SqlRun.queued(2L, NOTES, "db", "select 1");
        assertThat(StatusBar.workRows(s.withRuns(List.of(queued)))).extracting(StatusBar.WorkRow::text)
                .contains("Notes · query running");
    }

    /// A document bound to a session is not its panel, even when it comes first: a line goes to the session's own.
    @Test
    void aLineGoesToTheSessionsOwnPanelNotToADocumentOfIt() {
        final PanelId document = new PanelId("document");
        final List<PanelState> panels = List.of(new PanelState(document, "notes.md", "", "busy", true),
                new PanelState(BUSY, "Busy", "", "busy"));
        final AppState s = AppState.initial(panels, List.of(), List.of(busy()));
        assertThat(StatusBar.workRows(s)).extracting(StatusBar.WorkRow::panel).containsExactly(Optional.of(BUSY));
    }

    private static long ago(final int minutes) {
        return System.currentTimeMillis() - minutes * 60_000L;
    }

    private static Approval approval(final long id, final int minutes) {
        return new Approval(id, "asks", ApprovalKind.TOOL, "Bash", "{}", "ls", ago(minutes));
    }

    private static SessionState busy() {
        return open("busy").withTurns(1, 1_000L).withActivity("Bash", 1_000L);
    }

    private static void click(final JList<StatusBar.WorkRow> list, final String who) {
        for (int i = 0; i < list.getModel().getSize(); i++) {
            if (list.getModel().getElementAt(i).text().startsWith(who + " ")) {
                final Rectangle r = list.getCellBounds(i, i);
                list.dispatchEvent(new MouseEvent(list, MouseEvent.MOUSE_CLICKED, 0L, 0, r.x + 2, r.y + 2, 1, false,
                        MouseEvent.BUTTON1));
                return;
            }
        }
        throw new AssertionError("no row of " + who);
    }

    private static SessionState open(final String name) {
        return SessionState.closed(SessionSpec.fresh(name, "/tmp")).withStatus(Status.OPEN, "");
    }

    private static AppState state() {
        final BackgroundTask review = new BackgroundTask("a1", "local_agent", "review agent", 0L);
        final SessionState waits = open("waits").withTurns(1, 1_000L).withTasks(List.of(review), 1_000L)
                .withTurns(0, 2_000L);
        final List<PanelState> panels = List.of(new PanelState(WAITS, "Waits", "", "waits"),
                new PanelState(BUSY, "Busy", "", "busy"), new PanelState(ASKS, "Asks", "", "asks"),
                new PanelState(NOTES, "Notes", "", ""));
        final SqlRun run = new SqlRun(1L, NOTES, "db", "select 1", RunStatus.RUNNING, 0, 0L, false, 0L, 0L, 0L, "",
                ago(5), "", false, false);
        return AppState.initial(panels, List.of(), List.of(waits, busy(), open("asks"), open("idle"), open("no-panel")
                        .withTurns(1, 1_000L)))
                .withApprovals(List.of(approval(1L, 5)))
                .withRuns(List.of(run));
    }
}
