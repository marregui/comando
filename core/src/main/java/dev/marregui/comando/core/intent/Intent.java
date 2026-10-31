package dev.marregui.comando.core.intent;

import java.util.List;
import java.util.Map;

import dev.marregui.comando.core.dispatch.Target;
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
import dev.marregui.comando.core.state.BackgroundTask;
import dev.marregui.comando.core.state.InstancesState;
import dev.marregui.comando.core.state.LowerTab;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.core.state.SessionInfo;
import dev.marregui.comando.core.view.Layout;
import dev.marregui.comando.core.view.Placement;
import dev.marregui.comando.core.view.ViewSpec;

/**
 * Every change to the state is an intent, applied in order by the reducer on one thread. Every module sends its
 * intents to the one queue of the state loop.
 */
public sealed interface Intent {

    // ---- panels

    /**
     * Adds a panel bound to the focused one's connection and to no session, as a session has one panel; the caller
     * makes the id so the reducer stays deterministic. {@code document} marks a file opened from outside comando's
     * panels folder.
     */
    record AddPanel(PanelId panel, String title, boolean document) implements Intent {
        /** A panel of comando's own. */
        public AddPanel(final PanelId panel, final String title) {
            this(panel, title, false);
        }
    }

    /** Closes a panel: one of comando's own is kept, closed, with its file and bindings; a document is dropped. */
    record ClosePanel(PanelId panel) implements Intent {
    }

    /**
     * Removes a panel, open or closed: it leaves the list, and a panel of comando's own loses its file. A session's own
     * panel goes with its session (record 88).
     */
    record RemovePanel(PanelId panel) implements Intent {
    }

    record RenamePanel(PanelId panel, String title) implements Intent {
    }

    /**
     * Puts an open panel at {@code index} among the open ones, the others keeping their order; an index past either end
     * is the end.
     */
    record MovePanel(PanelId panel, int index) implements Intent {
    }

    /** Opens a closed panel of comando's own again, after the focused one, with its title, bindings and text. */
    record ReopenPanel(PanelId panel) implements Intent {
    }

    record FocusPanel(PanelId panel) implements Intent {
    }

    /** Binds a connection to the panel; an empty name unbinds. */
    record BindConnection(PanelId panel, String connection) implements Intent {
    }

    /**
     * Attaches a document panel to a session, or detaches it with an empty name; any number of documents may attach to
     * one session. On a panel of comando's own it opens the session's own panel instead, as {@link OpenSession} does
     * (record 88).
     */
    record BindSession(PanelId panel, String session) implements Intent {
    }

    /**
     * Opens a session's own panel, as a file opens in an editor: the panel comes forward, reopened when closed; else a
     * panel of comando's own with no session and the session's name becomes it; else a new panel {@code newPanel},
     * titled after the session and bound to it and to {@code target}'s connection. Another panel is never taken
     * (record 88). The caller makes the new id, so the reducer stays deterministic.
     */
    record OpenSession(String session, PanelId target, PanelId newPanel) implements Intent {
    }

    // ---- running text

    /**
     * Runs text from a panel: SQL to its connection, anything else to its session; a null panel means the focused one.
     */
    record RunText(PanelId panel, String text, Target target) implements Intent {
    }

    /** Runs {@code sql} again in the panel without moving the lower panel to Results: a live plot's refresh. */
    record RefreshSql(PanelId panel, String sql) implements Intent {
    }

    /** Replaces the content of a view the user placed without selecting its tab: a live plot's new data. */
    record RefreshView(String title, ViewSpec spec) implements Intent {
    }

    /** Stops the panel's running SQL, or interrupts its session's turn when no SQL runs. */
    record CancelRun(PanelId panel) implements Intent {
    }

    // ---- SQL run events from the database adapter

    record SqlStarted(long requestId) implements Intent {
    }

    record SqlRows(long requestId, int rows, long execMillis, long fetchMillis, long totalMillis) implements Intent {
    }

    record SqlCompleted(long requestId, int rows, long updateCount, boolean truncated, long execMillis, long fetchMillis, long totalMillis) implements Intent {
    }

    record SqlFailed(long requestId, String message, long totalMillis) implements Intent {
    }

    record SqlCancelled(long requestId) implements Intent {
    }

    /** The block held more statements than the one shown: how many ran, or that a later one failed. */
    record SqlWarning(long requestId, boolean failed, String text) implements Intent {
    }

    // ---- connections

    /** Adds a connection, or replaces the one called {@code previousName} (empty for a new one). */
    record SaveConnection(String previousName, ConnectionSpec spec) implements Intent {
    }

