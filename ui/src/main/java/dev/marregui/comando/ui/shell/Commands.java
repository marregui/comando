package dev.marregui.comando.ui.shell;

import java.awt.Component;
import java.awt.KeyboardFocusManager;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import javax.swing.JOptionPane;
import javax.swing.KeyStroke;
import javax.swing.text.BadLocationException;

import dev.marregui.comando.core.dispatch.Target;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.result.ResultView;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.LowerTab;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.core.state.RunStatus;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.core.state.SqlRun;
import dev.marregui.comando.core.state.ViewState;
import dev.marregui.comando.core.view.Clip;
import dev.marregui.comando.core.view.Placement;
import dev.marregui.comando.core.view.ViewSpec;
import dev.marregui.comando.ui.dialogs.folders.FoldersDialog;
import dev.marregui.comando.ui.kit.action.ActionRegistry;
import dev.marregui.comando.ui.kit.action.Command;
import dev.marregui.comando.ui.kit.action.CommandPalette;
import dev.marregui.comando.ui.kit.async.Edt;
import dev.marregui.comando.ui.kit.desktop.Platform;
import dev.marregui.comando.ui.kit.format.Labels;
import dev.marregui.comando.ui.kit.text.Plain;
import dev.marregui.comando.ui.kit.theme.NamedTheme;
import dev.marregui.comando.ui.panels.EditorPanel;
import dev.marregui.comando.ui.panels.LowerPanel;
import dev.marregui.comando.ui.panels.SessionsSidebar;
import dev.marregui.comando.ui.panels.TerminalTabs;
import dev.marregui.comando.ui.panels.Workspace;
import dev.marregui.comando.ui.widget.capability.Capabilities;
import dev.marregui.comando.ui.widget.capability.Copyable;
import dev.marregui.comando.ui.widget.capability.Exportable;
import dev.marregui.comando.ui.widget.capability.FindBar;
import dev.marregui.comando.ui.widget.capability.Searchable;
import dev.marregui.comando.ui.widget.capability.Zoomable;
import dev.marregui.comando.ui.widgets.terminal.TerminalWidget;

/**
 * The window's command table: every command with its label, key and when it can run, registered once; and what the
 * commands that act on the focused panel, its results and its shell do. EDT only.
 */
final class Commands {

    private static final int SHIFT = InputEvent.SHIFT_DOWN_MASK;
    private static final int CTRL = InputEvent.CTRL_DOWN_MASK;
    private static final int ALT = InputEvent.ALT_DOWN_MASK;

    private final ComandoUi ui;
    private final ComandoUi.Dependencies deps;
    private final ActionRegistry actions;
    private final ShellParts parts;
    private final FocusRouter focus;
    private final LayoutKeeper layout;
    private final PanelFiles files;
    private final QuitFlow quit;
    private final WindowLayout windowLayout;

    Commands(final ComandoUi ui, final ComandoUi.Dependencies deps, final ActionRegistry actions, final ShellParts parts, final FocusRouter focus,
            final LayoutKeeper layout, final PanelFiles files, final QuitFlow quit, final WindowLayout windowLayout) {
        this.ui = ui;
        this.deps = deps;
        this.actions = actions;
        this.parts = parts;
        this.focus = focus;
        this.layout = layout;
        this.files = files;
        this.quit = quit;
        this.windowLayout = windowLayout;
    }

