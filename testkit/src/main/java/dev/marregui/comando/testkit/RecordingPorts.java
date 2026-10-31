package dev.marregui.comando.testkit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import dev.marregui.comando.core.intent.EditMode;
import dev.marregui.comando.core.model.Approval;
import dev.marregui.comando.core.model.ApprovalDecision;
import dev.marregui.comando.core.model.ChangeAsk;
import dev.marregui.comando.core.model.ChangeTexts;
import dev.marregui.comando.core.model.ConnectionSpec;
import dev.marregui.comando.core.model.FolderRoot;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.PlanUsage;
import dev.marregui.comando.core.model.RepoStatus;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.port.ApprovalPort;
import dev.marregui.comando.core.port.ClaudePort;
import dev.marregui.comando.core.port.DatabasePort;
import dev.marregui.comando.core.port.PersistencePort;
import dev.marregui.comando.core.port.ToolPort;
import dev.marregui.comando.core.port.UiPort;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.core.view.Layout;
import dev.marregui.comando.core.view.ViewSpec;

/**
 * One fake for each of the six effect ports; each call is recorded as a line such as
 * {@code db.execute 7 local SELECT 1}.
 */
public final class RecordingPorts implements DatabasePort, ClaudePort, ApprovalPort, UiPort, PersistencePort, ToolPort {

    private final List<String> calls = Collections.synchronizedList(new ArrayList<>());

    public List<String> calls() {
        synchronized (calls) {
            return List.copyOf(calls);
        }
    }

    public void clear() {
        calls.clear();
    }

    private void record(final String line) {
        calls.add(line);
    }

    @Override
    public void open(final ConnectionSpec spec) {
        record("db.open " + spec.name());
    }

    @Override
    public void close(final String name) {
        record("db.close " + name);
    }

    @Override
    public void forgetSchema(final String name) {
        record("db.forgetSchema " + name);
    }

    @Override
    public void test(final ConnectionSpec spec) {
        record("db.test " + spec.name());
    }

    @Override
    public void execute(final long requestId, final ConnectionSpec spec, final String sql) {
        record("db.execute " + requestId + ' ' + spec.name() + ' ' + sql);
    }

    @Override
    public void cancel(final long requestId) {
        record("db.cancel " + requestId);
    }

    @Override
    public void release(final long requestId) {
        record("db.release " + requestId);
    }

    @Override
    public void start(final SessionSpec spec) {
        record("claude.start " + spec.name());
    }

    @Override
    public void stop(final String name) {
        record("claude.stop " + name);
    }

    @Override
    public void send(final String name, final String text, final String origin, final String preface) {
        record("claude.send " + name + ' ' + (preface.isEmpty() ? "" : "[preface] ") + text);
    }

    @Override
    public void interrupt(final String name) {
        record("claude.interrupt " + name);
    }

    @Override
    public void clearTranscript(final String name) {
        record("claude.clearTranscript " + name);
    }

    @Override
    public void loadHistory(final SessionSpec spec) {
        record("claude.history " + spec.name());
    }

    @Override
    public void forget(final String name) {
        record("claude.forget " + name);
    }

    @Override
    public void showSubagent(final String name, final String claudeSessionId, final String toolUseId) {
        record("claude.showSubagent " + name + ' ' + claudeSessionId + ' ' + toolUseId);
    }

    @Override
    public void resolve(final Approval approval, final ApprovalDecision decision) {
        record("approval.resolve " + approval.id() + ' ' + decision);
    }

    @Override
    public void editPanel(final PanelId panel, final String text, final EditMode mode) {
        record("ui.edit " + mode + ' ' + text);
    }

    @Override
    public void notifyDesktop(final String title, final String text) {
        record("ui.notify " + title + ": " + text);
    }

    @Override
    public void forgetPanel(final PanelId panel) {
        record("ui.forget " + panel);
    }

    @Override
    public void deletePanel(final PanelId panel) {
        record("ui.delete " + panel);
    }

    @Override
    public void showFile(final String session, final String path, final String baseDir, final int line) {
        record("ui.file " + baseDir + ' ' + path + ':' + line);
    }

    @Override
    public void arrange(final Layout layout) {
        record("ui.arrange " + layout);
    }

    @Override
    public void showFolder(final FolderRoot folder, final ViewSpec.TreeNode tree) {
        record("ui.folder " + folder.name());
    }

    @Override
    public void showRepos(final List<RepoStatus> repos) {
        record("ui.repos " + String.join(",", repos.stream().map(RepoStatus::name).toList()));
    }

    @Override
    public void showChange(final ChangeTexts texts, final ChangeAsk ask) {
        record("ui.change " + texts.change().path() + " " + ask);
    }

    @Override
    public void showPlanUsage(final PlanUsage usage) {
        record("ui.planUsage " + usage.windows().size());
    }

    @Override
    public void loadSchema(final ConnectionSpec spec, final boolean show) {
        record("db.schema " + spec.name() + (show ? " show" : ""));
    }

    // ---- ToolPort

    @Override
    public void runGradle(final long runId, final String workDir, final String tasks) {
        record("tools.gradle " + runId + ' ' + workDir + ' ' + tasks);
    }

    @Override
    public void cancelRun(final long runId) {
        record("tools.cancel " + runId);
    }

    @Override
    public void forgetRun(final long runId) {
        record("tools.forget " + runId);
    }

    @Override
    public void readFolders(final List<FolderRoot> folders) {
        record("tools.readFolders " + names(folders));
    }

    @Override
    public void watchFolders(final List<FolderRoot> folders) {
        record("tools.watchFolders " + names(folders));
    }

    @Override
    public void watchRepos(final List<FolderRoot> folders, final boolean changesShown) {
        record("tools.watchRepos " + names(folders) + (changesShown ? " changes" : ""));
    }

    @Override
    public void readChange(final String root, final RepoStatus.FileChange change, final ChangeAsk ask) {
        record("tools.readChange " + root + " " + change.path() + " " + ask);
    }

    @Override
    public void deleteFiles(final FolderRoot folder, final List<String> paths) {
        record("tools.deleteFiles " + folder.name() + " " + String.join(",", paths));
    }

    @Override
    public void rollBack(final String root, final List<RepoStatus.FileChange> changes) {
        record("tools.rollBack " + root + " " + String.join(",", changes.stream().map(RepoStatus.FileChange::path).toList()));
    }

    @Override
    public void removeFiles(final String root, final List<String> paths) {
        record("tools.removeFiles " + root + " " + String.join(",", paths));
    }

    @Override
    public void unstage(final String root, final List<String> paths) {
        record("tools.unstage " + root + " " + String.join(",", paths));
    }

    private static String names(final List<FolderRoot> folders) {
        return String.join(",", folders.stream().map(FolderRoot::name).toList());
    }

    @Override
    public void saveConnections(final List<ConnectionSpec> specs) {
        record("save.connections " + specs.size());
    }

    @Override
    public void saveSessions(final List<SessionSpec> specs) {
        record("save.sessions " + specs.size());
    }

    @Override
    public void saveFolders(final List<FolderRoot> folders) {
        record("save.folders " + folders.size());
    }

    @Override
    public void savePanels(final List<PanelState> panels, final List<PanelState> closed) {
        record("save.panels " + panels.size());
    }
}