    record RemoveConnection(String name) implements Intent {
    }

    /** Adds a folder to the Files tab, as the kit does; refused when one has its name. */
    record AddFolder(FolderRoot spec) implements Intent {
    }

    /**
     * The Files tab's folders as the folders window saves them, all of them in order; {@code base} is the list the
     * window started from, so a change to {@code folders.json} made while it was open is said to be replaced.
     */
    record SaveFolders(List<FolderRoot> folders, List<FolderRoot> base) implements Intent {
        public SaveFolders {
            folders = List.copyOf(folders);
            base = List.copyOf(base);
        }
    }

    /** The Files tab's folders as {@code folders.json} now has them, read again after it changed. */
    record FoldersRead(List<FolderRoot> folders) implements Intent {
        public FoldersRead {
            folders = List.copyOf(folders);
        }
    }

    record OpenConnection(String name) implements Intent {
    }

    record CloseConnection(String name) implements Intent {
    }

    record TestConnection(ConnectionSpec spec) implements Intent {
    }

    record ConnectionOpened(String name) implements Intent {
    }

    record ConnectionClosed(String name) implements Intent {
    }

    record ConnectionFailed(String name, String error) implements Intent {
    }

    /** Reads the connection's tables and columns and shows them as a tree. */
    record ExploreSchema(String connection) implements Intent {
    }

    /**
     * A statement that changes the schema ran outside a panel (a session's {@code db_run_sql}): the schema behind
     * completion reloads, and an open explorer of the connection shows it again.
     */
    record SchemaChanged(String connection) implements Intent {
    }

    /** The health check found an open connection no longer valid. */
    record ConnectionLost(String name, String error) implements Intent {
    }

    record ConnectionTested(String name, boolean ok, String message) implements Intent {
    }

    // ---- sessions

    /** Adds a session, or replaces the one called {@code previousName} (empty for a new one). */
    record SaveSession(String previousName, SessionSpec spec) implements Intent {
    }

    record RemoveSession(String name) implements Intent {
    }

    /** Adds sessions found in the catalogue; a name or a conversation already present is skipped. */
    record ImportSessions(List<SessionSpec> specs) implements Intent {
        public ImportSessions {
            specs = List.copyOf(specs);
        }
    }

    record StartSession(String name) implements Intent {
    }

    record StopSession(String name) implements Intent {
    }

    /** Starts a stopped session on a new conversation; the old one stays in Claude Code's files, importable again. */
    record StartFresh(String name) implements Intent {
    }

    /**
     * The Checkpoint button: sends the session's checkpoint command as a prompt from its panel; when that turn has
     * finished, {@code /compact} (record 129).
     */
    record Checkpoint(String name) implements Intent {
    }

    /** Sends a Claude Code command ({@code /compact}) as a prompt from the session's panel. */
    record SessionCommand(String name, String command) implements Intent {
    }

    /** Puts the sessions in this order; a session the list leaves out keeps its place after the named ones. */
    record OrderSessions(List<String> names) implements Intent {
        public OrderSessions {
            names = List.copyOf(names);
        }
    }

    record InterruptSession(String name) implements Intent {
    }

    /** Empties the session's Session tab; the conversation is untouched. Refused while a turn runs. */
    record ClearTranscript(String name) implements Intent {
    }

    /** Changes the model; a running session also receives {@code /model <model>}. */
    record SetSessionModel(String name, String model) implements Intent {
    }

    /** Changes the effort; a running session also receives {@code /effort <effort>}. */
    record SetSessionEffort(String name, String effort) implements Intent {
    }

    record SessionStarted(String name) implements Intent {
    }

    /**
     * A message of the session's running turn ran at {@code model} and {@code effort}, as the session's transcript
     * records it; {@code firstOfTurn} for the turn's first message, which runs at the session's own setting.
     */
    record SessionRanAt(String name, String model, String effort, boolean firstOfTurn) implements Intent {
    }

    record SessionInit(String name, SessionInfo info) implements Intent {
    }

    record SessionActivity(String name, String activity) implements Intent {
    }

    /**
     * The background work the session has open now, the whole set as Claude Code reports it at each change: agents and
     * shell commands left running past their turn. Start times are the reducer's ({@code startedMillis} is ignored).
     */
    record SessionTasksChanged(String name, List<BackgroundTask> open) implements Intent {
        public SessionTasksChanged {
            open = List.copyOf(open);
        }
    }

