package dev.marregui.comando.ui.shell;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.swing.JFrame;
import javax.swing.JOptionPane;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.FolderRoot;
import dev.marregui.comando.core.model.HomeDir;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.core.view.FileView;
import dev.marregui.comando.core.view.Placement;
import dev.marregui.comando.ui.PanelTexts;
import dev.marregui.comando.ui.kit.async.Edt;
import dev.marregui.comando.ui.kit.async.UiErrands;
import dev.marregui.comando.ui.kit.desktop.FileChoosers;
import dev.marregui.comando.ui.kit.desktop.FileSearch;
import dev.marregui.comando.ui.kit.desktop.Links;
import dev.marregui.comando.ui.kit.text.HitMarks;
import dev.marregui.comando.ui.kit.text.Plain;
import dev.marregui.comando.ui.kit.vcs.OpenedFiles;
import dev.marregui.comando.ui.panels.EditorPanel;
import dev.marregui.comando.ui.panels.Workspace;
import dev.marregui.comando.ui.widget.capability.Capabilities;
import dev.marregui.comando.ui.widget.capability.Exportable;
import dev.marregui.comando.ui.widgets.terminal.TerminalWidget;

/**
 * A panel's file and the panel itself: files opened in a panel or the side pane, a panel saved, closed, reopened, a
 * view exported. EDT only, unless a method says otherwise.
 */
final class PanelFiles {

    private static final System.Logger LOG = System.getLogger(PanelFiles.class.getName());

    private final ComandoUi.Dependencies deps;
    private final ShellParts parts;
    private final FocusRouter focus;
    private final LayoutKeeper layout;
    private final WindowLayout windowLayout;
    private final OpenedFiles openedFiles;
    /** Asks for a render of the state, coalesced. */
    private final Runnable render;
    /** Files whose opening is under way, so a second click does not open them twice. */
    private final Set<Path> openingFiles = new HashSet<>();
    /** The panels whose close is being checked or asked about. */
    private final Set<PanelId> closingPanels = new HashSet<>();
    /** The file asked for the preview tab last, until its read answers; null when none is being read. */
    private Path previewing;
    /** What {@link #previewing} marks as it arrives: the latest hit asked for it, with the keyboard when asked. */
    private HitMarks previewMarks = HitMarks.NONE;
    private boolean previewKeyboard;
    /** Previews asked to stay while their file is read: each arrives as a tab that stays, previewed since or not. */
    private final Set<Path> pinOnArrival = new HashSet<>();
    /** Files reopened with ⌘⇧T from the right-hand partition, which they open in again once read (record 141). */
    private final Set<Path> sideOnArrival = new HashSet<>();

    PanelFiles(final ComandoUi.Dependencies deps, final ShellParts parts, final FocusRouter focus, final LayoutKeeper layout, final WindowLayout windowLayout,
            final OpenedFiles openedFiles, final Runnable render) {
        this.deps = deps;
        this.parts = parts;
        this.focus = focus;
        this.layout = layout;
        this.windowLayout = windowLayout;
        this.openedFiles = openedFiles;
        this.render = render;
    }

    /**
     * Opens a file in a new panel bound to it, or brings forward the panel that has it. The check and the read run
     * off the EDT; a file that is binary or over {@link OpenFiles#MAX_PANEL_BYTES} goes to the side pane, read as
     * {@link FileView} reads it (a binary file said to be one, a file over {@link FileView#MAX_BYTES} refused).
     */
    void openFile(final Path chosen) {
        openFile(chosen, false);
    }

