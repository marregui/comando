package dev.marregui.comando.app;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.core.dispatch.Target;
import dev.marregui.comando.core.intent.EditMode;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.json.Json;
import dev.marregui.comando.core.json.ObjectNode;
import dev.marregui.comando.core.loop.StateLoop;
import dev.marregui.comando.core.model.Approval;
import dev.marregui.comando.core.model.ApprovalDecision;
import dev.marregui.comando.core.model.ApprovalKind;
import dev.marregui.comando.core.model.ConnectionSpec;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.port.Clock;
import dev.marregui.comando.core.reduce.Reducer;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.LowerTab;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.core.state.SessionInfo;
import dev.marregui.comando.core.view.Layout;
import dev.marregui.comando.core.view.Placement;
import dev.marregui.comando.core.view.ViewSpec;
import dev.marregui.comando.testkit.States;

import static org.assertj.core.api.Assertions.assertThat;

/// [IntentLog] writes every intent as one JSON line and reads it back for replay.
/// - every kind of intent survives a write and a read, the initial state too
/// - a connection's password never reaches the log
/// - a password inside a text field that holds JSON (an approval card's tool input) is blanked too
/// - a connection spec printed as text writes its password as `***`
/// - the checkpoint threshold and each change of it are logged, and a replay applies each intent under its own, the
///   value the run applied it under
class IntentLogTest {

    static final List<Intent> SAMPLE = List.of(
            new Intent.AddPanel(new PanelId("p9"), "nine"),
            new Intent.RunText(new PanelId("p9"), "SELECT 1", Target.AUTO),
            new Intent.RunText(null, "why?", Target.CLAUDE),
            new Intent.SqlCompleted(3L, 10, -1L, false, 1, 2, 3),
            new Intent.SaveConnection("", new ConnectionSpec("lab", "h", 8812, "qdb", "u", "", true, "require")),
            new Intent.TestConnection(new ConnectionSpec("t", "h", 8812, "qdb", "u", "", false)),
            new Intent.SaveSession("", SessionSpec.imported("edge", "/w", "abc")),
            new Intent.ImportSessions(List.of(SessionSpec.fresh("a", "/a"))),
            new Intent.SessionInit("edge", new SessionInfo("abc", "opus", "/w", "2.1.296", "default", List.of("interrupt"))),
            new Intent.SessionTurnEnded("edge", 0, 0.5, 1000L, 1_000_000L, false, ""),
            new Intent.ApprovalRequested(new Approval(1L, "edge", ApprovalKind.TOOL, "Bash", "{\"command\":\"ls\"}", "ls", 5L)),
            new Intent.ApprovalAnswered(1L, new ApprovalDecision.Deny("no")),
            new Intent.ApprovalAnswered(2L, new ApprovalDecision.Answer(Map.of("Q?", "A"))),
            new Intent.ShowView("edge", "t", new ViewSpec.Table(List.of("a"), List.of(List.of("1"))), Placement.LOWER),
            new Intent.ShowView("edge", "p", new ViewSpec.Plot("x", "y", true,
                    List.of(new ViewSpec.Series("s", new double[]{1, 2}, new double[]{3, Double.NaN}))), Placement.LOWER),
            new Intent.ShowView("", "tree", new ViewSpec.Tree(new ViewSpec.TreeNode("r", "d", "comando-file:/a#1", List.of())), Placement.SIDE),
            new Intent.ShowView("", "c", ViewSpec.Console.running(4L, "gradle build"), Placement.LOWER),
            new Intent.SelectLowerTab(LowerTab.view(7L)),
            new Intent.Arrange(new Layout(Layout.Toggle.HIDE, Layout.Toggle.KEEP, 0.4)),
            new Intent.Arrange(new Layout(Layout.Toggle.KEEP, Layout.Toggle.KEEP, Double.NaN, new PanelId("p1"))),
            new Intent.SchemaChanged("lab"),
            new Intent.RefreshSql(new PanelId("p1"), "SELECT 1"),
            new Intent.EditPanel(null, "text", EditMode.APPEND),
            new Intent.CommandFinished(4L, 1, 10L, false, ViewSpec.TreeNode.NONE, Map.of("A.b", "boom")),
            new Intent.Notify(Notice.Level.WARN, "careful", true));