    /**
     * The session's process is running a turn: it called the model for the main conversation. A turn comando did not
     * send (Claude Code starts one when background work it waited on ends) counts as running from here to its result.
     */
    record SessionTurnRunning(String name) implements Intent {
    }

    /**
     * A {@code result} event ended a turn.
     *
     * @param queued        turns Claude Code still holds queued
     * @param costUsd       the process's spend so far; negative when unknown (the previous value stands)
     * @param contextTokens the context size: input tokens of the turn's last API call; 0 when unknown after a
     *                      compaction; negative when unknown (the previous value stands)
     * @param contextWindow the model's context window, 0 when the event did not carry it
     * @param result        the turn's last answer, as the event carries it; empty for a failed turn or one without text
     */
    record SessionTurnEnded(String name, int queued, double costUsd, long contextTokens, long contextWindow, boolean error, String message,
                            String result) implements Intent {

        public SessionTurnEnded {
            result = result == null ? "" : result;
        }

        /** A turn that ended without an answer to read: it failed before one, or nothing ran. */
        public SessionTurnEnded(final String name, final int queued, final double costUsd, final long contextTokens, final long contextWindow, final boolean error, final String message) {
            this(name, queued, costUsd, contextTokens, contextWindow, error, message, "");
        }
    }

    record SessionExited(String name, int exitCode, String error) implements Intent {
    }

    record SessionBranch(String name, String branch) implements Intent {
    }

    /**
     * How full session {@code name}'s context is now: after each model call of a turn, and when its stored conversation
     * is loaded (record 120). {@code tokens} or {@code window} at 0 or below leave the one known.
     */
    record SessionContext(String name, long tokens, long window) implements Intent {
    }

    // ---- approvals

    record ApprovalRequested(Approval approval) implements Intent {
    }

    record ApprovalAnswered(long id, ApprovalDecision decision) implements Intent {
    }

    /** The waiting call ended without an answer (timeout, session gone). */
    record ApprovalWithdrawn(long id, String reason) implements Intent {
    }

    // ---- views

    /** Opens a view, or replaces an unpinned view with the same title and placement from the same session. */
    record ShowView(String session, String title, ViewSpec spec, Placement placement) implements Intent {
    }

    record CloseView(long id) implements Intent {
    }

    record PinView(long id, boolean pinned) implements Intent {
    }

    record SelectLowerTab(LowerTab tab) implements Intent {
    }

    /** Shows or hides panes and moves the split; the UI keeps these values outside the state. */
    record Arrange(Layout layout) implements Intent {
    }

    /**
     * A session arranges the window: it may focus a panel bound to itself, select a lower tab while the user
     * looks at its panel, and show or hide panes. Null and {@link Layout#KEEP} parts change nothing.
     */
    record SessionLayout(String session, PanelId panel, LowerTab tab, Layout layout) implements Intent {
    }

    /**
     * A session's focus request applied once the user paused typing: focuses {@code panel} and selects {@code tab}
     * (null keeps it) only if the panel is still bound to {@code session}.
     */
    record SessionFocus(String session, PanelId panel, LowerTab tab) implements Intent {
    }

    // ---- editor content from Claude

    /**
     * A prompt to a session through its own panel, the one the window gives it (record 88): the text is appended to
     * that panel and run there, as if typed. Resolved in the reducer, so a session created by the intent just before
     * gets it.
     *
     * @param from the session or client that sent it, announced like a session's write
     */
    record PromptSession(String session, String text, String from) implements Intent {
    }

    /**
     * Puts text in a panel's editor; a null panel means the focused one.
     *
     * @param session the session that wrote it, empty for the user's own command; a session's write is announced
     */
    record EditPanel(PanelId panel, String text, EditMode mode, String session) implements Intent {
        public EditPanel {
            session = session == null ? "" : session;
        }

        public EditPanel(final PanelId panel, final String text, final EditMode mode) {
            this(panel, text, mode, "");
        }
    }

    /** Shows a file read-only in the side pane; a relative path resolves against the session's working directory. */
    record OpenFile(String session, String path, int line) implements Intent {
    }

    /** Shows the conversation of the sub-agent that the session's Agent call {@code toolUseId} started. */
    record OpenSubagent(String session, String toolUseId) implements Intent {
    }

    // ---- development tools

    /**
     * Runs Gradle tasks in the build that holds the working folder of the panel's session, else the home folder; a null
     * panel means the focused one.
     */
    record RunGradle(PanelId panel, String tasks) implements Intent {
    }

