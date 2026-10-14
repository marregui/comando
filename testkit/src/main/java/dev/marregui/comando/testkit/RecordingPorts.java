package dev.marregui.comando.testkit;

import dev.marregui.comando.core.intent.EditMode;
import dev.marregui.comando.core.model.Approval;
import dev.marregui.comando.core.model.ApprovalDecision;
import dev.marregui.comando.core.model.ConnectionSpec;
import dev.marregui.comando.core.model.PanelId;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One fake for every port; each call is recorded as a line such as {@code db.execute 7 local SELECT 1}. */
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

    private void record(String line) {
        calls.add(line);
    }

    @Override
    public void open(ConnectionSpec spec) {
        record("db.open " + spec.name());
    }

    @Override
    public void close(String name) {
        record("db.close " + name);
    }

    @Override
    public void test(ConnectionSpec spec) {
        record("db.test " + spec.name());
    }

    @Override
    public void execute(long requestId, ConnectionSpec spec, String sql) {
        record("db.execute " + requestId + ' ' + spec.name() + ' ' + sql);
    }

    @Override
    public void cancel(long requestId) {
        record("db.cancel " + requestId);
    }

    @Override
    public void release(long requestId) {
        record("db.release " + requestId);
    }

    @Override
    public void start(SessionSpec spec) {
        record("claude.start " + spec.name());
    }

    @Override
    public void stop(String name) {
        record("claude.stop " + name);
    }

    @Override
    public void send(String name, String text, String origin) {
        record("claude.send " + name + ' ' + text);
    }

    @Override
    public void interrupt(String name) {
        record("claude.interrupt " + name);
    }

    @Override
    public void loadHistory(SessionSpec spec) {
        record("claude.history " + spec.name());
    }

    @Override
    public void forget(String name) {
        record("claude.forget " + name);
    }

    @Override
    public void resolve(Approval approval, ApprovalDecision decision) {
        record("approval.resolve " + approval.id() + ' ' + decision);
    }

    @Override
    public void editPanel(PanelId panel, String text, EditMode mode) {
        record("ui.edit " + mode + ' ' + text);
    }

    @Override
    public void notifyDesktop(String title, String text) {
        record("ui.notify " + title + ": " + text);
    }

    @Override
    public void forgetPanel(PanelId panel) {
        record("ui.forget " + panel);
    }

    @Override
    public void showFile(String session, String path, String baseDir, int line) {
        record("ui.file " + baseDir + ' ' + path + ':' + line);
    }

    @Override
    public void arrange(Layout layout) {
        record("ui.arrange " + layout);
    }

    @Override
    public void showScratchpad(ViewSpec.TreeNode root) {
        record("ui.scratchpad " + root.label());
    }

    @Override
    public void loadSchema(ConnectionSpec spec, boolean show) {
        record("db.schema " + spec.name() + (show ? " show" : ""));
    }

    // ---- ToolPort

    @Override
    public void runGradle(long runId, String workDir, String tasks) {
        record("tools.gradle " + runId + ' ' + workDir + ' ' + tasks);
    }

    @Override
    public void runJfrq(long runId, String recording, String args) {
        record("tools.jfrq " + runId + ' ' + recording + ' ' + args);
    }

    @Override
    public void cancelRun(long runId) {
        record("tools.cancel " + runId);
    }

    @Override
    public void forgetRun(long runId) {
        record("tools.forget " + runId);
    }

    @Override
    public void openRecording(String path) {
        record("tools.recording " + path);
    }

    @Override
    public void browseScratchpad() {
        record("tools.scratchpad");
    }

    @Override
    public void saveConnections(List<ConnectionSpec> specs) {
        record("save.connections " + specs.size());
    }

    @Override
    public void saveSessions(List<SessionSpec> specs) {
        record("save.sessions " + specs.size());
    }

    @Override
    public void savePanels(List<PanelState> panels) {
        record("save.panels " + panels.size());
    }
}