    /// A log of one sample per intent kind, written to a file and replayed, gives back the initial state and every
    /// intent equal; a plot's series arrays, which records compare by identity, match element by element with a `NaN`
    /// kept.
    @Test
    void everyIntentRoundTrips(@TempDir final Path dir) throws Exception {
        final AppState initial = AppState.initial(List.of(new PanelState(new PanelId("p1"), "one", "lab", "edge")),
                List.of(), List.of());
        try (final IntentLog log = new IntentLog(dir, initial, () -> 40)) {
            SAMPLE.forEach(log::observe);
        }
        Path file;
        try (final var files = Files.list(dir)) {
            file = files.findFirst().orElseThrow();
        }
        final IntentLog.Replay replay = IntentLog.read(file);
        assertThat(replay.initial()).isEqualTo(initial);
        final List<Intent> read = replay.intents();
        assertThat(read).hasSize(SAMPLE.size());
        for (int i = 0; i < SAMPLE.size(); i++) {
            final Intent expected = SAMPLE.get(i);
            if (expected instanceof Intent.ShowView v && v.spec() instanceof ViewSpec.Plot) {
                // arrays compare by identity in records; compare the parts
                final ViewSpec.Plot a = (ViewSpec.Plot) v.spec();
                final ViewSpec.Plot b = (ViewSpec.Plot) ((Intent.ShowView) read.get(i)).spec();
                assertThat(b.series().getFirst().x()).containsExactly(a.series().getFirst().x());
                assertThat(Double.isNaN(b.series().getFirst().y()[1])).isTrue();
            } else {
                assertThat(read.get(i)).isEqualTo(expected);
            }
        }
    }

    /// The log records the checkpoint threshold the reducer reads, and each change of it before the next intent: 90 %
    /// for the first turn, 40 % for the second. Replayed, each turn runs under its own: the first, at half the context
    /// window, leaves the session not due; the second, at the same size, makes it due, which the first value alone
    /// would not.
    @Test
    void theCheckpointThresholdIsLoggedAndEachIntentReplaysUnderItsOwn(@TempDir final Path dir) throws Exception {
        final AppState initial = States.twoPanels();
        final Intent half = new Intent.SessionTurnEnded("work", 0, 0.1, 500_000L, 1_000_000L, false, "");
        final Intent halfAgain = new Intent.SessionTurnEnded("work", 0, 0.1, 500_000L, 1_000_000L, false, "");
        final AtomicInteger threshold = new AtomicInteger(90);
        try (final IntentLog log = new IntentLog(dir, initial, threshold::get)) {
            log.observe(half);
            threshold.set(40);
            log.observe(halfAgain);
        }
        Path file;
        try (final var files = Files.list(dir)) {
            file = files.findFirst().orElseThrow();
        }
        final IntentLog.Replay replay = IntentLog.read(file);
        assertThat(replay.intents()).containsExactly(half, halfAgain);
        assertThat(replay.thresholds()).containsExactly(90, 40);
        assertThat(replay.threshold()).isEqualTo(90);
        final List<String> lines = Files.readAllLines(file);
        assertThat(lines.getFirst()).contains("\"version\":1", "\"checkpointThreshold\":90");
        assertThat(lines.get(2)).contains("\"type\":\"@threshold\"", "\"percent\":40");

        try (final StateLoop loop = replay.loop(Clock.SYSTEM, e -> { })) {
            loop.applyNow(replay.intents().get(0));
            assertThat(loop.state().session("work").orElseThrow().reflection().due()).as("50 % under 90 %").isFalse();
            loop.applyNow(replay.intents().get(1));
            assertThat(loop.state().session("work").orElseThrow().reflection().due()).as("50 % under 40 %").isTrue();
        }
    }

