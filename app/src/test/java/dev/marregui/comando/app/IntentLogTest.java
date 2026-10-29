package dev.marregui.comando.app;

import dev.marregui.comando.core.dispatch.Target;
import dev.marregui.comando.core.intent.EditMode;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.Approval;
import dev.marregui.comando.core.model.ApprovalDecision;
import dev.marregui.comando.core.model.ApprovalKind;
import dev.marregui.comando.core.model.ConnectionSpec;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.LowerTab;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.core.state.SessionInfo;
import dev.marregui.comando.core.view.Layout;
import dev.marregui.comando.core.view.Placement;
import dev.marregui.comando.core.view.ViewSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// [IntentLog] writes every intent as one JSON line and reads it back for replay.
/// - every kind of intent survives a write and a read, the initial state too
/// - a connection's password never reaches the log
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
    void everyIntentRoundTrips(@TempDir Path dir) throws Exception {
        AppState initial = AppState.initial(List.of(new PanelState(new PanelId("p1"), "one", "lab", "edge")),
                List.of(), List.of());
        try (IntentLog log = new IntentLog(dir, initial)) {
            SAMPLE.forEach(log::observe);
        }
        Path file;
        try (var files = Files.list(dir)) {
            file = files.findFirst().orElseThrow();
        }
        IntentLog.Replay replay = IntentLog.read(file);
        assertThat(replay.initial()).isEqualTo(initial);
        List<Intent> read = replay.intents();
        assertThat(read).hasSize(SAMPLE.size());
        for (int i = 0; i < SAMPLE.size(); i++) {
            Intent expected = SAMPLE.get(i);
            if (expected instanceof Intent.ShowView v && v.spec() instanceof ViewSpec.Plot) {
                // arrays compare by identity in records; compare the parts
                ViewSpec.Plot a = (ViewSpec.Plot) v.spec();
                ViewSpec.Plot b = (ViewSpec.Plot) ((Intent.ShowView) read.get(i)).spec();
                assertThat(b.series().getFirst().x()).containsExactly(a.series().getFirst().x());
                assertThat(Double.isNaN(b.series().getFirst().y()[1])).isTrue();
            } else {
                assertThat(read.get(i)).isEqualTo(expected);
            }
        }
    }

    /// A `SaveConnection` whose spec holds the password `s3cret` is logged with an empty `password` field.
    @Test
    void passwordsNeverReachTheLog() throws Exception {
        String line = IntentLog.line(new Intent.SaveConnection("", new ConnectionSpec("lab", "h", 8812, "qdb", "u", "s3cret", false)));
        assertThat(line).doesNotContain("s3cret").contains("\"password\":\"\"");
    }
}