    /** {@link #openFile(Path)}, the new panel moved to the right-hand partition when {@code side}. */
    private void openFile(final Path chosen, final boolean side) {
        final Path file = chosen.toAbsolutePath().normalize();
        openedFiles.add(file);
        // what the file opens from, read now, at the request: closing it gives the keyboard back there (record 112)
        final FocusOrigins.Origin from = focus.originNow();
        final Optional<PanelState> open = panelOf(file);
        if (open.isPresent()) {
            focus.opened("panel:" + open.get().id().value(), from);
            // a file opened on purpose stays: its preview tab is pinned, as IntelliJ's is (record 141)
            if (open.get().preview()) {
                deps.intents().dispatch(new Intent.PinPanel(open.get().id()));
            }
            deps.intents().dispatch(new Intent.FocusPanel(open.get().id()));
            bringForward(open.get().id());
            return;
        }
        // the file of a closed panel: the panel comes back, with its bindings
        for (final PanelState p : deps.state().get().closed()) {
            if (deps.texts().file(p.id()).map(f -> PanelTexts.samePath(f, file)).orElse(false)) {
                focus.opened("panel:" + p.id().value(), from);
                deps.intents().dispatch(new Intent.ReopenPanel(p.id()));
                return;
            }
        }
        if (!openingFiles.add(file)) {
            return;
        }
        if (side) {
            sideOnArrival.add(file);
        }
        UiErrands.start("open-file", () -> {
            if (!OpenFiles.isText(file)) {
                deps.intents().dispatch(new Intent.OpenFile("", file.toString(), 0));
                Edt.later(() -> openingFiles.remove(file));
                return;
            }
            final PanelId id = PanelId.random();
            Edt.run(() -> focus.opened("panel:" + id.value(), from));
            deps.texts().open(id, file).thenAccept(opened -> {
                switch (opened.kind()) {
                    case BOUND -> {
                        // a file elsewhere keeps its extension, so it never takes a session's or a panel's name; a file
                        // of the panels' folder is named after its panel's title, so it takes the title without .md
                        final boolean document = deps.texts().document(id);
                        final String title = document ? PanelTexts.documentTitle(file, deps.state().get()::titleTaken) : PanelTexts.titleOf(file);
                        deps.intents().dispatch(new Intent.AddPanel(id, title, document));
                        Edt.run(() -> {
                            if (sideOnArrival.remove(file)) {
                                deps.intents().dispatch(new Intent.PlacePanel(id, true));
                            }
                        });
                    }
                    // a click racing the first one's panel, which comes forward on its own
                    case ALREADY_OPEN -> { }
                    case REFUSED -> deps.intents().dispatch(new Intent.Notify(Notice.Level.ERROR, opened.reason(), false));
                }
                Edt.later(() -> {
                    openingFiles.remove(file);
                    sideOnArrival.remove(file);
                });
            });
        });
    }

    /**
     * Brings {@code id}'s tab forward where it is, over a view the user brought forward in the partition, which then
     * shows; a focus that did not change renders nothing that would.
     */
    private void bringForward(final PanelId id) {
        if (parts.workspace().bringForward(id)) {
            layout.revealSidePane();
        }
    }

    /**
     * Shows {@code chosen} in the preview tab, {@code marks} marked (record 141): a panel that holds the file shows
     * them and comes forward; else the file opens as the preview, which replaces the one before, once its read
     * answers; a file previewed meanwhile wins. {@code takeKeyboard} gives the panel the keyboard, else it stays where
     * it is. The check and the read run off the EDT.
     */
    void previewFile(final Path chosen, final HitMarks marks, final boolean takeKeyboard) {
        final Path file = chosen.toAbsolutePath().normalize();
        final Optional<PanelState> open = panelOf(file);
        if (open.isPresent()) {
            previewing = null;
            showHits(open.get().id(), marks, takeKeyboard);
            deps.intents().dispatch(new Intent.FocusPanel(open.get().id()));
            bringForward(open.get().id());
            return;
        }
        // a later hit in the file being read: it arrives with that hit's marks, read once
        final boolean reading = file.equals(previewing);
        previewing = file;
        previewMarks = marks;
        previewKeyboard = takeKeyboard;
        if (reading) {
            return;
        }
        final FocusOrigins.Origin from = focus.originNow();
        UiErrands.start("preview-file", () -> {
            if (!OpenFiles.isText(file)) {
                // a search finds text only: a file that turned binary or grew since shows as other such files do
                Edt.later(() -> {
                    if (file.equals(previewing)) {
                        previewing = null;
                        openFile(file);
                    }
                });
                return;
            }
            final PanelId id = PanelId.random();
            deps.texts().open(id, file).thenAccept(opened -> Edt.later(() -> arrived(id, file, opened, from)));
        });
    }