    /**
     * Registers every command, {@code shortcut} the modifier of comando's commands and {@code lowerTabKey} that of the
     * lower tabs' keys; where each sits, in a menu or an area, its icon and its tooltip come from {@link Menus}.
     */
    void register(final int shortcut, final int lowerTabKey, final HelpActions help, final KitActions kit, final JobActions jobs) {
        final Workspace workspace = parts.workspace();
        final LowerPanel lower = parts.lower();
        final FindBar findBar = parts.findBar();
        final LivePlots livePlots = parts.livePlots();
        // file
        cmd("panel.new", "New panel", key(KeyEvent.VK_T, shortcut), () -> workspace.newPanel());
        cmd("panel.openSpec", "Open file…", key(KeyEvent.VK_O, shortcut), files::openSpec);
        cmd("panel.reopen", "Reopen closed panel", key(KeyEvent.VK_T, shortcut | SHIFT), files::reopenPanel, () -> workspace.hasClosed());
        // ⌘S saves the focused panel wherever the keyboard is; exporting a view is ⌘⇧S
        cmd("panel.save", "Save", key(KeyEvent.VK_S, shortcut), files::savePanel);
        cmd("panel.rename", "Rename panel…", null, () -> {
            final EditorPanel p = workspace.focused();
            if (p != null) {
                workspace.rename(p);
            }
        });
        // ⌘W closes the view that has the keyboard, else the panel
        cmd("file.close", "Close", key(KeyEvent.VK_W, shortcut), () -> {
            final long view = focus.focusedView();
            if (view >= 0) {
                deps.intents().dispatch(new Intent.CloseView(view));
            } else {
                files.closePanel(focusedId());
            }
        });
        cmd("view.export", "Export view…", key(KeyEvent.VK_S, shortcut | SHIFT), files::export, () -> focus.capability(Exportable.class) != null);
        // edit: each forwards its key to the focused component's own binding, so the menu shows the keys the
        // components already have; a component without the binding (the terminal) gets the key itself
        edit("edit.undo", "Undo", key(KeyEvent.VK_Z, shortcut));
        edit("edit.redo", "Redo", key(KeyEvent.VK_Z, shortcut | SHIFT));
        edit("edit.cut", "Cut", key(KeyEvent.VK_X, shortcut));
        edit("edit.copy", "Copy", key(KeyEvent.VK_C, shortcut));
        edit("edit.paste", "Paste", key(KeyEvent.VK_V, shortcut));
        edit("edit.selectAll", "Select all", key(KeyEvent.VK_A, shortcut));
        cmd("view.copy", "Copy view content", key(KeyEvent.VK_C, shortcut | SHIFT), () -> {
            final Copyable c = focus.capability(Copyable.class);
            if (c != null && Capabilities.copy(c)) {
                deps.intents().dispatch(new Intent.Notify(Notice.Level.INFO, "Copied the view's content", false));
            }
        }, () -> {
            final Copyable c = focus.capability(Copyable.class);
            return c != null && c.canCopy();
        });
        cmd("view.find", "Find…", key(KeyEvent.VK_F, shortcut), () -> findBar.open(focus.capability(Searchable.class)),
                () -> focus.capability(Searchable.class) != null);
        cmd("view.findNext", "Find next", key(KeyEvent.VK_G, shortcut), () -> findBar.next(true), () -> findBar.isOpen());
        cmd("view.findPrevious", "Find previous", key(KeyEvent.VK_G, shortcut | SHIFT), () -> findBar.next(false), () -> findBar.isOpen());
        final KeyStroke complete = key(KeyEvent.VK_SPACE, CTRL);
        cmd("edit.complete", "Complete SQL", complete, () -> Keys.forward(focus.editorArea(), complete, true),
                () -> focus.editorFocused() && Keys.forward(focus.editorArea(), complete, false));
        // ⌥⎋ as well, as in macOS text views: ⌃Space is the system's input-source switch on many Macs
        cmd("edit.completeAlt", "Complete SQL", key(KeyEvent.VK_ESCAPE, ALT), () -> Keys.forward(focus.editorArea(), complete, true),
                () -> focus.editorFocused() && Keys.forward(focus.editorArea(), complete, false));
        cmd("panel.preview", "Edit / Preview Markdown", key(KeyEvent.VK_M, shortcut | SHIFT), () -> {
            final EditorPanel p = workspace.focused();
            if (p != null) {
                p.togglePreview();
            }
        });
        // panel: running; run keys act while the keyboard is in the focused panel's editor, else the component has them
        cmd("run", "Run", key(KeyEvent.VK_ENTER, shortcut), () -> run(Target.AUTO), focus::editorFocused);
        cmd("run.claude", "Send to Claude", key(KeyEvent.VK_ENTER, shortcut | SHIFT), () -> run(Target.CLAUDE), focus::editorFocused);
        cmd("run.sql", "Run as SQL", key(KeyEvent.VK_ENTER, ALT), () -> run(Target.SQL), focus::editorFocused);
        // ⌃⏎ on macOS; elsewhere Ctrl+⏎ is Run and Alt+⏎ Run as SQL, so Ctrl+Alt+⏎
        cmd("terminal.run", "Run in the terminal", key(KeyEvent.VK_ENTER, Platform.macKeys() ? CTRL : shortcut | ALT), this::runInTerminal,
                focus::editorFocused);
        cmd("run.stop", "Stop", key(KeyEvent.VK_PERIOD, shortcut), () -> deps.intents().dispatch(new Intent.CancelRun(focusedId())),
                this::canStop);
        // panel: its session
        cmd("session.toggle", "Connect or disconnect", null, () -> focusedSession().ifPresent(x ->
                deps.intents().dispatch(x.status().isUsable() ? new Intent.StopSession(x.name()) : new Intent.StartSession(x.name()))),
                () -> focusedSession().isPresent());
        cmd("session.interrupt", "Interrupt the turn", key(KeyEvent.VK_PERIOD, shortcut | SHIFT),
                () -> focusedSession().ifPresent(x -> deps.intents().dispatch(new Intent.InterruptSession(x.name()))),
                () -> focusedSession().map(SessionState::isBusy).orElse(false));
        // the Session tab emptied, the conversation going on: from the palette only, once a turn has ended (record 104)
        cmd("transcript.clear", "Clear the Session tab", null, lower::clearTranscript, lower::canClearTranscript);
        // as the session's menu in the sidebar: only a running session has a conversation to compact, and not while its
        // checkpoint runs (record 130)
        cmd("session.compact", "Compact the conversation", null, () -> focusedSession().ifPresent(x ->
                        deps.intents().dispatch(new Intent.SessionCommand(x.name(), SessionSpec.COMPACT_COMMAND))),
                () -> focusedSession().filter(x -> x.status().isUsable() && !x.isCheckpointing()).isPresent());
        // every session's: the context at which a turn's end runs the checkpoint, then /compact (record 129)
        cmd("session.checkpointThreshold", "Checkpoint threshold…", null, this::checkpointThreshold);
        for (final String m : SessionSpec.MODELS) {
            cmd("session.model." + m, m, null, () -> focusedSession().ifPresent(x ->
                    deps.intents().dispatch(new Intent.SetSessionModel(x.name(), m))),
                    () -> focusedSession().filter(x -> !x.isCheckpointing()).isPresent());
        }
        for (final String e : SessionSpec.EFFORTS) {
            cmd("session.effort." + e, e, null, () -> focusedSession().ifPresent(x ->
                    deps.intents().dispatch(new Intent.SetSessionEffort(x.name(), e))),
                    () -> focusedSession().filter(x -> !x.isCheckpointing()).isPresent());
        }
        cmd("panel.bindSession", "Open a session…", null, () -> choose("Session to open",
                deps.state().get().sessions().stream().map(x -> x.name()).toList(),
                name -> deps.intents().dispatch(new Intent.OpenSession(name, deps.state().get().focused(), PanelId.random()))));
        // the focused panel's session in the dialog, or a new one when the panel has none
        cmd("session.dialog", "Session settings…", key(KeyEvent.VK_K, shortcut | SHIFT), () -> ui.openSessions(deps.state().get().focusedPanel().session()));
        cmd("session.import", "Import conversations…", null, ui::openImport);
        // panel: its connection
        cmd("connection.toggle", "Connect or disconnect", null, () -> {
            final AppState s = deps.state().get();
            s.connection(s.focusedPanel().connection()).ifPresent(c -> deps.intents().dispatch(c.status().isUsable()
                    ? new Intent.CloseConnection(c.name()) : new Intent.OpenConnection(c.name())));
        }, () -> !deps.state().get().focusedPanel().connection().isEmpty());
        cmd("connection.schema", "Explore the schema", key(KeyEvent.VK_E, shortcut | SHIFT),
                () -> ui.exploreSchema(deps.state().get().focusedPanel().connection()),
                () -> !deps.state().get().focusedPanel().connection().isEmpty());
        cmd("panel.bindConnection", "Bind a connection…", null, () -> choose("Connection for this panel",
                deps.state().get().connections().stream().map(x -> x.name()).toList(),
                name -> deps.intents().dispatch(new Intent.BindConnection(deps.state().get().focused(), name))));
        cmd("connection.dialog", "Connection settings…", key(KeyEvent.VK_O, shortcut | SHIFT),
                () -> ui.openConnections(deps.state().get().focusedPanel().connection()));
        if (deps.server() != null) {
            cmd("server.start", "Start embedded QuestDB", null, () -> deps.server().toggle(), () -> !deps.server().isRunning() && !deps.server().busy());
            cmd("server.stop", "Stop embedded QuestDB", null, () -> deps.server().toggle(), () -> deps.server().isRunning() && !deps.server().busy());
        }
        if (deps.edge() != null) {
            // disabled while a start or stop is under way: the button greys out and its tooltip says which
            cmd("edge.start", "Start the comando Edge", null, () -> deps.edge().toggle(), () -> !deps.edge().isRunning() && !deps.edge().busy());
            cmd("edge.stop", "Stop the comando Edge", null, () -> deps.edge().toggle(), () -> deps.edge().isRunning() && !deps.edge().busy());
        }
        // panel: its results
        cmd("run.insertResult", "Attach to the editor", key(KeyEvent.VK_I, shortcut | SHIFT), this::insertLastResult, this::hasRun);
        cmd("run.keep", "Keep in a tab", key(KeyEvent.VK_R, shortcut | SHIFT), this::keepResult, this::hasRun);
        // always claimed: the editor's own ⌘J joins lines, which the user never means here; without a run it says so
        cmd("run.plot", "Plot", key(KeyEvent.VK_J, shortcut), this::plotLastResult);
        cmd("run.plotLive", "Plot, refreshing…", key(KeyEvent.VK_J, shortcut | SHIFT), this::plotLive, this::hasRun);
        cmd("run.plotStop", "Stop refreshing plots", null, livePlots::stopAll, () -> livePlots.size() > 0);
        // panel: tools that run in its project
        cmd("tools.gradle", "Run Gradle tasks…", key(KeyEvent.VK_G, shortcut | ALT), this::runGradle, () -> focusedSession().isPresent());
        cmd("files.settings", "Configure " + FoldersDialog.title(), null, ui::editFolders);
        cmd("files.jobs", "Configure kit jobs", null, jobs::configure, jobs::available);
        // the kit: its upgrade, by a session in its root, and the backups of the brain and the kit; never on their own
        cmd("kit.upgrade", KitActions.UPGRADE, null, kit::upgrade, kit::canUpgrade);
        cmd("kit.backup", KitActions.BACK_UP, null, kit::backUp, kit::canBackUp);
        cmd("kit.backupKit", KitActions.BACK_UP_KIT, null, kit::backUpKit, kit::canBackUpKit);
        cmd("terminal.restart", "Restart the shell", null, () -> lower.terminals().restartFocused(deps.state().get()));
        cmd("terminal.start", "Start the shell", null, () -> lower.terminals().startFocused(deps.state().get()),
                () -> !lower.terminals().focusedRunning());
        cmd("terminal.stop", "Stop the shell", null, () -> lower.terminals().stopFocused(), () -> lower.terminals().focusedRunning());
        // view
        cmd("view.palette", "Command palette", key(KeyEvent.VK_P, shortcut | SHIFT), () -> new CommandPalette(actions).show(parts.frame()));
        cmd("focus.editor", "Focus the editor", key(KeyEvent.VK_E, shortcut), () -> {
            final EditorPanel p = workspace.focused();
            if (p != null) {
                p.editor().requestFocus();
            }
        });
        cmd("view.sidebar", "Sidebar", key(KeyEvent.VK_B, shortcut), layout::toggleSidebar);
        cmd("view.sidePane", "Side pane", key(KeyEvent.VK_BACK_SLASH, shortcut), layout::toggleSidePane,
                () -> layout.sidePaneShown() || parts.side().hasViews());
        // ⌘K, IntelliJ's key for its commit view; a focused terminal keeps it as its Clear Buffer
        cmd("view.changes", "Uncommitted changes", key(KeyEvent.VK_K, shortcut), this::toggleChanges);
        cmd("view.files", "Files", null, () -> {
            final SessionsSidebar sidebar = parts.sidebar();
            if (!sidebar.isShown()) {
                layout.toggleSidebar();
            }
            sidebar.selectTab(SessionsSidebar.FILES);
            sidebar.focusFilesFilter();
        });
        cmd("view.restoreLayout", "Restore layout", null, layout::restoreArranged, layout::arranged);
        // ⌃1–⌃4 follow the lower tabs' order: Session, Approvals, Terminal, Results
        cmd("view.session", "Session", key(KeyEvent.VK_1, lowerTabKey), () -> focus.showLowerAndFocus(LowerTab.SESSION, lower.transcript().canvas()));
        cmd("view.approvals", "Approvals", key(KeyEvent.VK_2, lowerTabKey),
                () -> focus.showLowerAndFocus(LowerTab.APPROVALS, lower.inbox().component(), lower.inbox()::focusFirst));
        cmd("view.terminal", "Terminal", key(KeyEvent.VK_3, lowerTabKey), () -> {
            layout.lowerFor(deps.state().get(), true);
            deps.intents().dispatch(new Intent.SelectLowerTab(LowerTab.TERMINAL));
            Edt.later(() -> lower.terminals().focusShown(deps.state().get()));
        });
        cmd("view.results", "Results", key(KeyEvent.VK_4, lowerTabKey),
                () -> focus.showLowerAndFocus(LowerTab.RESULTS, lower.results().grid().table()));
        // ⌘⌥1–4 as well: macOS Mission Control takes ⌃1–⌃4 when its desktop shortcuts are on
        final String[] lowerTabs = {"view.session", "view.approvals", "view.terminal", "view.results"};
        for (int i = 0; i < lowerTabs.length; i++) {
            final String target = lowerTabs[i];
            cmd(target + ".alt", actions.get(target).label() + " tab", key(KeyEvent.VK_1 + i, shortcut | ALT), () -> actions.run(target));
        }
        cmd("view.nextTab", "Next tab", key(KeyEvent.VK_TAB, CTRL), () -> lower.selectRelative(1));
        cmd("view.previousTab", "Previous tab", key(KeyEvent.VK_TAB, CTRL | SHIFT), () -> lower.selectRelative(-1));
        cmd("view.closeLower", "Close the view in front", key(KeyEvent.VK_W, shortcut | SHIFT),
                () -> deps.intents().dispatch(new Intent.CloseView(lower.selectedView())), () -> lower.selectedView() >= 0);
        cmd("panel.next", "Next panel", key(KeyEvent.VK_CLOSE_BRACKET, shortcut | SHIFT), () -> workspace.selectRelative(1));
        cmd("panel.previous", "Previous panel", key(KeyEvent.VK_OPEN_BRACKET, shortcut | SHIFT), () -> workspace.selectRelative(-1));
        for (int i = 1; i <= 9; i++) {
            final int index = i - 1;
            cmd("panel." + i, "Go to panel " + i, key(KeyEvent.VK_0 + i, shortcut), () -> workspace.select(index));
        }
        cmd("view.zoomIn", "Zoom in", key(KeyEvent.VK_EQUALS, shortcut), () -> zoom(1), () -> focus.capability(Zoomable.class) != null);
        cmd("view.zoomOut", "Zoom out", key(KeyEvent.VK_MINUS, shortcut), () -> zoom(-1), () -> focus.capability(Zoomable.class) != null);
        cmd("view.zoomReset", "Reset zoom", key(KeyEvent.VK_0, shortcut), () -> {
            final Zoomable z = focus.capability(Zoomable.class);
            if (z != null) {
                z.resetZoom();
            }
        }, () -> focus.capability(Zoomable.class) != null);
        cmd("view.notices", "Recent notices", null, () -> parts.status().showHistory());
        // help
        cmd("help.tutorial", "Tutorial", null, help::showTutorial, () -> deps.home() != null);
        // ⌘/ stays the editor's comment toggle; ⌘⇧/ is the Mac's Help key
        cmd("help.keys", "Keyboard shortcuts", key(KeyEvent.VK_SLASH, shortcut | SHIFT), help::showKeys);
        cmd("help.markdown", "Markdown sheet", null, help::showMarkdownSheet);
        cmd("help.log", "Show the log", null, help::showLog, () -> deps.logDir() != null);
        cmd("help.edgeLog", "Show the Edge's log", null, () -> help.showLog(deps.edge()), () -> deps.edge() != null);
        cmd("help.questDbLog", "Show QuestDB's log", null, () -> help.showLog(deps.server()), () -> deps.server() != null);
        cmd("help.dataRoot", "Reveal comando folder", null, () -> help.reveal(deps.home()), () -> deps.home() != null);
        // the themes, from the swatch beside New panel: a ground and its tint chosen together, each with its swatch
        // (Menus), the one chosen ticked (record 103)
        for (final NamedTheme t : NamedTheme.values()) {
            cmd(t.commandId(), t.label, null, () -> ui.themeChosen(t));
            actions.choice(t.commandId(), () -> NamedTheme.chosen().orElse(null) == t);
        }
        // on macOS the application menu owns ⌘Q and calls the quit handler; a second binding would quit twice
        final boolean mac = Platform.isMac();
        cmd("app.about", "About comando", null, help::showAbout);
        cmd("app.quit", "Quit comando", mac ? null : key(KeyEvent.VK_Q, shortcut), quit::quit);
    }

