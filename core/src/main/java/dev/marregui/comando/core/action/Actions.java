package dev.marregui.comando.core.action;

import dev.marregui.comando.core.action.Action.Arg;
import dev.marregui.comando.core.action.Action.Kind;
import dev.marregui.comando.core.action.Action.Type;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.SessionSpec;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The registry of user actions (record 121): every action a person has in the window, as an agent's MCP tool, and every
 * kind of {@link Intent} classified as sent by an action, an event (sent by an adapter, never by a person), or not yet
 * offered, with the reason. {@code ActionParityTest} keeps the classification whole and checks that every intent the
 * window sends is offered.
 */
public final class Actions {

    private static final Arg SESSION = Arg.required("session", Type.STRING, "the session's name");
    private static final Arg PANEL = Arg.required("panel", Type.STRING, "the panel's title or id");

    /** Every action, in the order the tools are listed. */
    public static final List<Action> ALL = List.of(
            // ---- sessions: the Sessions tab, its menus, the session bar and the session dialog
            new Action("session_new", Kind.ACT,
                    "Creates a session, as Sessions ▸ New session… does, opens it in a panel of its own and, with prompt, starts it with that prompt. "
                            + "The new session records this caller as its creator.",
                    List.of(Arg.required("name", Type.STRING, "unique; also the CLI's --name"),
                            Arg.required("work_dir", Type.STRING, "the folder the session runs in; ~/ expands"),
                            Arg.optional("model", Type.STRING, "empty: the CLI's default").among(withDefault(SessionSpec.MODELS)),
                            Arg.optional("effort", Type.STRING, "empty: the CLI's default").among(withDefault(SessionSpec.EFFORTS)),
                            Arg.optional("permission_mode", Type.STRING, "empty: the CLI's default")
                                    .among(List.of("", "default", "acceptEdits", "plan", "auto", "dontAsk")),
                            Arg.optional("add_dirs", Type.STRINGS, "extra directories, --add-dir"),
                            Arg.optional("checkpoint", Type.STRING, "the prompt the Checkpoint button sends; default /reflect"),
                            Arg.optional("prompt", Type.STRING, "a first prompt: the session starts and runs it")),
                    Set.of(Intent.SaveSession.class, Intent.PromptSession.class)),
            new Action("session_edit", Kind.ACT,
                    "Changes a session's settings, as its Edit… dialog does; new_name renames it and its panel. Takes effect at its next start.",
                    List.of(SESSION, Arg.optional("new_name", Type.STRING, ""), Arg.optional("work_dir", Type.STRING, ""),
                            Arg.optional("model", Type.STRING, ""), Arg.optional("effort", Type.STRING, ""),
                            Arg.optional("permission_mode", Type.STRING, ""), Arg.optional("add_dirs", Type.STRINGS, ""),
                            Arg.optional("checkpoint", Type.STRING, "")),
                    Set.of(Intent.SaveSession.class)),
            new Action("session_open", Kind.ACT, "Opens a session in a panel of its own, or focuses the panel it has, as a double click on its row does.",
                    List.of(SESSION), Set.of(Intent.OpenSession.class)),
            new Action("session_start", Kind.ACT,
                    "Starts a session's process, as Start does. A session the user stopped is started again only by the user or by the session's creator (record 44's circuit breaker).",
                    List.of(SESSION), Set.of(Intent.StartSession.class)),
            new Action("session_stop", Kind.ACT, "Stops a session's process, as Stop does; its conversation stays and its next start resumes it.",
                    List.of(SESSION), Set.of(Intent.StopSession.class)),
            new Action("session_interrupt", Kind.ACT, "Interrupts a session's running turn, as Esc in its panel does.",
                    List.of(SESSION), Set.of(Intent.InterruptSession.class)),
            new Action("session_fresh", Kind.ACT,
                    "Restarts a session on a new conversation, as Start fresh does; the old conversation stays on disk. Same rule as session_start.",
                    List.of(SESSION), Set.of(Intent.StartFresh.class)),
            new Action("session_checkpoint", Kind.ACT, "Sends a session its checkpoint command and carries its summary into a fresh conversation, as Checkpoint does.",
                    List.of(SESSION), Set.of(Intent.Checkpoint.class)),
            new Action("session_command", Kind.ACT, "Sends a session a slash command, as the palette and the compact button do (/compact, /clear, …).",
                    List.of(SESSION, Arg.required("command", Type.STRING, "the command, with its slash")), Set.of(Intent.SessionCommand.class)),
            new Action("session_model", Kind.ACT, "Sets a session's model, as its model chip does; a running session switches at its next turn.",
                    List.of(SESSION, Arg.required("model", Type.STRING, "").among(withDefault(SessionSpec.MODELS))), Set.of(Intent.SetSessionModel.class)),
            new Action("session_effort", Kind.ACT, "Sets a session's effort, as its effort chip does.",
                    List.of(SESSION, Arg.required("effort", Type.STRING, "").among(withDefault(SessionSpec.EFFORTS))), Set.of(Intent.SetSessionEffort.class)),
            new Action("session_order", Kind.ACT, "Puts the Sessions tab in this order, as dragging the rows does; names left out keep their places after these.",
                    List.of(Arg.required("names", Type.STRINGS, "session names, first to last")), Set.of(Intent.OrderSessions.class)),
            new Action("session_import", Kind.ACT, "Adds a stored Claude Code conversation as a session, as Sessions ▸ Import… does; its first start forks it.",
                    List.of(Arg.required("name", Type.STRING, ""), Arg.required("work_dir", Type.STRING, ""),
                            Arg.required("conversation", Type.STRING, "the stored conversation's session id")),
                    Set.of(Intent.ImportSessions.class)),
            new Action("session_clear", Kind.DESTRUCTIVE, "Clears a session's transcript in the window, as Clear does.",
                    List.of(SESSION), Set.of(Intent.ClearTranscript.class)),
            new Action("session_remove", Kind.DESTRUCTIVE, "Removes a session from comando, as Remove does; its stored conversation stays on disk.",
                    List.of(SESSION), Set.of(Intent.RemoveSession.class)),
            new Action("session_send", Kind.ACT, "Prompts another running session through its panel (record 44).",
                    List.of(SESSION, Arg.required("text", Type.STRING, "")), Set.of(Intent.EditPanel.class, Intent.RunText.class)),
            new Action("session_read", Kind.READ, "Reads another session's transcript as numbered blocks.",
                    List.of(SESSION), Set.of()),
            new Action("session_subagent", Kind.READ, "Follows a sub-agent's stored conversation in a view, as the transcript link on its Agent card does.",
                    List.of(SESSION, Arg.required("tool_use_id", Type.STRING, "the Agent call's id")), Set.of(Intent.OpenSubagent.class)),

            // ---- panels: the workspace's tabs and their menus
            new Action("panel_add", Kind.ACT, "Adds a panel, as + does: an editor, or a document panel with document.",
                    List.of(Arg.optional("title", Type.STRING, "default: the next free name"), Arg.optional("document", Type.BOOLEAN, "")),
                    Set.of(Intent.AddPanel.class)),
            new Action("panel_close", Kind.ACT, "Closes a panel; it can be reopened, as its tab's ✕ does.", List.of(PANEL), Set.of(Intent.ClosePanel.class)),
            new Action("panel_reopen", Kind.ACT, "Reopens a closed panel, as the closed panels' menu does.", List.of(PANEL), Set.of(Intent.ReopenPanel.class)),
            new Action("panel_remove", Kind.DESTRUCTIVE, "Removes a panel and its text, as Remove on its tab does.", List.of(PANEL), Set.of(Intent.RemovePanel.class)),
            new Action("panel_rename", Kind.ACT, "Renames a panel, as Rename on its tab does.",
                    List.of(PANEL, Arg.required("title", Type.STRING, "")), Set.of(Intent.RenamePanel.class)),
            new Action("panel_move", Kind.ACT, "Moves a panel's tab to a position, as dragging it does.",
                    List.of(PANEL, Arg.required("index", Type.INTEGER, "0 is the first tab")), Set.of(Intent.MovePanel.class)),
            new Action("panel_focus", Kind.ACT, "Focuses a panel, as clicking its tab does.", List.of(PANEL), Set.of(Intent.FocusPanel.class)),
            new Action("panel_bind_session", Kind.ACT, "Binds a panel to a session, as choosing one in its session chooser does; empty unbinds.",
                    List.of(PANEL, Arg.required("session", Type.STRING, "")), Set.of(Intent.BindSession.class)),
            new Action("panel_bind_connection", Kind.ACT, "Binds a panel to a database connection, as its connection toggle does; empty unbinds.",
                    List.of(PANEL, Arg.required("connection", Type.STRING, "")), Set.of(Intent.BindConnection.class)),
            new Action("panel_run", Kind.ACT, "Runs text in a panel as ⌘⏎ does: SQL on its connection, or a prompt to its session.",
                    List.of(PANEL, Arg.required("text", Type.STRING, ""), Arg.optional("target", Type.STRING, "default: the panel's binding")
                            .among(List.of("", "sql", "claude"))),
                    Set.of(Intent.RunText.class)),
            new Action("panel_cancel", Kind.ACT, "Cancels the SQL running in a panel, as Cancel does.", List.of(PANEL), Set.of(Intent.CancelRun.class)),
            new Action("panel_refresh", Kind.ACT, "Runs a panel's last SQL again, as Refresh on its result does.",
                    List.of(PANEL, Arg.required("sql", Type.STRING, "")), Set.of(Intent.RefreshSql.class)),
            new Action("ui_set_editor", Kind.ACT, "Replaces a panel's text.", List.of(), Set.of(Intent.EditPanel.class)),
            new Action("ui_insert", Kind.ACT, "Adds text to a panel as its own block.", List.of(), Set.of(Intent.EditPanel.class)),

            // ---- approvals: the Approvals tab's cards
            new Action("approval_answer", Kind.ACT,
                    "Answers an approval card, as its buttons do. Refused for a card raised by this caller or by a session this caller created "
                            + "(record 121, rule 1). decision: allow (scope once, session or always), deny with a message, or answer with the chosen options.",
                    List.of(Arg.required("id", Type.INTEGER, "the card's id, from ui_get_state"),
                            Arg.required("decision", Type.STRING, "").among(List.of("allow", "deny", "answer")),
                            Arg.optional("scope", Type.STRING, "for allow; default once").among(List.of("once", "session", "always")),
                            Arg.optional("message", Type.STRING, "for deny"),
                            Arg.optional("answers", Type.OBJECT, "for answer: question text to the chosen option label")),
                    Set.of(Intent.ApprovalAnswered.class)),

            // ---- connections: the Connections tab and its dialog
            new Action("connection_save", Kind.ACT,
                    "Adds or changes a database connection, as its dialog does; previous_name renames one.",
                    List.of(Arg.required("name", Type.STRING, ""), Arg.optional("previous_name", Type.STRING, ""),
                            Arg.required("host", Type.STRING, ""), Arg.optional("port", Type.INTEGER, "default 8812"),
                            Arg.required("database", Type.STRING, ""), Arg.optional("user", Type.STRING, ""),
                            Arg.optional("password", Type.STRING, "never shown"),
                            Arg.optional("production", Type.BOOLEAN, "a production connection refuses writes"),
                            Arg.optional("ssl_mode", Type.STRING, "default prefer").among(List.of("", "prefer", "disable", "allow", "require", "verify-ca", "verify-full"))),
                    Set.of(Intent.SaveConnection.class)),
            new Action("connection_test", Kind.READ, "Tests a connection's settings without saving them, as Test in its dialog does.",
                    List.of(Arg.required("name", Type.STRING, "a saved connection")), Set.of(Intent.TestConnection.class)),
            new Action("connection_open", Kind.ACT, "Connects, as the connection's toggle does.",
                    List.of(Arg.required("name", Type.STRING, "")), Set.of(Intent.OpenConnection.class)),
            new Action("connection_close", Kind.ACT, "Disconnects, as the connection's toggle does.",
                    List.of(Arg.required("name", Type.STRING, "")), Set.of(Intent.CloseConnection.class)),
            new Action("connection_schema", Kind.READ, "Opens a connection's schema explorer, as Explore does.",
                    List.of(Arg.required("name", Type.STRING, "")), Set.of(Intent.ExploreSchema.class)),
            new Action("connection_remove", Kind.DESTRUCTIVE, "Removes a connection, as Remove does.",
                    List.of(Arg.required("name", Type.STRING, "")), Set.of(Intent.RemoveConnection.class)),

            // ---- views and the window
            new Action("ui_show_table", Kind.READ, "Shows rows as a table view.", List.of(), Set.of(Intent.ShowView.class)),
            new Action("ui_plot", Kind.READ, "Plots series in a view.", List.of(), Set.of(Intent.ShowView.class)),
            new Action("ui_show_view", Kind.READ, "Shows a markdown, diff, text, tree, table or plot view.", List.of(), Set.of(Intent.ShowView.class)),
            new Action("ui_open_file", Kind.READ, "Shows a file in the side pane.", List.of(), Set.of(Intent.OpenFile.class)),
            new Action("ui_close_view", Kind.ACT, "Closes a view this session placed.", List.of(), Set.of(Intent.CloseView.class)),
            new Action("view_pin", Kind.ACT, "Pins or unpins a view, as its pin does; a pinned view is never replaced or closed by a session.",
                    List.of(Arg.required("id", Type.INTEGER, "the view's id, from ui_get_state"), Arg.optional("pinned", Type.BOOLEAN, "default true")),
                    Set.of(Intent.PinView.class)),
            new Action("view_close", Kind.ACT, "Closes any view the user could close, as its ✕ does; a pinned view is unpinned first by view_pin.",
                    List.of(Arg.required("id", Type.INTEGER, "")), Set.of(Intent.CloseView.class)),
            new Action("ui_set_layout", Kind.ACT, "Arranges the window: focus, lower tab, sidebar, side pane, lower share.", List.of(),
                    Set.of(Intent.SessionLayout.class, Intent.Arrange.class, Intent.SelectLowerTab.class, Intent.SessionFocus.class)),
            new Action("ui_get_state", Kind.READ, "What the window shows: panels, sessions, approvals, connections, folders, views.", List.of(), Set.of()),
            new Action("ui_notify", Kind.READ, "A status-bar message and a desktop notification.", List.of(), Set.of(Intent.Notify.class)),
            new Action("notice_dismiss", Kind.ACT, "Dismisses the status bar's notice, as its ✕ does.", List.of(), Set.of(Intent.DismissNotice.class)),

            // ---- databases
            new Action("db_run_sql", Kind.ACT, "Runs SQL on a connection under the SQL policy.", List.of(), Set.of(Intent.RunText.class)),
            new Action("db_describe", Kind.READ, "A table's columns and types.", List.of(), Set.of()),

            // ---- commands and folders
            new Action("gradle_run", Kind.ACT, "Runs Gradle tasks for a panel's folder, as Run Gradle does; the run shows in its own log panel.",
                    List.of(PANEL, Arg.required("tasks", Type.STRING, "e.g. :app:test")), Set.of(Intent.RunGradle.class)),
            new Action("command_cancel", Kind.ACT, "Cancels a running command, as its Cancel does.",
                    List.of(Arg.required("run_id", Type.INTEGER, "")), Set.of(Intent.CancelCommand.class)),
            new Action("folders_browse", Kind.READ, "Reads the Files tab's folders again, as Refresh does.",
                    List.of(Arg.optional("names", Type.STRINGS, "default: every folder")), Set.of(Intent.BrowseFolders.class, Intent.WatchFolders.class)),

            // ---- the approval prompt itself, which Claude Code calls
            new Action("approve", Kind.READ, "Claude Code's permission prompt: a card, and a wait for the answer. Claude Code calls it; do not call it yourself.",
                    List.of(), Set.of(Intent.ApprovalRequested.class, Intent.ApprovalWithdrawn.class)));

