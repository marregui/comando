package dev.marregui.comando.core.effect;

import dev.marregui.comando.core.intent.EditMode;
import dev.marregui.comando.core.model.Approval;
import dev.marregui.comando.core.model.ApprovalDecision;
import dev.marregui.comando.core.model.ConnectionSpec;
import dev.marregui.comando.core.model.FolderRoot;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.PlanUsage;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.core.view.Layout;
import dev.marregui.comando.core.view.ViewSpec;

import java.util.List;

/**
 * Work the reducer asks the adapters to do. Effects are handed out after the state transition, in
 * order, by the reducer thread; an adapter queues the work on its own threads and never blocks.
 */
public sealed interface Effect {

    // ---- database

    record OpenDb(ConnectionSpec spec) implements Effect {
    }

    record CloseDb(String name) implements Effect {
    }

    /** The connection was removed, renamed or changed: its schema no longer describes a server it reaches. */
    record ForgetSchema(String name) implements Effect {
    }

    record TestDb(ConnectionSpec spec) implements Effect {
    }

    record ExecuteSql(long requestId, ConnectionSpec spec, String sql) implements Effect {
    }

    record CancelSql(long requestId) implements Effect {
    }

    record LoadSchema(ConnectionSpec spec, boolean show) implements Effect {

        /** The title of the explorer view that shows a connection's schema. */
        public static String viewTitle(String connection) {
            return "schema · " + connection;
        }
    }

    /** The run was superseded; its rows can be freed. */
    record ReleaseResult(long requestId) implements Effect {
    }

    // ---- Claude Code

    record StartClaude(SessionSpec spec) implements Effect {
    }

    record StopClaude(String name) implements Effect {
    }

    /** Sends a prompt; {@code origin} names the panel it came from, shown on the transcript's user block. */
    record SendToClaude(String name, String text, String origin) implements Effect {
    }

    record InterruptClaude(String name) implements Effect {
    }

    /** Drops the blocks of the session's transcript, as shown on the Session tab. */
    record ClearTranscript(String name) implements Effect {
    }

    /** Loads a stored conversation's transcript for display, without starting a process. */
    record LoadHistory(SessionSpec spec) implements Effect {
    }

    record ForgetSession(String name) implements Effect {
    }

    // ---- approvals

    record ResolveApproval(Approval approval, ApprovalDecision decision) implements Effect {
    }

    // ---- UI

    record EditPanelText(PanelId panel, String text, EditMode mode) implements Effect {
    }

    record DesktopNotify(String title, String text) implements Effect {
    }

    record ForgetPanel(PanelId panel) implements Effect {
    }

    /** A panel was removed: its own file is deleted (a document's never is) and what comando held for it dropped. */
    record DeletePanel(PanelId panel) implements Effect {
    }

    record Arrange(Layout layout) implements Effect {
    }

    /** Reads a file off the EDT and shows it; {@code baseDir} resolves a relative path. */
    record ShowFile(String session, String path, String baseDir, int line) implements Effect {
    }

    // ---- development tools

    record RunGradle(long runId, String workDir, String tasks) implements Effect {
    }

    record CancelCommand(long runId) implements Effect {
    }

    /** The run's view was closed; its output can be freed. */
    record ForgetCommand(long runId) implements Effect {
    }

    /** Reads these folders once, now. */
    record ReadFolders(List<FolderRoot> folders) implements Effect {
        public ReadFolders {
            folders = List.copyOf(folders);
        }
    }

    /** Reads these folders every few seconds, and no others. */
    record WatchFolders(List<FolderRoot> folders) implements Effect {
        public WatchFolders {
            folders = List.copyOf(folders);
        }
    }

    /** Shows a folder's tree in the sidebar's Files tab, when the folder still has the settings it was read with. */
    record ShowFolder(FolderRoot folder, ViewSpec.TreeNode tree) implements Effect {
    }

    /** Shows the plan's usage limits in the status bar. */
    record ShowPlanUsage(PlanUsage usage) implements Effect {
    }

    // ---- persistence

    record SaveConnections(List<ConnectionSpec> specs) implements Effect {
        public SaveConnections {
            specs = List.copyOf(specs);
        }
    }

    record SaveSessions(List<SessionSpec> specs) implements Effect {
        public SaveSessions {
            specs = List.copyOf(specs);
        }
    }

    record SaveFolders(List<FolderRoot> folders) implements Effect {
        public SaveFolders {
            folders = List.copyOf(folders);
        }
    }

    /** The open panels, in tab order, and the closed panels of comando's own. */
    record SavePanels(List<PanelState> panels, List<PanelState> closed) implements Effect {
        public SavePanels {
            panels = List.copyOf(panels);
            closed = List.copyOf(closed);
        }
    }
}
