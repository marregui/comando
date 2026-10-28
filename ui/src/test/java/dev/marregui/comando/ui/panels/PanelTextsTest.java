package dev.marregui.comando.ui.panels;

import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.Status;
import dev.marregui.comando.core.state.BackgroundTask;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.core.state.Reflection;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.core.state.SqlRun;
import dev.marregui.comando.testkit.States;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// The words the panels show for runs, turns and notices ([ResultsView], [EditorPanel], [SessionRow], [SessionBar],
/// [StatusBar]).
/// - a DDL run reads "Done."; a DML run counts the rows it changed
/// - a running statement shows its elapsed time; a block's note ends the status line
/// - the error position is read from the driver's text
/// - a running session row states its uptime and start time
/// - the checkpoint icon and tooltip change when a checkpoint is due or a summary waits
/// - a busy or waiting session says what it works or waits on and since when
/// - a notice in the history carries its time and is cut at 160 characters; its tooltip is whole and wraps when long
class PanelTextsTest {

    private static final PanelId P = States.P1;

    /// A DDL run's done text is "Done." with no row count in the status line; an INSERT of 100,000 rows reads
    /// "100,000 rows changed" in both the done text and the status line.
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

    /// A run started at 10 s shows "Running on local · 12 s" at 22 s, and a run carrying a block note ends its status line
    /// with that note.
    @Test
    void aRunningStatementShowsItsElapsedTimeAndABlockItsNote() {
        SqlRun run = SqlRun.queued(1, P, "local", "SELECT 1").started(10_000);
        assertThat(ResultsView.runningText(run, 22_000)).isEqualTo("Running on local · 12 s");
        SqlRun noted = run.completed(1, -1, false, 1, 0, 1).withNote("statement 2 of the block failed: boom", true);
        assertThat(ResultsView.statusLine(noted)).endsWith("statement 2 of the block failed: boom");
    }

    /// [EditorPanel#errorPosition] reads the number after `Position:`, and returns 0 for text without one and for null.
    @Test
    void theErrorPositionIsReadFromTheDriversText() {
        assertThat(EditorPanel.errorPosition("ERROR: table does not exist [table=nope]\n  Position: 38")).isEqualTo(38);
        assertThat(EditorPanel.errorPosition("ERROR: boom")).isZero();
        assertThat(EditorPanel.errorPosition(null)).isZero();
    }

    /// A running session's details give its uptime in minutes, hours or days with the start time (and date past a day),
    /// "under a minute" below one minute, and no uptime once it is stopped.
    @Test
    void aRunningSessionRowSaysForHowLongAndItsDetailsWhenItStarted() {
        ZoneId utc = ZoneOffset.UTC;
        long started = Instant.parse("2026-10-16T09:12:30Z").toEpochMilli();
        SessionState running = SessionState.closed(States.WORK).withStatus(Status.OPEN, "").withStarted(started);
        assertThat(SessionRow.details(running, started + (3 * 60 + 5) * 60_000L, utc, "")).contains("Uptime: 3 h 05 min, since 09:12\n");
        assertThat(SessionRow.details(running, started + 26 * 3_600_000L, utc, "")).contains("Uptime: 1 d 2 h, since Oct 16 09:12\n");
        assertThat(SessionRow.details(running, started + 30_000, utc, "")).contains("Uptime: under a minute, since 09:12\n");
        assertThat(SessionRow.details(running.withStatus(Status.CLOSED, ""), started + 60_000, utc, "")).as("stopped").doesNotContain("Uptime");
    }

    /// The checkpoint icon is `checkpoint-due` only past 60% context; its tooltip names what a checkpoint sends and says
    /// when a carried summary still waits for the next prompt.
    @Test
    void theCheckpointButtonIsPurpleOnlyWhenDue() {
        SessionState x = SessionState.closed(States.WORK);
        assertThat(SessionBar.checkpointIcon(x)).isEqualTo("checkpoint");
        assertThat(SessionBar.checkpointTip(x))
                .isEqualTo("Checkpoint: sends /reflect, then clears the conversation and carries its summary into the next prompt");
        SessionState due = x.withReflection(Reflection.FRESH.afterTurn(700_000L, 1_000_000L));
        assertThat(SessionBar.checkpointIcon(due)).isEqualTo("checkpoint-due");
        assertThat(SessionBar.checkpointTip(due)).isEqualTo("Checkpoint due, context past 60%: sends /reflect, then clears the conversation"
                + " and carries its summary into the next prompt");
        assertThat(SessionBar.checkpointTip(x.withSpec(x.spec().withCarried("the summary"))))
                .isEqualTo("Checkpoint: sends /reflect; the last checkpoint's summary still waits for the next prompt");
    }

    /// A session with two turns pending shows "working · …" ending with "1 queued"; an idle session shows nothing.
    @Test
    void aBusySessionShowsHowLongItWorksAndWhatIsQueued() {
        SessionState x = SessionState.closed(States.WORK).withTurns(2, System.currentTimeMillis() - 5_000);
        assertThat(SessionBar.busyText(x)).contains("working · ").endsWith(" · 1 queued");
        assertThat(SessionBar.busyText(SessionState.closed(States.WORK))).isEmpty();
    }

    /// A session waiting on one background task names it with its elapsed seconds; on two it counts them; the chip keeps
    /// the session's name, and the tick counts a waiting session as it does a working one.
    @Test
    void aSessionWaitingOnBackgroundWorkSaysOnWhatAndSinceWhen() {
        long started = System.currentTimeMillis() - 5_000;
        BackgroundTask agent = new BackgroundTask("a1", "local_agent", "review agent", 0L);
        SessionState one = SessionState.closed(States.WORK).withTasks(List.of(agent), started);
        assertThat(SessionBar.busyText(one)).startsWith("  waiting on review agent · ").endsWith(" s");
        SessionState two = one.withTasks(List.of(agent, new BackgroundTask("b2", "local_bash", "build in the background", 0L)), started);
        assertThat(SessionBar.busyText(two)).startsWith("  waiting on 2 background tasks · ");
        assertThat(SessionBar.chipText(two)).as("the chip keeps still; the activity is a label of its own").isEqualTo("work");
        assertThat(SessionBar.counting(one)).as("the tick moves a waiting chip's time on, as a working one's").isTrue();
        assertThat(SessionBar.counting(SessionState.closed(States.WORK))).isFalse();
    }

    /// A history line prefixes the notice's time when it has one, cuts a 400-character text to 160, and the full line
    /// keeps all 400.
    @Test
    void aNoticeInTheHistoryCarriesItsTime() {
        assertThat(StatusBar.historyLine(new Notice(Notice.Level.INFO, "Saved one", 0L))).isEqualTo("Saved one");
        assertThat(StatusBar.historyLine(new Notice(Notice.Level.INFO, "Saved one", 1_000L))).matches("\\d\\d:\\d\\d:\\d\\d  Saved one");
        String long400 = "x".repeat(400);
        assertThat(StatusBar.historyLine(new Notice(Notice.Level.ERROR, long400, 0L))).hasSize(160);
        assertThat(StatusBar.fullLine(new Notice(Notice.Level.ERROR, long400, 0L))).as("the tooltip has the whole text").hasSize(400);
    }

    /// A short notice keeps a tooltip as wide as its text, with HTML escaped and newlines as breaks; long prose wraps at a
    /// fixed width, and a word longer than a line breaks every 70 characters.
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