    /**
     * The read of a file to preview answered: it becomes the preview tab with the latest hit asked for it, unless
     * another file was previewed since; one asked to stay meanwhile arrives as a tab that stays either way, the
     * keyboard in it only while it is still the file asked for.
     */
    private void arrived(
            final PanelId id,
            final Path file,
            final PanelTexts.Opened opened,
            final FocusOrigins.Origin from
    ) {
        final boolean wanted = file.equals(previewing);
        final boolean pinned = pinOnArrival.remove(file);
        final HitMarks marks = wanted ? previewMarks : HitMarks.NONE;
        final boolean takeKeyboard = wanted && (previewKeyboard || pinned);
        if (wanted) {
            previewing = null;
            previewMarks = HitMarks.NONE;
        }
        switch (opened.kind()) {
            case BOUND -> {
                if (!wanted && !pinned) {
                    deps.texts().forget(id);
                    return;
                }
                final boolean document = deps.texts().document(id);
                final String title = document
                        ? PanelTexts.documentTitle(file, deps.state().get()::titleTaken)
                        : PanelTexts.titleOf(file);
                focus.opened("panel:" + id.value(), from);
                showHits(id, marks, takeKeyboard);
                deps.intents().dispatch(new Intent.AddPanel(id, title, document, !pinned));
            }
            // a panel opened the file meanwhile, or holds it closed: it shows the hits
            case ALREADY_OPEN -> {
                if (wanted || pinned) {
                    showInPanelOf(file, marks, takeKeyboard, pinned);
                }
            }
            case REFUSED -> {
                if (wanted || pinned) {
                    deps.intents().dispatch(new Intent.Notify(Notice.Level.ERROR, opened.reason(), false));
                }
            }
        }
    }

    /**
     * The panel that holds {@code file} shows {@code marks}: an open one comes forward, pinned when {@code pin}; a
     * closed one comes back; none, nothing.
     */
    private void showInPanelOf(final Path file, final HitMarks marks, final boolean takeKeyboard, final boolean pin) {
        final Optional<PanelState> open = panelOf(file);
        if (open.isPresent()) {
            if (pin && open.get().preview()) {
                deps.intents().dispatch(new Intent.PinPanel(open.get().id()));
            }
            showHits(open.get().id(), marks, takeKeyboard);
            deps.intents().dispatch(new Intent.FocusPanel(open.get().id()));
            return;
        }
        for (final PanelState p : deps.state().get().closed()) {
            if (deps.texts().file(p.id()).map(f -> PanelTexts.samePath(f, file)).orElse(false)) {
                showHits(p.id(), marks, takeKeyboard);
                deps.intents().dispatch(new Intent.ReopenPanel(p.id()));
                return;
            }
        }
    }

    /** The panel {@code id}, once it shows, marks {@code marks}; with the keyboard when asked. */
    private void showHits(final PanelId id, final HitMarks marks, final boolean takeKeyboard) {
        parts.workspace().whenShown(id, p -> {
            p.showHits(marks);
            if (takeKeyboard) {
                focus.focusOnceShown(p.focusTarget());
            }
        });
    }