    private void cmd(final String id, final String label, final KeyStroke key, final Runnable run) {
        actions.register(Command.of(id, label, Menus.icon(id), key, "", run));
    }

    private void cmd(final String id, final String label, final KeyStroke key, final Runnable run, final BooleanSupplier enabled) {
        actions.register(Command.of(id, label, Menus.icon(id), key, "", run, enabled));
    }

    /** An Edit command: runs the focused component's own action for {@code key}; enabled when it has one. */
    private void edit(final String id, final String label, final KeyStroke key) {
        cmd(id, label, key, () -> Keys.forward(editTarget(), key, true), () -> Keys.forward(editTarget(), key, false));
    }

    /**
     * What an Edit command acts on: the comparison window's text while it has the keyboard, since macOS's menu bar
     * takes the command's key before that window sees it; else the main window's.
     */
    private Component editTarget() {
        final Component c = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        return c != null && parts.diffWindow().holds(c) ? c : focus.focusOwnerInFrame();
    }

    private static KeyStroke key(final int code, final int modifiers) {
        return KeyStroke.getKeyStroke(code, modifiers);
    }

    // ---- what the commands do

    private PanelId focusedId() {
        return deps.state().get().focused();
    }

    private Optional<SessionState> focusedSession() {
        final AppState s = deps.state().get();
        return s.session(s.focusedPanel().session());
    }