    /// The window's setting changes from 90 % to 40 % after the log wrote an intent's line and before the reducer reads
    /// the threshold for it, as the EDT's write may land while the reducer thread is between the two; the observer
    /// added after the log stands in for that write. The run, wired as [App] wires it, applies the intent under the
    /// value the log wrote for it, so its replay applies it as the run did.
    @Test
    void aThresholdSetBetweenTheLogsLineAndTheReducersReadReplaysAsTheRunApplied(@TempDir final Path dir) throws Exception {
        final AppState initial = States.twoPanels();
        final AtomicInteger threshold = new AtomicInteger(90);
        final Intent half = new Intent.SessionTurnEnded("work", 0, 0.1, 500_000L, 1_000_000L, false, "");
        boolean live;
        try (final IntentLog log = new IntentLog(dir, initial, threshold::get);
             final StateLoop run = new StateLoop(initial, new Reducer(Clock.SYSTEM, App.appliedThreshold(log, threshold::get)), e -> { })) {
            run.addObserver(log);
            run.addObserver(i -> threshold.set(40));
            run.applyNow(half);
            live = run.state().session("work").orElseThrow().reflection().due();
        }
        Path file;
        try (final var files = Files.list(dir)) {
            file = files.findFirst().orElseThrow();
        }
        final IntentLog.Replay replay = IntentLog.read(file);
        try (final StateLoop loop = replay.loop(Clock.SYSTEM, e -> { })) {
            loop.applyNow(replay.intents().getFirst());
            assertThat(loop.state().session("work").orElseThrow().reflection().due())
                    .as("replayed under %s; the run applied it so too", replay.thresholds()).isEqualTo(live);
        }
    }

    /// A `SaveConnection` whose spec holds the password `s3cret` is logged with an empty `password` field.
    @Test
    void passwordsNeverReachTheLog() throws Exception {
        final String line = IntentLog.line(new Intent.SaveConnection("", new ConnectionSpec("lab", "h", 8812, "qdb", "u", "s3cret", false)));
        assertThat(line).doesNotContain("s3cret").contains("\"password\":\"\"");
    }

    /// A text field holding a tool's JSON input, as an approval card carries it, is read, its `password` blanked and
    /// written back; free text that only mentions the word stays as it is.
    @Test
    void aPasswordInsideJsonTextNeverReachesTheLog() {
        final ObjectNode node = Json.object();
        node.put("input", "{\"name\":\"lab\",\"password\":\"s3cret\"}");
        node.put("note", "the password field is optional");
        final String out = IntentLog.redact(node).toString();
        assertThat(out).doesNotContain("s3cret").contains("the password field is optional");
    }

    /// `ConnectionSpec.toString` writes `password=***`, so a spec in a log line or an exception message keeps it out.
    @Test
    void aConnectionSpecPrintsNoPassword() {
        assertThat(new ConnectionSpec("lab", "h", 8812, "qdb", "u", "s3cret", false).toString())
                .doesNotContain("s3cret").contains("password=***");
    }

    /// A log that recorded no threshold (every log written before it was) replays with a reducer that never
    /// checkpoints, not at the window's minimum; one that did replays at its intent's threshold. An intent the log does
    /// not hold, though equal to one it does, keeps the threshold in force.
    @Test
    void aLogWithoutAThresholdReplaysWithAReducerThatNeverCheckpoints() {
        final AppState start = States.twoPanels();
        final Intent turn = new Intent.SessionTurnEnded("work", 0, 0.1, 900_000L, 1_000_000L, false, "");
        try (final StateLoop loop = new IntentLog.Replay(start, List.of(turn), List.of()).loop(Clock.SYSTEM, e -> { })) {
            loop.applyNow(turn);
            assertThat(loop.state().session("work").orElseThrow().reflection().due()).as("no threshold: never due").isFalse();
        }
        try (final StateLoop loop = new IntentLog.Replay(start, List.of(turn), List.of(40)).loop(Clock.SYSTEM, e -> { })) {
            loop.applyNow(turn);
            assertThat(loop.state().session("work").orElseThrow().reflection().due()).as("40 %: due at 90 %").isTrue();
        }
        final Intent low = new Intent.SessionTurnEnded("work", 0, 0.1, 500_000L, 1_000_000L, false, "");
        try (final StateLoop loop = new IntentLog.Replay(start, List.of(low, low), List.of(90, 40)).loop(Clock.SYSTEM, e -> { })) {
            loop.applyNow(new Intent.SessionTurnEnded("work", 0, 0.1, 500_000L, 1_000_000L, false, ""));
            assertThat(loop.state().session("work").orElseThrow().reflection().due()).as("not the log's: still 90 %").isFalse();
        }
    }
}