    /** Intents sent by adapters, the store or the loop, never on a person's gesture: no action sends them. */
    public static final Set<Class<? extends Intent>> EVENTS = Set.of(
            Intent.SqlStarted.class, Intent.SqlRows.class, Intent.SqlCompleted.class, Intent.SqlFailed.class, Intent.SqlCancelled.class,
            Intent.SqlWarning.class, Intent.FoldersRead.class, Intent.ConnectionOpened.class, Intent.ConnectionClosed.class,
            Intent.ConnectionFailed.class, Intent.SchemaChanged.class, Intent.ConnectionLost.class, Intent.ConnectionTested.class,
            Intent.SessionStarted.class, Intent.SessionRanAt.class, Intent.SessionInit.class, Intent.SessionActivity.class,
            Intent.SessionTasksChanged.class, Intent.SessionTurnRunning.class, Intent.SessionTurnEnded.class, Intent.SessionExited.class,
            Intent.SessionBranch.class, Intent.SessionContext.class, Intent.CommandFinished.class, Intent.FolderLoaded.class,
            Intent.ReposLoaded.class, Intent.ChangeRead.class, Intent.PlanUsageRead.class);

    /** Intents the window sends by itself, not on a gesture, with the reason. */
    public static final Map<Class<? extends Intent>, String> WINDOW = Map.of(
            Intent.RefreshView.class, "a live plot redraws itself as its result grows; an agent shows a plot with ui_plot");