    private boolean canStop() {
        final AppState s = deps.state().get();
        return s.runOf(s.focused()).map(r -> r.status().isActive()).orElse(false)
                || focusedSession().map(SessionState::isBusy).orElse(false);
    }

    private boolean hasRun() {
        final AppState s = deps.state().get();
        return s.runOf(s.focused()).isPresent();
    }

    /** Runs the editor's selection or the block under the caret. */
    private void run(final Target target) {
        final EditorPanel p = parts.workspace().focused();
        if (p != null) {
            p.runSelection(target);
        }
    }

    /**
     * Opens the Changes view of every folder's repository in the side pane; closes it when it shows them all, widens
     * it when it shows one.
     */
    private void toggleChanges() {
        final Optional<ViewState> open = deps.state().get().views().stream().filter(v -> v.spec() instanceof ViewSpec.Changes).findFirst();
        if (open.isPresent() && ((ViewSpec.Changes) open.get().spec()).root().isEmpty()) {
            deps.intents().dispatch(new Intent.CloseView(open.get().id()));
        } else {
            layout.revealSidePane();
            deps.intents().dispatch(new Intent.ShowView("", ViewSpec.Changes.TITLE, new ViewSpec.Changes(), Placement.SIDE));
        }
    }

    /** Asks for Gradle tasks, naming the build they run in; the last ones are remembered across restarts. */
    private void runGradle() {
        final String dir = focusedSession().map(x -> Labels.shortPath(x.spec().workDir())).orElse("the session's directory");
        final String tasks = (String) JOptionPane.showInputDialog(parts.frame(), Plain.text("Tasks and options, run in " + dir + ":"),
                "Run Gradle", JOptionPane.PLAIN_MESSAGE, null, null, windowLayout.gradleTasks());
        if (tasks != null && !tasks.isBlank()) {
            windowLayout.gradleTasks(tasks.strip());
            deps.intents().dispatch(new Intent.RunGradle(focusedId(), tasks.strip()));
        }
    }

