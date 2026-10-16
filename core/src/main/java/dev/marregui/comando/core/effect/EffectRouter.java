package dev.marregui.comando.core.effect;

import dev.marregui.comando.core.loop.StateLoop;
import dev.marregui.comando.core.port.ApprovalPort;
import dev.marregui.comando.core.port.ClaudePort;
import dev.marregui.comando.core.port.DatabasePort;
import dev.marregui.comando.core.port.PersistencePort;
import dev.marregui.comando.core.port.ToolPort;
import dev.marregui.comando.core.port.UiPort;

/** Hands each effect to the port that does it. Runs on the reducer thread; ports only enqueue. */
public final class EffectRouter implements StateLoop.EffectHandler {

    private final DatabasePort db;
    private final ClaudePort claude;
    private final ApprovalPort approvals;
    private final UiPort ui;
    private final PersistencePort persistence;
    private final ToolPort tools;

    public EffectRouter(DatabasePort db, ClaudePort claude, ApprovalPort approvals, UiPort ui, PersistencePort persistence, ToolPort tools) {
        this.db = db;
        this.claude = claude;
        this.approvals = approvals;
        this.ui = ui;
        this.persistence = persistence;
        this.tools = tools;
    }

    @Override
    public void handle(Effect effect) {
        switch (effect) {
            case Effect.OpenDb e -> db.open(e.spec());
            case Effect.CloseDb e -> db.close(e.name());
            case Effect.ForgetSchema e -> db.forgetSchema(e.name());
            case Effect.TestDb e -> db.test(e.spec());
            case Effect.ExecuteSql e -> db.execute(e.requestId(), e.spec(), e.sql());
            case Effect.CancelSql e -> db.cancel(e.requestId());
            case Effect.ReleaseResult e -> db.release(e.requestId());
            case Effect.LoadSchema e -> db.loadSchema(e.spec(), e.show());
            case Effect.StartClaude e -> claude.start(e.spec());
            case Effect.StopClaude e -> claude.stop(e.name());
            case Effect.SendToClaude e -> claude.send(e.name(), e.text(), e.origin());
            case Effect.InterruptClaude e -> claude.interrupt(e.name());
            case Effect.ClearTranscript e -> claude.clearTranscript(e.name());
            case Effect.LoadHistory e -> claude.loadHistory(e.spec());
            case Effect.ForgetSession e -> claude.forget(e.name());
            case Effect.ResolveApproval e -> approvals.resolve(e.approval(), e.decision());
            case Effect.EditPanelText e -> ui.editPanel(e.panel(), e.text(), e.mode());
            case Effect.DesktopNotify e -> ui.notifyDesktop(e.title(), e.text());
            case Effect.ForgetPanel e -> ui.forgetPanel(e.panel());
            case Effect.DeletePanel e -> ui.deletePanel(e.panel());
            case Effect.Arrange e -> ui.arrange(e.layout());
            case Effect.ShowFile e -> ui.showFile(e.session(), e.path(), e.baseDir(), e.line());
            case Effect.ShowFolder e -> ui.showFolder(e.folder(), e.tree());
            case Effect.ShowPlanUsage e -> ui.showPlanUsage(e.usage());
            case Effect.RunGradle e -> tools.runGradle(e.runId(), e.workDir(), e.tasks());
            case Effect.RunJfrq e -> tools.runJfrq(e.runId(), e.recording(), e.args());
            case Effect.CancelCommand e -> tools.cancelRun(e.runId());
            case Effect.ForgetCommand e -> tools.forgetRun(e.runId());
            case Effect.OpenRecording e -> tools.openRecording(e.path());
            case Effect.ReadFolders e -> tools.readFolders(e.folders());
            case Effect.WatchFolders e -> tools.watchFolders(e.folders());
            case Effect.CopyFile e -> tools.copyFile(e.from(), e.to());
            case Effect.DeleteFile e -> tools.deleteFile(e.file(), e.toTrash());
            case Effect.SaveConnections e -> persistence.saveConnections(e.specs());
            case Effect.SaveFolders e -> persistence.saveFolders(e.folders());
            case Effect.SaveSessions e -> persistence.saveSessions(e.specs());
            case Effect.SavePanels e -> persistence.savePanels(e.panels(), e.closed());
        }
    }
}
