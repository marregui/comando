package dev.marregui.comando.core.effect;

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
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.core.view.Layout;
import dev.marregui.comando.core.view.ViewSpec;

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
        public static String viewTitle(final String connection) {
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

    /**
     * Sends a prompt; {@code origin} names the panel it came from, shown on the transcript's user block. A non-empty
     * {@code preface}, the summary a checkpoint carried, goes to Claude in front of the text, and is not shown again on
     * the user block.
     */
    record SendToClaude(String name, String text, String origin, String preface) implements Effect {

        public SendToClaude(final String name, final String text, final String origin) {
            this(name, text, origin, "");
        }
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

    /**
     * Reads, off the EDT, the stored conversation of the sub-agent that the Agent call {@code toolUseId} started, and
     * shows it.
     */
    record ShowSubagent(String name, String claudeSessionId, String toolUseId) implements Effect {
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

    /** Moves {@code paths}, inside {@code folder}, to the desktop's Trash; never deletes for good. */
    record DeleteFiles(FolderRoot folder, List<String> paths) implements Effect {
        public DeleteFiles {
            paths = List.copyOf(paths);
        }
    }

    /**
     * Takes {@code changes} of the repository at {@code root} back to {@code HEAD}: changed versions to the Trash
     * first, new files to the Trash.
     */
    record RollBack(String root, List<RepoStatus.FileChange> changes) implements Effect {
        public RollBack {
            changes = List.copyOf(changes);
        }
    }

    /** Moves {@code paths}, relative to the repository at {@code root}, to the desktop's Trash. */
    record RemoveFiles(String root, List<String> paths) implements Effect {
        public RemoveFiles {
            paths = List.copyOf(paths);
        }
    }

    /** Takes {@code paths}, relative to the repository at {@code root}, out of its index. */
    record Unstage(String root, List<String> paths) implements Effect {
        public Unstage {
            paths = List.copyOf(paths);
        }
    }

    /** Shows a folder's tree in the sidebar's Files tab, when the folder still has the settings it was read with. */
    record ShowFolder(FolderRoot folder, ViewSpec.TreeNode tree) implements Effect {
    }

    /**
     * Reads from now on the git repositories of {@code folders}, every configured folder: those of the open folders,
     * and of all while {@code changesShown}, every few seconds; the others less often.
     */
    record WatchRepos(List<FolderRoot> folders, boolean changesShown) implements Effect {
        public WatchRepos {
            folders = List.copyOf(folders);
        }
    }

    /** Reads a changed file's two versions, {@code HEAD}'s and the working tree's. */
    record ReadChange(String root, RepoStatus.FileChange change, ChangeAsk ask) implements Effect {
    }

    /** Shows the watched repositories' changes: the Changes view, and the Files tab's colours. */
    record ShowRepos(List<RepoStatus> repos) implements Effect {
        public ShowRepos {
            repos = List.copyOf(repos);
        }
    }

    /** Shows a changed file's two versions side by side, as {@code ask} says. */
    record ShowChange(ChangeTexts texts, ChangeAsk ask) implements Effect {
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