    /**
     * Asks for the context, in tokens, at which a session's turn end runs its checkpoint, then {@code /compact}; 0 runs
     * none (record 129). Kept across restarts; a session started from then on is told who checkpoints it.
     */
    private void checkpointThreshold() {
        final String answer = (String) JOptionPane.showInputDialog(parts.frame(),
                Plain.text("Context, in tokens, at which a turn's end runs the session's checkpoint, then /compact; 0 for never:"),
                "Checkpoint threshold", JOptionPane.PLAIN_MESSAGE, null, null, Long.toString(windowLayout.checkpointThreshold()));
        if (answer == null || answer.isBlank()) {
            return;
        }
        long tokens;
        try {
            tokens = Long.parseLong(answer.strip().replace("_", "").replace(",", ""));
        } catch (final NumberFormatException e) {
            tokens = -1L;
        }
        if (tokens < 0 || tokens > Integer.MAX_VALUE) {
            deps.intents().dispatch(new Intent.Notify(Notice.Level.WARN, "Checkpoint threshold kept: " + answer.strip() + " is not a number of tokens", false));
            return;
        }
        ui.checkpointThresholdChosen(tokens);
        deps.intents().dispatch(new Intent.Notify(Notice.Level.INFO, tokens == 0 ? "No automatic checkpoint"
                : "Checkpoint, then /compact, at " + String.format("%,d", tokens) + " tokens of context", false));
    }

