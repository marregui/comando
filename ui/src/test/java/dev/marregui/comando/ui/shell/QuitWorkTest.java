package dev.marregui.comando.ui.shell;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.model.Status;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.BackgroundTask;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.core.state.SqlRun;
import dev.marregui.comando.core.state.ViewState;
import dev.marregui.comando.core.view.Placement;
import dev.marregui.comando.core.view.ViewSpec;

import static org.assertj.core.api.Assertions.assertThat;

/// [QuitFlow#runningWork] names what quitting would stop, which the quit question lists.
/// - Running queries, sessions in a turn, sessions with background work, shells running a job and Gradle runs.
/// - Nothing running: nothing named, and comando quits without asking.
class QuitWorkTest {

    private static final PanelId NOTES = new PanelId("notes");
    private static final PanelId SCRATCH = new PanelId("scratch");

    private static SessionState session(final String name) {
        return SessionState.closed(SessionSpec.fresh(name, "/tmp")).withStatus(Status.OPEN, "");
    }

    private static AppState idle() {
        return AppState.initial(List.of(new PanelState(NOTES, "notes", "", ""), new PanelState(SCRATCH, "scratch", "", "")),
                List.of(), List.of(session("main"), session("side")));
    }

    /// A state with nothing running and no shell job names nothing.
    @Test
    void nothingRunningNamesNothing() {
        assertThat(QuitFlow.runningWork(idle(), Map.of())).isEmpty();
    }

    /// Each kind of work is named once, in a fixed order: queries, turns, background work, the panels' jobs in the
    /// panels' order, then the Gradle runs; a finished Gradle run and a job of a panel no longer open are left out.
    @Test
    void eachKindOfRunningWorkIsNamed() {
        final ViewSpec.Console done = new ViewSpec.Console(8L, "gradle check", ViewSpec.RunState.PASSED, 0, 10L,
                ViewSpec.TreeNode.NONE, Map.of(), "");
        final AppState s = idle()
                .withRuns(List.of(SqlRun.queued(1L, NOTES, "db", "select 1"), SqlRun.queued(2L, NOTES, "db", "select 2")))
                .withSession("main", m -> m.withTurns(1, 1L))
                .withSession("side", m -> m.withTasks(List.of(new BackgroundTask("a1", "local_agent", "review", 0L)), 1L))
                .withViews(List.of(new ViewState(7L, "gradle build", ViewSpec.Console.running(7L, "gradle build"), Placement.LOWER, false, "", 1L),
                        new ViewState(8L, "gradle check", done, Placement.LOWER, false, "", 1L)));
        final Map<PanelId, String> jobs = Map.of(SCRATCH, "top", NOTES, "man cat", new PanelId("gone"), "vi");

        assertThat(QuitFlow.runningWork(s, jobs)).containsExactly("2 running queries", "main's turn",
                "side's background work", "man cat in notes' Terminal tab", "top in scratch's Terminal tab", "gradle build");
    }

    /// Several sessions in a turn are named together, with the plural possessive.
    @Test
    void severalSessionsInATurnAreNamedTogether() {
        final AppState s = idle().withSession("main", m -> m.withTurns(1, 1L)).withSession("side", m -> m.withTurns(2, 1L))
                .withRuns(List.of(SqlRun.queued(1L, NOTES, "db", "select 1")));

        assertThat(QuitFlow.runningWork(s, Map.of())).containsExactly("1 running query", "main, side' turns");
    }
}
