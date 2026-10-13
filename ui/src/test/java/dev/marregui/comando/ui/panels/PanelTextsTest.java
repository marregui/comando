package dev.marregui.comando.ui.panels;

import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.core.state.SqlRun;
import dev.marregui.comando.testkit.States;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The words the panels show for runs, turns and notices. */
class PanelTextsTest {

    private static final PanelId P = States.P1;

    @Test
    void aDdlStatementIsDoneAndADmlCountsItsRowsInBothPlaces() {
        SqlRun ddl = SqlRun.queued(1, P, "local", "CREATE TABLE t (x INT)").started(1).completed(0, 0, false, 1, 0, 1);
        assertThat(ResultsView.doneText(ddl)).isEqualTo("Done.");
        assertThat(ResultsView.statusLine(ddl)).doesNotContain("0 rows");
        SqlRun insert = SqlRun.queued(2, P, "local", "INSERT INTO t SELECT x FROM long_sequence(100000)").started(1)
                .completed(0, 100_000, false, 1, 0, 1);
        assertThat(ResultsView.doneText(insert)).isEqualTo("100,000 rows changed");
        assertThat(ResultsView.statusLine(insert)).contains("100,000 rows changed").doesNotContain("· 0 rows");
    }

    @Test
    void aRunningStatementShowsItsElapsedTimeAndABlockItsNote() {
        SqlRun run = SqlRun.queued(1, P, "local", "SELECT 1").started(10_000);
        assertThat(ResultsView.runningText(run, 22_000)).isEqualTo("Running on local · 12 s");
        SqlRun noted = run.completed(1, -1, false, 1, 0, 1).withNote("statement 2 of the block failed: boom", true);
        assertThat(ResultsView.statusLine(noted)).endsWith("statement 2 of the block failed: boom");
    }

    @Test
    void theErrorPositionIsReadFromTheDriversText() {
        assertThat(EditorPanel.errorPosition("ERROR: table does not exist [table=nope]\n  Position: 38")).isEqualTo(38);
        assertThat(EditorPanel.errorPosition("ERROR: boom")).isZero();
        assertThat(EditorPanel.errorPosition(null)).isZero();
    }

    @Test
    void aBusySessionShowsHowLongItWorksAndWhatIsQueued() {
        SessionState x = SessionState.closed(States.WORK).withTurns(2, System.currentTimeMillis() - 5_000);
        assertThat(EditorPanel.busyText(x)).contains("working · ").endsWith(" · 1 queued");
        assertThat(EditorPanel.busyText(SessionState.closed(States.WORK))).isEmpty();
    }

    @Test
    void aNoticeInTheHistoryCarriesItsTime() {
        assertThat(StatusBar.historyLine(new Notice(Notice.Level.INFO, "Saved one", 0L))).isEqualTo("Saved one");
        assertThat(StatusBar.historyLine(new Notice(Notice.Level.INFO, "Saved one", 1_000L))).matches("\\d\\d:\\d\\d:\\d\\d  Saved one");
        String long400 = "x".repeat(400);
        assertThat(StatusBar.historyLine(new Notice(Notice.Level.ERROR, long400, 0L))).hasSize(160);
        assertThat(StatusBar.fullLine(new Notice(Notice.Level.ERROR, long400, 0L))).as("the tooltip has the whole text").hasSize(400);
    }

    /** A short notice keeps a tooltip as wide as its text; a long one wraps, and a word longer than a line breaks. */
    @Test
    void theTooltipWrapsOnlyALongNotice() {
        assertThat(StatusBar.wrapped("Query finished")).isEqualTo("<html>Query finished</html>");
        assertThat(StatusBar.wrapped("a <b>\nc")).isEqualTo("<html>a &lt;b&gt;<br>c</html>");
        String prose = "word ".repeat(30).strip();
        assertThat(StatusBar.wrapped(prose)).startsWith("<html><div style='width:520px'>");
        String path = "/" + "p".repeat(150);
        assertThat(StatusBar.wrapped(path)).doesNotContain("width").isEqualTo("<html>/" + "p".repeat(69) + "<br>" + "p".repeat(70)
                + "<br>" + "p".repeat(11) + "</html>");
    }
}