    /** Types the editor's selection, or the line under the caret, into the panel's shell and runs it. */
    private void runInTerminal() {
        final EditorPanel p = parts.workspace().focused();
        if (p == null) {
            return;
        }
        String text = p.editor().area().getSelectedText();
        if (text == null || text.isBlank()) {
            final var area = p.editor().area();
            try {
                final int line = area.getLineOfOffset(area.getCaretPosition());
                text = area.getText(area.getLineStartOffset(line), area.getLineEndOffset(line) - area.getLineStartOffset(line));
            } catch (final BadLocationException e) {
                return;
            }
        }
        final String command = text.strip();
        if (command.isEmpty()) {
            return;
        }
        deps.intents().dispatch(new Intent.SelectLowerTab(LowerTab.TERMINAL));
        Edt.later(() -> {
            // the posted send may run before the render that creates the panel's terminal: render it here first
            final TerminalTabs tabs = parts.lower().terminals();
            tabs.render(deps.state().get(), true);
            final TerminalWidget t = tabs.focused();
            if (t != null) {
                t.send(command + "\n");
            }
        });
    }

    private void zoom(final int steps) {
        final Zoomable z = focus.capability(Zoomable.class);
        if (z != null) {
            z.zoom(steps);
        }
    }

    /** Asks for one of {@code names}; nothing happens when there are none or the user cancels. */
    private void choose(final String title, final List<String> names, final Consumer<String> chosen) {
        if (names.isEmpty()) {
            deps.intents().dispatch(new Intent.Notify(Notice.Level.WARN, "Nothing to choose: add one first", false));
            return;
        }
        // names can come from stored conversations: shown guarded, mapped back to the real name
        final List<String> shown = names.stream().map(Plain::text).toList();
        final Object pick = JOptionPane.showInputDialog(parts.frame(), title, title, JOptionPane.PLAIN_MESSAGE, null, shown.toArray(), shown.getFirst());
        if (pick != null) {
            chosen.accept(names.get(shown.indexOf(pick.toString())));
        }
    }

