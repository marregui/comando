package dev.marregui.comando.ui.panels;

import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.ui.kit.async.Edt;
import dev.marregui.comando.ui.kit.async.UiErrands;
import dev.marregui.comando.ui.widgets.terminal.TerminalWidget;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.CardLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * One terminal per editor panel, shown for the focused panel. A shell starts the first time its panel's
 * terminal is shown, in the working directory of the panel's session at that moment, else the home
 * directory; an exit bar and a restart use the directory of the panel's session in the latest rendered
 * state when the bar shows and when Restart is clicked.
 */
public final class TerminalTabs {

    private final CardLayout layout = new CardLayout();
    private final JPanel cards = new JPanel(layout);
    private final Map<PanelId, TerminalWidget> terminals = new HashMap<>();
    private final Function<Path, TerminalWidget> factory;
    private PanelId shown;
    /** The latest state rendered or acted on: where each terminal's exit bar and Restart read the directory. */
    private AppState last;

    public TerminalTabs() {
        this(TerminalWidget::new);
    }

    /** Terminals made by {@code factory} from their first directory, for tests. */
    TerminalTabs(Function<Path, TerminalWidget> factory) {
        this.factory = factory;
    }

    public JComponent component() {
        return cards;
    }

    /**
     * Shows the focused panel's terminal; starts its shell when {@code visible} and not yet started. Every
     * terminal whose shell is not running takes its panel's current directory, so the next start, a send
     * or a restart opens the shell where the panel's session works.
     */
    public void render(AppState s, boolean visible) {
        last = s;
        for (Map.Entry<PanelId, TerminalWidget> e : terminals.entrySet()) {
            if (!e.getValue().isRunning()) {
                s.panel(e.getKey()).ifPresent(p -> e.getValue().setDirectory(directory(s, p)));
            }
        }
        TerminalWidget t = show(s);
        if (visible && !t.isStarted()) {
            t.start();
        }
    }

    /** The focused panel's terminal, created and shown; its directory is current when its shell is not running. */
    private TerminalWidget show(AppState s) {
        last = s;
        PanelId id = s.focused();
        Path dir = directory(s, s.focusedPanel());
        TerminalWidget t = terminals.computeIfAbsent(id, k -> {
            TerminalWidget w = factory.apply(dir);
            w.setDirectorySource(() -> currentDirectory(k));
            cards.add(w.component(), k.value());
            return w;
        });
        if (!t.isRunning()) {
            t.setDirectory(dir);
        }
        if (!id.equals(shown)) {
            shown = id;
            layout.show(cards, id.value());
        }
        return t;
    }

    /** The directory of {@code panel}'s session in the latest state, or null when the panel is gone from it. */
    private Path currentDirectory(PanelId panel) {
        AppState s = last;
        return s == null ? null : s.panel(panel).map(p -> directory(s, p)).orElse(null);
    }

    static Path directory(AppState s) {
        return directory(s, s.focusedPanel());
    }

    static Path directory(AppState s, PanelState panel) {
        return s.session(panel.session()).map(SessionState::spec).map(x -> Path.of(x.workDir()))
                .filter(Files::isDirectory).orElse(Path.of(System.getProperty("user.home")));
    }

    /** The focused panel's terminal, null before the panel's terminal was first rendered. */
    public TerminalWidget focused() {
        return shown == null ? null : terminals.get(shown);
    }

    /** Restarts the focused panel's shell in the working directory of the panel's session now. */
    public void restartFocused(AppState s) {
        TerminalWidget t = show(s);
        t.close();
        t.setDirectory(directory(s, s.focusedPanel()));
        t.start();
    }

    /** Whether the focused panel's shell runs; false before its terminal was first rendered. */
    public boolean focusedRunning() {
        TerminalWidget t = focused();
        return t != null && t.isRunning();
    }

    /** Starts the focused panel's shell, when it is not running, in the working directory of the panel's session. */
    public void startFocused(AppState s) {
        TerminalWidget t = show(s);
        if (!t.isRunning()) {
            t.start();
        }
    }

    /** Stops the focused panel's shell and what runs in it, and frees its emulator. */
    public void stopFocused() {
        TerminalWidget t = focused();
        if (t != null) {
            t.close();
        }
    }

    /**
     * Starts the focused panel's shell when it is not running, in the current directory, and gives it the
     * keyboard. Safe right after a render: the terminal is created and shown here when the render did not.
     */
    public void focusShown(AppState s) {
        TerminalWidget t = show(s);
        if (!t.isRunning()) {
            t.start();
        }
        t.focus();
        Edt.later(t::focus); // a terminal added in this event is focusable once it has been laid out
    }

    /** Whether the panel's shell runs a program (a build, a server, an ssh session). */
    public boolean hasRunningJob(PanelId panel) {
        TerminalWidget t = terminals.get(panel);
        return t != null && t.hasRunningJob();
    }

    /** The command of the job the panel's shell runs in front, or empty. */
    public Optional<String> runningJob(PanelId panel) {
        TerminalWidget t = terminals.get(panel);
        return t == null ? Optional.empty() : t.runningJob();
    }

    /** How long the job checks of a quit or a panel close may take together, whatever the number of shells. */
    static final long CHECK_MILLIS = 2_000;

    /**
     * Finds, off the EDT, the job each listed panel's shell runs in front, and gives {@code answer} on the EDT the
     * panels whose shell runs one, by panel; at once when no listed panel has a running shell. The checks run together,
     * {@link #CHECK_MILLIS} in all; a shell not answered by then counts as running
     * {@link TerminalWidget#UNCHECKED_JOB}, so the question asks.
     */
    public void checkJobs(Collection<PanelId> panels, Consumer<Map<PanelId, String>> answer) {
        Map<PanelId, TerminalWidget.Shell> shells = new LinkedHashMap<>();
        for (PanelId id : panels) {
            TerminalWidget t = terminals.get(id);
            if (t != null) {
                t.shell().ifPresent(shell -> shells.put(id, shell));
            }
        }
        if (shells.isEmpty()) {
            answer.accept(Map.of());
            return;
        }
        UiErrands.start("terminal-jobs", () -> {
            Map<PanelId, String> jobs = TerminalWidget.foregroundJobs(shells, CHECK_MILLIS);
            Edt.later(() -> answer.accept(jobs));
        });
    }

    /** {@link #checkJobs} for every panel's shell. */
    public void checkAllJobs(Consumer<Map<PanelId, String>> answer) {
        checkJobs(List.copyOf(terminals.keySet()), answer);
    }

    /** "man cat in notes' Terminal tab": a job a question names, with where it runs, comando's own terminal. */
    public static String jobLine(String job, String panelTitle) {
        return job + " in " + panelTitle + (panelTitle.endsWith("s") ? "'" : "'s") + " Terminal tab";
    }

    /** Ends the shell of a closed panel. */
    public void forget(PanelId panel) {
        TerminalWidget t = terminals.remove(panel);
        if (t != null) {
            t.close();
            cards.remove(t.component());
        }
        if (panel.equals(shown)) {
            shown = null;
        }
    }

    public void closeAll() {
        terminals.values().forEach(TerminalWidget::close);
        terminals.clear();
        cards.removeAll();
        shown = null;
    }

    TerminalWidget terminal(PanelId panel) {
        return terminals.get(panel);
    }
}