    /**
     * A command run ended.
     *
     * @param tests    test results found after a build, {@link ViewSpec.TreeNode#NONE} when none
     * @param failures the text of each failed test, by its name
     */
    record CommandFinished(long runId, int exitCode, long millis, boolean cancelled, ViewSpec.TreeNode tests,
                           Map<String, String> failures) implements Intent {
        public CommandFinished {
            failures = Map.copyOf(failures);
        }
    }

    record CancelCommand(long runId) implements Intent {
    }

    /** Reads the named folders of the Files tab once, now: a refresh, or a filter over folders that are not open. */
    record BrowseFolders(List<String> names) implements Intent {
        public BrowseFolders {
            names = List.copyOf(names);
        }
    }

    /** The Files tab's open folders: these are read every few seconds, the others not at all. */
    record WatchFolders(List<String> names) implements Intent {
        public WatchFolders {
            names = List.copyOf(names);
        }
    }

    /**
     * A folder of the Files tab as read, with the settings it was read with: a tree read with settings since edited
     * is not shown under the edited folder.
     */
    record FolderLoaded(FolderRoot folder, ViewSpec.TreeNode tree) implements Intent {
    }

    /**
     * The git repositories watched, each as read: those of the open Files tab folders, and of every folder while the
     * Changes view is open.
     */
    record ReposLoaded(List<RepoStatus> repos) implements Intent {
        public ReposLoaded {
            repos = List.copyOf(repos);
        }
    }

    /** Compares a changed file with its {@code HEAD} version, side by side; {@code ask} says why. */
    record OpenChange(String root, RepoStatus.FileChange change, ChangeAsk ask) implements Intent {
    }

    /** A changed file's two versions as read, to be shown side by side as {@code ask} says. */
    record ChangeRead(ChangeTexts texts, ChangeAsk ask) implements Intent {
    }

    /**
     * Moves files and subfolders of the Files tab's folder {@code folder} to the desktop's Trash, full paths; refused
     * unless the folder is deletable and every path is inside it, never the folder itself.
     */
    record DeleteFiles(String folder, List<String> paths) implements Intent {
        public DeleteFiles {
            paths = List.copyOf(paths);
        }
    }

    /**
     * Takes {@code changes} of the repository at {@code root} back to their {@code HEAD} state, in the working tree and
     * the index: a changed or removed file comes back as committed, its changed version first moved to the Trash; a new
     * file, untracked or added, goes to the Trash. Paths relative to {@code root}; refused when one leaves it.
     */
    record RollBack(String root, List<RepoStatus.FileChange> changes) implements Intent {
        public RollBack {
            changes = List.copyOf(changes);
        }
    }

    /**
     * Moves files of the repository at {@code root} to the desktop's Trash; paths relative to it, a new file also
     * unstaged.
     */
    record RemoveFiles(String root, List<String> paths) implements Intent {
        public RemoveFiles {
            paths = List.copyOf(paths);
        }
    }

    /**
     * Takes {@code paths} of the repository at {@code root} out of the index, back to {@code HEAD}'s; the files stay as
     * they are.
     */
    record Unstage(String root, List<String> paths) implements Intent {
        public Unstage {
            paths = List.copyOf(paths);
        }
    }

    /** The comando instances on the comando Edge and the questions between them, read again after any change (record 124). */
    record InstancesChanged(InstancesState instances) implements Intent {
    }

    /** The plan's usage limits as read from Claude Code, every few minutes. */
    record PlanUsageRead(PlanUsage usage) implements Intent {
    }

    // ---- notices

    /** A notice; {@code subject} is one of {@link Notice}'s subjects, or empty. */
    record Notify(Notice.Level level, String text, boolean desktop, String subject) implements Intent {
        public Notify {
            subject = subject == null ? "" : subject;
        }

        public Notify(final Notice.Level level, final String text, final boolean desktop) {
            this(level, text, desktop, "");
        }

        /**
         * A failure told twice: in comando's log, an error for an error and a warning otherwise, with the stack of
         * {@code cause} when there is one; and as the notice {@code text}, a click on which opens the log at that
         * entry. The time is read before the entry is written, as {@link Notice#log} needs.
         */
        public static Notify logged(final System.Logger log, final Notice.Level level, final String text, final Throwable cause) {
            final long at = System.currentTimeMillis();
            final System.Logger.Level as = level == Notice.Level.ERROR ? System.Logger.Level.ERROR : System.Logger.Level.WARNING;
            if (cause == null) {
                log.log(as, text);
            } else {
                log.log(as, text, cause);
            }
            return new Notify(level, text, false, Notice.log(at));
        }
    }

    /** The user dismissed the status bar's notice. */
    record DismissNotice() implements Intent {
    }
}