    // ---- the focused panel's results

    /**
     * Puts the focused panel's last result in the editor as a fenced block, to send with a prompt: the selected
     * rows when the grid has a selection, else the first 50 rows; the error when the run failed.
     */
    private void insertLastResult() {
        final AppState s = deps.state().get();
        final SqlRun run = s.runOf(s.focused()).orElse(null);
        final EditorPanel p = parts.workspace().focused();
        // its session runs its checkpoint: the panel takes no text from the user meanwhile (record 130)
        if (p == null || p.locked()) {
            return;
        }
        if (!p.editable()) {
            files.refuseReadOnly();
            return;
        }
        if (run == null) {
            deps.intents().dispatch(new Intent.Notify(Notice.Level.WARN, "The panel has no result to attach", false));
            return;
        }
        final ResultView r = parts.lower().results().shown();
        String block;
        if (run.status() == RunStatus.FAILED) {
            block = Attachments.error(run.sql(), run.error());
        } else if (r == null) {
            deps.intents().dispatch(new Intent.Notify(Notice.Level.WARN, "The panel's run returned no rows", false));
            return;
        } else {
            block = Attachments.rows(run.sql(), r, selectedResultRows(r));
        }
        p.editor().append(block);
        p.editor().requestFocus();
    }

    /** The grid's selected rows as result rows, in the order shown, at most 500; empty when nothing is selected. */
    private int[] selectedResultRows(final ResultView r) {
        return Arrays.stream(parts.lower().results().grid().selectedSourceRows())
                .filter(i -> i >= 0 && i < r.rowCount()).limit(500).toArray();
    }