    /**
     * Keeps the preview tab of {@code chosen} as a tab that stays, with the keyboard in it (record 141); a preview
     * still being read is kept as it arrives, and a file not previewed opens as {@link #openFile} opens it.
     */
    void pinFile(final Path chosen) {
        final Path file = chosen.toAbsolutePath().normalize();
        final Optional<PanelState> open = panelOf(file);
        if (open.isPresent()) {
            if (open.get().preview()) {
                deps.intents().dispatch(new Intent.PinPanel(open.get().id()));
            }
            deps.intents().dispatch(new Intent.FocusPanel(open.get().id()));
            final EditorPanel p = parts.workspace().panel(open.get().id());
            if (p != null) {
                focus.focusOnceShown(p.focusTarget());
            }
        } else if (file.equals(previewing)) {
            pinOnArrival.add(file);
        } else {
            openFile(file);
        }
    }

    /** The open panel bound to {@code file}. */
    private Optional<PanelState> panelOf(final Path file) {
        return deps.state().get().panels().stream()
                .filter(p -> deps.texts().file(p.id()).map(f -> PanelTexts.samePath(f, file)).orElse(false))
                .findFirst();
    }

    /**
     * A file a session or a link names, opened as the sidebar opens it, or shown in the side pane; a page without a
     * line in the browser. Any thread.
     */
    void showFile(final String session, final String path, final String baseDir, final int line) {
        UiErrands.start("show-file", () -> {
            try {
                final Path p = located(session, path, baseDir);
                // a page a link names opens in the browser, as a web address does; the sidebar opens it to edit
                if (line <= 0 && Files.isRegularFile(p) && Links.isPage(p)) {
                    Links.browsePage(p);
                    return;
                }
                // a file named without a line opens as the sidebar opens it: in a panel, or its panel comes forward;
                // a binary or large one, and one at a line, show in the side pane (record 111)
                if (line <= 0 && Files.isRegularFile(p) && OpenFiles.isText(p)) {
                    Edt.run(() -> openFile(p));
                    return;
                }
                final FileView file = FileView.read(p, line);
                // a file opens on the user's click only: it shows even in a side pane the user hid earlier, and even
                // when it replaces the same file's view in place
                Edt.run(() -> {
                    layout.revealSidePane();
                    openedFiles.add(p);
                    deps.intents().dispatch(new Intent.ShowView(session, file.title(), file.spec(), Placement.SIDE));
                    render.run();
                });
            } catch (final IOException | RuntimeException e) {
                // a path that is no path (a NUL in it) is told like a file that cannot be read
                final String why = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                deps.intents().dispatch(Intent.Notify.logged(LOG, Notice.Level.ERROR, "Cannot open " + path + ": " + why, e));
            }
        });
    }

    /**
     * The file {@code path} names, read from {@code baseDir}; a relative one that is not there is looked for under
     * that folder's tree, then under the repositories of the files {@code session}'s own tool calls named, the latest
     * first, then under the folder tabs' folders (record 111). Off the EDT.
     */
    private Path located(final String session, final String path, final String baseDir) {
        final Path p = FileView.resolve(path, baseDir);
        if (Files.exists(p) || Path.of(HomeDir.expand(path)).isAbsolute()) {
            return p;
        }
        final List<Path> roots = new ArrayList<>();
        if (baseDir != null && !baseDir.isBlank()) {
            roots.add(Path.of(baseDir));
        }
        if (session != null && !session.isEmpty()) {
            roots.addAll(WorkedIn.roots(deps.transcripts().of(session), WorkedIn.MAX_ROOTS));
        }
        for (final FolderRoot f : deps.state().get().folders()) {
            roots.add(Path.of(HomeDir.expand(f.path())));
        }
        return FileSearch.bySuffix(path, roots).orElse(p);
    }

    /** Files ▸ Open file…: a file chosen anywhere, starting in the panels' folder. */
    void openSpec() {
        final Optional<Path> folder = deps.texts().folder();
        if (folder.isPresent()) {
            FileChoosers.open(parts.frame(), "Open a file in a panel", folder.get()).ifPresent(this::openFile);
        }
    }

