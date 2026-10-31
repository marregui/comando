package dev.marregui.comando.ui.shell;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.JDialog;
import javax.swing.JFrame;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.core.view.Placement;
import dev.marregui.comando.core.view.ViewSpec;
import dev.marregui.comando.ui.kit.action.Command;
import dev.marregui.comando.ui.kit.async.Edt;
import dev.marregui.comando.ui.kit.async.UiErrands;
import dev.marregui.comando.ui.kit.desktop.Browser;

/**
 * The window's help: the tutorial, the keyboard and Markdown sheets, About, the logs and the data root. Each opens
 * through the window it is given; file and process work runs on an errand of the window, Swing on the EDT.
 */
final class HelpActions {

    private static final System.Logger LOG = System.getLogger(HelpActions.class.getName());

    private final ComandoUi.Dependencies deps;
    private final Supplier<JFrame> frame;
    private final Consumer<Path> openFile;
    private final Consumer<String> runCommand;
    private final Runnable showSidePane;
    private final Supplier<List<Command>> commands;
    private JDialog about;

    /**
     * {@code openFile} opens a file in a panel; {@code runCommand} runs a command by id; {@code showSidePane} shows the
     * side pane even when the user hid it; {@code commands} lists every command, for the keyboard sheet.
     */
    HelpActions(final ComandoUi.Dependencies deps, final Supplier<JFrame> frame, final Consumer<Path> openFile,
                final Consumer<String> runCommand, final Runnable showSidePane, final Supplier<List<Command>> commands) {
        this.deps = deps;
        this.frame = frame;
        this.openFile = openFile;
        this.runCommand = runCommand;
        this.showSidePane = showSidePane;
        this.commands = commands;
    }

    /**
     * Writes the tutorial's file off the EDT, so it exists from the start and opens as any file, not only from Help.
     */
    void writeTutorial() {
        final Path home = deps.home();
        if (home == null) {
            return;
        }
        UiErrands.start("write-tutorial", () -> {
            try {
                HelpFiles.writeTutorial(home);
            } catch (IOException | RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "cannot write the tutorial [home=" + home + ']', e);
            }
        });
    }

    /** The tutorial in a panel, as a document: its file in comando's folder, written from this build's copy first. */
    void showTutorial() {
        final Path home = deps.home();
        UiErrands.start("show-tutorial", () -> {
            try {
                final Path file = HelpFiles.writeTutorial(home);
                Edt.later(() -> openFile.accept(file));
            } catch (IOException | RuntimeException e) {
                deps.intents().dispatch(Intent.Notify.logged(LOG, Notice.Level.ERROR, "Cannot show the tutorial: " + e.getMessage(), e));
            }
        });
    }

    /**
     * About comando: the card of {@link About}, over the window; when it is open already it comes forward. The Claude
     * Code version is the one the most recent session reported; before any session started there is none to show.
     */
    void showAbout() {
        if (about != null && about.isDisplayable()) {
            about.toFront();
            return;
        }
        final String claude = deps.state().get().sessions().stream().filter(s -> !s.info().cliVersion().isEmpty())
                .max(Comparator.comparingLong(SessionState::startedMillis)).map(s -> s.info().cliVersion()).orElse("");
        about = About.show(frame.get(), About.load(About.facts(deps.version(), deps.home(), claude)), this::followAbout);
    }

    /** A link of the About card: the tutorial, the data root, or a page in the browser. */
    private void followAbout(final String target) {
        switch (target) {
            case "comando:tutorial" -> runCommand.accept("help.tutorial");
            case "comando:home" -> {
                if (deps.home() != null) {
                    reveal(deps.home());
                }
            }
            default -> {
                if (Browser.isWebLink(target)) {
                    Browser.open(target);
                }
            }
        }
    }

    /** Every command with a key, by menu, as a table in the side pane. */
    void showKeys() {
        showSidePane.run();
        deps.intents().dispatch(new Intent.ShowView("", "Keyboard shortcuts", new ViewSpec.Markdown(Keys.markdown(commands.get())), Placement.SIDE));
    }

    /** The Markdown sheet in the side pane, as the keyboard shortcuts are, to keep beside the editor. */
    void showMarkdownSheet() {
        showSidePane.run();
        deps.intents().dispatch(new Intent.ShowView("", "Markdown", new ViewSpec.Markdown(MarkdownSheet.markdown()), Placement.SIDE));
    }

    /** Opens {@code dir} in the desktop's file manager, off the EDT; a failure is a notice. */
    void reveal(final Path dir) {
        UiErrands.start("reveal", () -> {
            try {
                Desktop.getDesktop().open(dir.toFile());
            } catch (IOException | RuntimeException e) {
                deps.intents().dispatch(Intent.Notify.logged(LOG, Notice.Level.ERROR, "Cannot open " + dir + ": " + e.getMessage(), e));
            }
        });
    }

    /**
     * The log {@code server} writes now, in this instance's data root, in a panel as comando's own is shown; a notice
     * naming where it would be when there is none yet.
     */
    void showLog(final ServerToggle server) {
        final Path log = server.log();
        if (log != null && Files.isRegularFile(log)) {
            openFile.accept(log);
        } else {
            deps.intents().dispatch(new Intent.Notify(Notice.Level.WARN, log == null ? server.name() + " keeps no log comando knows of"
                    : "No " + server.name() + " log yet at " + log + "; start " + server.name() + " first", false));
        }
    }

    /** The log comando writes now, in a panel, as a document: read-only, following its end, reloaded as it grows. */
    void showLog() {
        final Path log = HelpFiles.log(deps.logDir());
        if (Files.isRegularFile(log)) {
            openFile.accept(log);
        } else {
            deps.intents().dispatch(new Intent.Notify(Notice.Level.WARN, "No log yet at " + log, false));
        }
    }
}
