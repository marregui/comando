package dev.marregui.comando.core.intent;

import dev.marregui.comando.core.dispatch.Target;
import dev.marregui.comando.core.model.Approval;
import dev.marregui.comando.core.model.ApprovalDecision;
import dev.marregui.comando.core.model.ConnectionSpec;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.state.LowerTab;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.core.state.SessionInfo;
import dev.marregui.comando.core.view.Layout;
import dev.marregui.comando.core.view.Placement;
import dev.marregui.comando.core.view.ViewSpec;

import java.util.List;
import java.util.Map;

/**
 * Every change to the state is an intent, applied in order by the reducer on one thread. The UI,
 * the database adapter, the Claude adapter and the MCP server all send intents to the same queue.
 */
public sealed interface Intent {

    // ---- panels

    /** Adds a panel bound like the focused one; the caller makes the id so the reducer stays deterministic. */
    record AddPanel(PanelId panel, String title) implements Intent {
    }

    record ClosePanel(PanelId panel) implements Intent {
    }

    record RenamePanel(PanelId panel, String title) implements Intent {
    }

    /** Opens a closed panel again with its title and bindings; its text is already back in the panel store. */
    record ReopenPanel(PanelId panel, String title, String connection, String session) implements Intent {
    }

    record FocusPanel(PanelId panel) implements Intent {
    }

    /** Binds a connection to the panel; an empty name unbinds. */
    record BindConnection(PanelId panel, String connection) implements Intent {
    }

    /** Binds a session to the panel; an empty name unbinds. */
    record BindSession(PanelId panel, String session) implements Intent {
    }

    // ---- running text

    /** Runs text from a panel: SQL to its connection, anything else to its session; a null panel means the focused one. */
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

    /** Adds sessions found in the catalogue; names already present are skipped. */
    record ImportSessions(List<SessionSpec> specs) implements Intent {
        public ImportSessions {
            specs = List.copyOf(specs);
        }
    }

    record StartSession(String name) implements Intent {
    }

    record StopSession(String name) implements Intent {
    }

    record InterruptSession(String name) implements Intent {
    }

    /** Changes the model; a running session also receives {@code /model <model>}. */
    record SetSessionModel(String name, String model) implements Intent {
    }

    /** Changes the effort; a running session also receives {@code /effort <effort>}. */
    record SetSessionEffort(String name, String effort) implements Intent {
    }

    record SessionStarted(String name) implements Intent {
    }

    record SessionInit(String name, SessionInfo info) implements Intent {
    }

    record SessionActivity(String name, String activity) implements Intent {
    }

    /**
     * A {@code result} event ended a turn.
     *
     * @param queued        turns Claude Code still holds queued
     * @param costUsd       the process's spend so far; negative when unknown (the previous value stands)
     * @param contextTokens the context size: input tokens of the turn's last API call; 0 when unknown after a
     *                      compaction; negative when unknown (the previous value stands)
     * @param contextWindow the model's context window, 0 when the event did not carry it
     */
    record SessionTurnEnded(String name, int queued, double costUsd, long contextTokens, long contextWindow, boolean error, String message) implements Intent {
    }

    record SessionExited(String name, int exitCode, String error) implements Intent {
    }

    record SessionBranch(String name, String branch) implements Intent {
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

    /** Opens a view, or replaces an unpinned view with the same title from the same session. */
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
     * Puts text in a panel's editor; a null panel means the focused one.
     *
     * @param session the session that wrote it, empty for the user's own command; a session's write is announced
     */
    record EditPanel(PanelId panel, String text, EditMode mode, String session) implements Intent {
        public EditPanel {
            session = session == null ? "" : session;
        }

        public EditPanel(PanelId panel, String text, EditMode mode) {
            this(panel, text, mode, "");
        }
    }

    /** Shows a file read-only in the side pane; a relative path resolves against the session's working directory. */
    record OpenFile(String session, String path, int line) implements Intent {
    }

    // ---- development tools

    /** Runs Gradle tasks in the build of the panel's session; a null panel means the focused one. */
    record RunGradle(PanelId panel, String tasks) implements Intent {
    }

    /** Runs jfrq with {@code args} on a recording. */
    record RunJfrq(String recording, String args) implements Intent {
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

    /** Reads a JFR recording into a summary table and plots. */
    record OpenRecording(String path) implements Intent {
    }

    record BrowseScratchpad() implements Intent {
    }

    // ---- notices

    /** A notice; {@code subject} is one of {@link Notice}'s subjects, or empty. */
    record Notify(Notice.Level level, String text, boolean desktop, String subject) implements Intent {
        public Notify {
            subject = subject == null ? "" : subject;
        }

        public Notify(Notice.Level level, String text, boolean desktop) {
            this(level, text, desktop, "");
        }
    }

    /** The user dismissed the status bar's notice. */
    record DismissNotice() implements Intent {
    }
}