    /**
     * Opens the most recently closed tab again: a panel of comando's own, still closed, comes back with its text and
     * bindings; a document opens on its file again. A panel removed since is skipped.
     */
    void reopenPanel() {
        final Workspace workspace = parts.workspace();
        while (workspace.hasClosed()) {
            final Workspace.ClosedPanel c = workspace.popClosed().orElseThrow();
            if (!c.document()) {
                if (deps.state().get().closedPanel(c.id()).isPresent()) {
                    deps.intents().dispatch(new Intent.ReopenPanel(c.id()));
                    return;
                }
            } else if (c.file() != null) {
                openFile(c.file(), c.side());
                return;
            }
        }
    }

    /** Panels save as they change; ⌘S saves now and says so, for the habit. */
    void savePanel() {
        final EditorPanel p = parts.workspace().focused();
        if (p != null) {
            final String title = p.panel().title();
            deps.texts().changed(p.panel().id(), p.editor().text());
            deps.texts().saveNow().thenAccept(error -> deps.intents().dispatch(error == null
                    ? new Intent.Notify(Notice.Level.INFO, "Saved " + title + " (panels also save as you type)", false)
                    : Intent.Notify.logged(LOG, Notice.Level.ERROR, "Cannot save the panels: " + error, null)));
        }
    }

    /**
     * Closes a panel; asks first when its shell runs a job in front, which closing would end. The job is looked up off
     * the EDT, so the close happens, or the question opens, once the check answers.
     */
    void closePanel(final PanelId id) {
        // the last panel is never closed: the reducer says so, with nothing asked first
        if (deps.state().get().panels().size() <= 1) {
            deps.intents().dispatch(new Intent.ClosePanel(id));
            return;
        }
        if (!closingPanels.add(id)) {
            return; // a second close while its check runs or its question is open: that one answers it
        }
        parts.lower().terminals().checkJobs(List.of(id), jobs -> {
            try {
                answerClose(id, jobs.get(id));
            } finally {
                closingPanels.remove(id);
            }
        });
    }

    /**
     * Asks, when the panel's shell runs {@code job}, then closes; a panel gone meanwhile is closed without a question.
     */
    private void answerClose(final PanelId id, final String job) {
        if (job != null && deps.state().get().panel(id).isPresent()) {
            final String title = deps.state().get().panel(id).map(x -> x.title()).orElse("the panel");
            final String why = job.equals(TerminalWidget.UNCHECKED_JOB)
                    ? "comando cannot tell whether the shell in its Terminal tab is running a program; closing the panel ends anything it runs."
                    : "The shell in its Terminal tab is running " + job + "; closing the panel ends it.";
            if (!confirm(parts.frame(), "Close " + title + "?", why, "Close panel")) {
                return;
            }
        }
        deps.intents().dispatch(new Intent.ClosePanel(id));
    }

    /** A yes-or-no question with the action named on the button; Cancel is the default. */
    static boolean confirm(final JFrame frame, final String title, final String message, final String action) {
        final Object[] options = {action, "Cancel"};
        return JOptionPane.showOptionDialog(frame, Plain.text(message), title, JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE,
                null, options, options[1]) == 0;
    }

    /** The panel in front shows a log, which is read-only: says that nothing was written. */
    void refuseReadOnly() {
        deps.intents().dispatch(new Intent.Notify(Notice.Level.WARN, "The panel shows a log, which is read-only; nothing was written", false));
    }

    /** Exports the view the user means to a file chosen in the folder of the last export. */
    void export() {
        final Exportable e = focus.capability(Exportable.class);
        if (e == null) {
            return;
        }
        final Path dir = windowLayout.exportDir();
        Capabilities.export(parts.frame(), e, dir, (file, error) -> {
            if (error == null) {
                windowLayout.exportDir(file.toAbsolutePath().getParent());
                deps.intents().dispatch(new Intent.Notify(Notice.Level.INFO, "Exported to " + file, false));
            } else {
                deps.intents().dispatch(Intent.Notify.logged(LOG, Notice.Level.ERROR, "Cannot export to " + file + ": " + error, null));
            }
        });
    }
}
