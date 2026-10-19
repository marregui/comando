package dev.marregui.comando.ui.shell;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.FolderRoot;
import dev.marregui.comando.core.model.HomeDir;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.core.view.Placement;
import dev.marregui.comando.core.view.ViewSpec;
import dev.marregui.comando.ui.kit.action.Command;
import dev.marregui.comando.ui.kit.async.Edt;
import dev.marregui.comando.ui.kit.desktop.Browser;
import dev.marregui.comando.ui.kit.text.Plain;

import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.prefs.Preferences;

/**
 * The window's help: the tutorial, the tool and folder settings as documents, the keyboard and Markdown sheets, the
 * Claude Code kit, About, the log and the data root. Each opens through the window it is given; file and process work
 * runs on a thread of its own, Swing on the EDT.
 */
final class HelpActions {

    private static final System.Logger LOG = System.getLogger(HelpActions.class.getName());
    /** False once the user said not to offer the kit when comando starts. */
    static final String KIT_OFFER = "kit.offer";

    private final ComandoUi.Dependencies deps;
    private final Preferences prefs;
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
    HelpActions(ComandoUi.Dependencies deps, Preferences prefs, Supplier<JFrame> frame, Consumer<Path> openFile,
                Consumer<String> runCommand, Runnable showSidePane, Supplier<List<Command>> commands) {
        this.deps = deps;
        this.prefs = prefs;
        this.frame = frame;
        this.openFile = openFile;
        this.runCommand = runCommand;
        this.showSidePane = showSidePane;
        this.commands = commands;
    }

    /** The tutorial's file exists from the start, to be opened as any file, not only from Help. */
    void writeTutorial() {
        Path home = deps.home();
        if (home == null) {
            return;
        }
        Thread.ofVirtual().name("write-tutorial").start(() -> {
            try {
                HelpFiles.writeTutorial(home);
            } catch (IOException | RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "cannot write the tutorial into " + home + ": " + e.getMessage());
            }
        });
    }

    /** The tutorial in a panel, as a document: its file in comando's folder, written from this build's copy first. */
    void showTutorial() {
        Path home = deps.home();
        Thread.ofVirtual().name("show-tutorial").start(() -> {
            try {
                Path file = HelpFiles.writeTutorial(home);
                Edt.later(() -> openFile.accept(file));
            } catch (IOException | RuntimeException e) {
                deps.intents().dispatch(new Intent.Notify(Notice.Level.ERROR, "Cannot show the tutorial: " + e.getMessage(), false));
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
        String claude = deps.state().get().sessions().stream().filter(s -> !s.info().cliVersion().isEmpty())
                .max(Comparator.comparingLong(SessionState::startedMillis)).map(s -> s.info().cliVersion()).orElse("");
        about = About.show(frame.get(), About.load(About.facts(deps.version(), deps.home(), claude)), this::followAbout);
    }

    /** A link of the About card: the tutorial, the data root, or a page in the browser. */
    private void followAbout(String target) {
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

    /** Tools ▸ Help ▸ Claude Code kit: what the kit is here, and an install when there is anything to install. */
    void showKit() {
        Thread.ofVirtual().name("kit").start(() -> {
            try {
                Setup.KitOffer offer = deps.setup().kit();
                Edt.later(() -> askKit(offer, false));
            } catch (IOException | RuntimeException e) {
                deps.intents().dispatch(new Intent.Notify(Notice.Level.ERROR, "Cannot read the Claude Code kit's state: " + e.getMessage(), false));
            }
        });
    }

    /**
     * Offers the kit when comando starts: once the window shows, and unless the user said not to ask again. Any
     * thread.
     */
    void offerKit(Setup.KitOffer offer) {
        Edt.later(() -> {
            if (frame.get() != null && prefs.getBoolean(KIT_OFFER, true)) {
                askKit(offer, true);
            }
        });
    }

    private void askKit(Setup.KitOffer found, boolean atStart) {
        Setup.KitOffer offer = found.withFolders(Setup.missingFolders(deps.state().get().folders(), deps.setup().kitFolders()).stream()
                .map(f -> "Add " + f.name() + " (" + HomeDir.shorten(f.path()) + ") to the Files tab").toList());
        Object[] options = offer.buttons(atStart).toArray();
        int pick = JOptionPane.showOptionDialog(frame.get(), Plain.text(offer.message()), "Claude Code kit", JOptionPane.DEFAULT_OPTION,
                offer.pending() ? JOptionPane.QUESTION_MESSAGE : JOptionPane.INFORMATION_MESSAGE, null, options,
                options[atStart && options.length > 1 ? 1 : 0]);
        if (atStart && pick == Setup.DONT_ASK) {
            prefs.putBoolean(KIT_OFFER, false);
        }
        if (!offer.pending() || pick != Setup.DO) {
            return;
        }
        Thread.ofVirtual().name("kit-install").start(() -> {
            try {
                List<String> said = new ArrayList<>(deps.setup().installKit());
                List<String> added = addKitFolders();
                if (!added.isEmpty()) {
                    said.add("added " + String.join(" and ", added) + " to the Files tab");
                }
                deps.intents().dispatch(new Intent.Notify(Notice.Level.INFO, said.isEmpty()
                        ? "The Claude Code kit was already in place"
                        : "Claude Code kit: " + String.join("; ", said) + ". New Claude sessions load it.", false));
            } catch (IOException | RuntimeException e) {
                deps.intents().dispatch(new Intent.Notify(Notice.Level.ERROR, "The Claude Code kit install stopped: " + e.getMessage(), false));
            }
        });
    }

    /** Adds the kit's folders the Files tab lacks; their names. */
    private List<String> addKitFolders() {
        List<String> added = new ArrayList<>();
        for (FolderRoot f : Setup.missingFolders(deps.state().get().folders(), deps.setup().kitFolders())) {
            deps.intents().dispatch(new Intent.AddFolder(f));
            added.add(f.name());
        }
        return added;
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

    void reveal(Path dir) {
        Thread.ofVirtual().name("reveal").start(() -> {
            try {
                Desktop.getDesktop().open(dir.toFile());
            } catch (IOException | RuntimeException e) {
                deps.intents().dispatch(new Intent.Notify(Notice.Level.ERROR, "Cannot open " + dir + ": " + e.getMessage(), false));
            }
        });
    }

    /** The log comando writes now, in a panel, as a document: read-only, following its end, reloaded as it grows. */
    void showLog() {
        Path log = HelpFiles.log(deps.logDir());
        if (Files.isRegularFile(log)) {
            openFile.accept(log);
        } else {
            deps.intents().dispatch(new Intent.Notify(Notice.Level.WARN, "No log yet at " + log, false));
        }
    }
}