    /**
     * User intents not offered yet, each with the reason (record 121 §3): they take a git change or a folder entry the
     * window holds and comando's state does not, so the action needs the repository status read first.
     */
    public static final Map<Class<? extends Intent>, String> NOT_YET = notYet();

    private Actions() {
    }

    private static Map<Class<? extends Intent>, String> notYet() {
        Map<Class<? extends Intent>, String> m = new LinkedHashMap<>();
        String git = "a git change of the Files tab: needs the repository status, which the window reads and comando's state does not hold (record 121, increment 2)";
        m.put(Intent.OpenChange.class, git);
        m.put(Intent.RollBack.class, git);
        m.put(Intent.RemoveFiles.class, git);
        m.put(Intent.Unstage.class, git);
        m.put(Intent.DeleteFiles.class, "a Files-tab entry: needs the folder tree the window holds (record 121, increment 2)");
        m.put(Intent.AddFolder.class, "the folders window (record 121, increment 3, with the Setup actions)");
        m.put(Intent.SaveFolders.class, "the folders window (record 121, increment 3, with the Setup actions)");
        return Map.copyOf(m);
    }

    /** The action offered as {@code tool}. */
    public static Optional<Action> byTool(String tool) {
        return ALL.stream().filter(a -> a.tool().equals(tool)).findFirst();
    }

    /** Whether an action sends intents of this kind. */
    public static boolean offered(Class<? extends Intent> kind) {
        return ALL.stream().anyMatch(a -> a.intents().contains(kind));
    }

    private static List<String> withDefault(List<String> values) {
        return java.util.stream.Stream.concat(java.util.stream.Stream.of(""), values.stream()).toList();
    }
}