    /**
     * Plots the focused panel's last result: the first time or numeric column as x, the other numeric ones as series.
     */
    private void plotLastResult() {
        try {
            final ViewSpec.Plot plot = ResultViews.plot(parts.lower().results().shown());
            deps.intents().dispatch(new Intent.ShowView("", "plot · " + deps.state().get().focusedPanel().title(), plot, Placement.LOWER));
        } catch (final IllegalArgumentException e) {
            deps.intents().dispatch(new Intent.Notify(Notice.Level.WARN, e.getMessage(), false));
        }
    }

    /** Re-runs the focused panel's last query every N seconds, N asked for, and plots each result again. */
    private void plotLive() {
        final AppState s = deps.state().get();
        final SqlRun run = s.runOf(s.focused()).orElse(null);
        if (run == null) {
            deps.intents().dispatch(new Intent.Notify(Notice.Level.WARN, "Run the query to plot first", false));
            return;
        }
        ViewSpec.Plot plot;
        try {
            plot = ResultViews.plot(parts.lower().results().shown());
        } catch (final IllegalArgumentException e) {
            deps.intents().dispatch(new Intent.Notify(Notice.Level.WARN, e.getMessage(), false));
            return;
        }
        final String every = (String) JOptionPane.showInputDialog(parts.frame(), "Re-run the query and refresh the plot every N seconds:",
                "Live plot", JOptionPane.PLAIN_MESSAGE, null, null, "5");
        if (every == null) {
            return;
        }
        int seconds;
        try {
            seconds = Integer.parseInt(every.strip());
        } catch (final NumberFormatException e) {
            deps.intents().dispatch(new Intent.Notify(Notice.Level.WARN, "Not a number of seconds: " + every, false));
            return;
        }
        if (seconds < 1 || seconds > LivePlots.MAX_SECONDS) {
            deps.intents().dispatch(new Intent.Notify(Notice.Level.WARN, "Refresh every 1 to " + LivePlots.MAX_SECONDS + " seconds", false));
            return;
        }
        final String title = "live · " + s.focusedPanel().title();
        parts.livePlots().start(s.focused(), run.sql(), title, seconds);
        // the plot checked before the dialog: a run that ended while it was open is plotted by the first refresh
        deps.intents().dispatch(new Intent.ShowView("", title, plot, Placement.LOWER));
    }

    /** Copies the focused panel's last result into its own tab, which later runs do not replace. */
    private void keepResult() {
        final ResultView r = parts.lower().results().shown();
        final AppState s = deps.state().get();
        final SqlRun run = s.runOf(s.focused()).orElse(null);
        if (r == null || run == null) {
            deps.intents().dispatch(new Intent.Notify(Notice.Level.WARN, "The panel has no result to keep", false));
            return;
        }
        final String sql = run.sql().replace('\n', ' ').strip();
        final String title = "result · " + Clip.keepStart(sql, 41) + " #" + run.requestId();
        deps.intents().dispatch(new Intent.ShowView("", title, ResultViews.table(r), Placement.LOWER));
    }
}
