package dev.marregui.comando.core.effect;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import dev.marregui.comando.core.intent.EditMode;
import dev.marregui.comando.core.model.Approval;
import dev.marregui.comando.core.model.ApprovalDecision;
import dev.marregui.comando.core.model.ApprovalKind;
import dev.marregui.comando.core.model.ChangeAsk;
import dev.marregui.comando.core.model.ChangeTexts;
import dev.marregui.comando.core.model.ConnectionSpec;
import dev.marregui.comando.core.model.Decision;
import dev.marregui.comando.core.model.FolderRoot;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.PlanUsage;
import dev.marregui.comando.core.model.RepoStatus;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.core.view.Layout;
import dev.marregui.comando.core.view.ViewSpec;
import dev.marregui.comando.testkit.RecordingPorts;

import static org.assertj.core.api.Assertions.assertThat;

/// [EffectRouter] hands each effect to its own port method with its own arguments, so two methods of one port
/// (Unstage and RemoveFiles, ForgetPanel and DeletePanel) are never confused.
///
/// - one row per effect, naming the one call it makes
/// - every effect the sealed interface permits has a row
class EffectRouterTest {

    private static final ConnectionSpec LOCAL = new ConnectionSpec("local", "localhost", 8812, "qdb", "admin", "", false);
    private static final SessionSpec WORK = SessionSpec.fresh("work", "/w");
    private static final PanelId PANEL = new PanelId("p1");
    private static final FolderRoot NOTES = new FolderRoot("Notes", "/n", "", List.of());
    private static final RepoStatus.FileChange CHANGE = new RepoStatus.FileChange("a.txt", "", RepoStatus.Kind.MODIFIED);

    /// Each effect and the one call it makes on the recording ports.
    static Stream<Row> rows() {
        return Stream.of(
                row(new Effect.OpenDb(LOCAL), "db.open local"),
                row(new Effect.CloseDb("local"), "db.close local"),
                row(new Effect.ForgetSchema("local"), "db.forgetSchema local"),
                row(new Effect.TestDb(LOCAL), "db.test local"),
                row(new Effect.ExecuteSql(7, LOCAL, "SELECT 1"), "db.execute 7 local SELECT 1"),
                row(new Effect.CancelSql(7), "db.cancel 7"),
                row(new Effect.ReleaseResult(7), "db.release 7"),
                row(new Effect.LoadSchema(LOCAL, true), "db.schema local show"),
                row(new Effect.StartClaude(WORK), "claude.start work"),
                row(new Effect.StopClaude("work"), "claude.stop work"),
                row(new Effect.SendToClaude("work", "hi", "user", "before"), "claude.send work [preface] hi"),
                row(new Effect.InterruptClaude("work"), "claude.interrupt work"),
                row(new Effect.ClearTranscript("work"), "claude.clearTranscript work"),
                row(new Effect.LoadHistory(WORK), "claude.history work"),
                row(new Effect.ForgetSession("work"), "claude.forget work"),
                row(new Effect.ShowSubagent("work", "conv", "toolu_1"), "claude.showSubagent work conv toolu_1"),
                row(new Effect.ResolveApproval(new Approval(3, "work", ApprovalKind.TOOL, "Bash", "{}", "ls", 0L),
                        new ApprovalDecision.Allow(ApprovalDecision.Scope.ONCE)), "approval.resolve 3 Allow[scope=ONCE]"),
                row(new Effect.EditPanelText(PANEL, "text", EditMode.APPEND), "ui.edit APPEND text"),
                row(new Effect.DesktopNotify("Title", "body"), "ui.notify Title: body"),
                row(new Effect.ForgetPanel(PANEL), "ui.forget " + PANEL),
                row(new Effect.DeletePanel(PANEL), "ui.delete " + PANEL),
                row(new Effect.Arrange(Layout.KEEP), "ui.arrange " + Layout.KEEP),
                row(new Effect.ShowFile("work", "a.txt", "/w", 4), "ui.file /w a.txt:4"),
                row(new Effect.ShowFolder(NOTES, new ViewSpec.TreeNode("Notes", "/n", List.of())), "ui.folder Notes"),
                row(new Effect.ShowPlanUsage(new PlanUsage(List.of(new PlanUsage.Window("Session", 4, "")), 0L, "")), "ui.planUsage 1"),
                row(new Effect.ShowRepos(List.of(new RepoStatus("/r", "main", List.of(), false, ""))),
                        "ui.repos " + new RepoStatus("/r", "main", List.of(), false, "").name()),
                row(new Effect.ShowChange(ChangeTexts.noted("/r", CHANGE, "binary"), ChangeAsk.OPEN), "ui.change a.txt OPEN"),
                row(new Effect.RunGradle(5, "/w", "build"), "tools.gradle 5 /w build"),
                row(new Effect.CancelCommand(5), "tools.cancel 5"),
                row(new Effect.ForgetCommand(5), "tools.forget 5"),
                row(new Effect.ReadFolders(List.of(NOTES)), "tools.readFolders Notes"),
                row(new Effect.WatchFolders(List.of(NOTES)), "tools.watchFolders Notes"),
                row(new Effect.DeleteFiles(NOTES, List.of("x.md")), "tools.deleteFiles Notes x.md"),
                row(new Effect.RollBack("/r", List.of(CHANGE)), "tools.rollBack /r a.txt"),
                row(new Effect.RemoveFiles("/r", List.of("a.txt")), "tools.removeFiles /r a.txt"),
                row(new Effect.Unstage("/r", List.of("a.txt")), "tools.unstage /r a.txt"),
                row(new Effect.WatchRepos(List.of(NOTES), true), "tools.watchRepos Notes changes"),
                row(new Effect.ReadChange("/r", CHANGE, ChangeAsk.FOLLOW), "tools.readChange /r a.txt FOLLOW"),
                row(new Effect.SaveConnections(List.of(LOCAL)), "save.connections 1"),
                row(new Effect.SaveFolders(List.of(NOTES, NOTES)), "save.folders 2"),
                row(new Effect.SaveDecisions(List.of(new Decision(1, "work", "t", "d", 0L)), 1), "save.decisions 1"),
                row(new Effect.SaveSessions(List.of(WORK)), "save.sessions 1"),
                row(new Effect.SavePanels(List.of(new PanelState(PANEL, "SQL", "local", "")), List.of()), "save.panels 1"));
    }

    /// The effect reaches its own port method, once, with its own arguments, and nothing else is called.
    @ParameterizedTest
    @MethodSource("rows")
    void eachEffectReachesItsOwnPortMethod(final Row row) {
        final RecordingPorts ports = new RecordingPorts();
        new EffectRouter(ports, ports, ports, ports, ports, ports).handle(row.effect());
        assertThat(ports.calls()).containsExactly(row.call());
    }

    /// Every record the sealed [Effect] permits has a row above, so an effect added later is routed under test too.
    @Test
    void everyEffectHasARow() {
        final Set<Class<?>> covered = rows().map(r -> r.effect().getClass()).collect(Collectors.toSet());
        assertThat(covered).containsExactlyInAnyOrderElementsOf(Arrays.asList(Effect.class.getPermittedSubclasses()));
    }

    private static Row row(final Effect effect, final String call) {
        return new Row(effect, call);
    }

    /// An effect and the call it must make, named by the effect's type in the test report.
    record Row(Effect effect, String call) {
        @Override
        public String toString() {
            return effect.getClass().getSimpleName();
        }
    }
}
