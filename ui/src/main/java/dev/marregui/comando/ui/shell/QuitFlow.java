package dev.marregui.comando.ui.shell;

import java.awt.desktop.QuitResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.swing.JFrame;
import javax.swing.JMenuBar;

import dev.marregui.comando.core.model.InstanceName;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.core.view.ViewSpec;
import dev.marregui.comando.ui.panels.TerminalTabs;

/**
 * Quitting: what it would stop, the question asked when it would stop work, and the closing window that stays until
 * the application has closed. EDT only.
 */
final class QuitFlow {

    private static final System.Logger LOG = System.getLogger(QuitFlow.class.getName());

    private final ComandoUi.Dependencies deps;
    private final ShellParts parts;
    private final LayoutKeeper layout;
    /** Stops what the window runs; once, at quit or at close. */
    private final Runnable teardown;
    private boolean quitting;
    private boolean confirmingQuit;
    /** The macOS quit requests waiting for the answer to the open question. */
    private final List<QuitResponse> pendingQuits = new ArrayList<>();

    QuitFlow(final ComandoUi.Dependencies deps, final ShellParts parts, final LayoutKeeper layout, final Runnable teardown) {
        this.deps = deps;
        this.parts = parts;
        this.layout = layout;
        this.teardown = teardown;
    }

    /** Whether quitting has begun: the window keeps its closing title. */
    boolean quitting() {
        return quitting;
    }

    /** A macOS quit request, answered with the quit it triggers. */
    void request(final QuitResponse response) {
        // a trigger that arrives while the question is open is answered with it
        pendingQuits.add(response);
        quit();
    }

    /**
     * Quits, asking first when work would stop. In two steps, so the EDT never waits on a shell: the terminals' jobs
     * are looked up off the EDT ({@link TerminalTabs#checkAllJobs}), then {@link #quitWith} asks or quits. A trigger
     * while either step is under way is answered with it; when the user keeps comando open, the macOS quit requests
     * waiting are cancelled.
     */
    void quit() {
        if (quitting || confirmingQuit) {
            return;
        }
        confirmingQuit = true;
        parts.lower().terminals().checkAllJobs(this::quitWith);
    }

    private void quitWith(final Map<PanelId, String> jobs) {
        final List<String> work = runningWork(jobs);
        final JFrame frame = parts.frame();
        final boolean keep = !work.isEmpty() && !PanelFiles.confirm(frame, "Quit comando?", quitQuestion(work), "Quit");
        confirmingQuit = false;
        if (keep) {
            pendingQuits.forEach(QuitResponse::cancelQuit);
            pendingQuits.clear();
            return;
        }
        quitting = true;
        final long start = System.nanoTime();
        layout.saveQuietly();
        teardown.run();
        final long millis = (System.nanoTime() - start) / 1_000_000;
        LOG.log(System.Logger.Level.INFO, "quitting [window teardown millis=" + millis + ']');
        // the window stays, saying so, until the application has closed (close() disposes it): a relaunch meanwhile
        // finds the lock held, and the user can see why
        frame.setTitle(InstanceName.display(InstanceName.current()) + " — closing…");
        final ClosingCover cover = new ClosingCover();
        frame.setGlassPane(cover);
        cover.setVisible(true);
        cover.requestFocusInWindow();
        final JMenuBar bar = frame.getJMenuBar();
        for (int i = 0; bar != null && i < bar.getMenuCount(); i++) {
            if (bar.getMenu(i) != null) {
                bar.getMenu(i).setEnabled(false);
            }
        }
        deps.onQuit().run();
    }

    /** "Quitting stops main's turn.", or what it stops one per line when several things run. */
    static String quitQuestion(final List<String> work) {
        return work.size() == 1 ? "Quitting stops " + work.getFirst() + "."
                : "Quitting stops:\n" + work.stream().map(w -> "• " + w).collect(Collectors.joining("\n"));
    }

    /**
     * What quitting would stop: running queries, sessions in a turn, shells running a job ({@code jobs}, by panel);
     * empty when nothing.
     */
    private List<String> runningWork(final Map<PanelId, String> jobs) {
        final AppState s = deps.state().get();
        final List<String> work = new ArrayList<>();
        final long queries = s.runs().stream().filter(r -> r.status().isActive()).count();
        if (queries > 0) {
            work.add(queries + (queries == 1 ? " running query" : " running queries"));
        }
        final List<String> busy = s.sessions().stream().filter(SessionState::isBusy).map(SessionState::name).toList();
        if (!busy.isEmpty()) {
            work.add(String.join(", ", busy) + (busy.size() == 1 ? "'s turn" : "' turns"));
        }
        final List<String> waiting = s.sessions().stream().filter(SessionState::isWaiting).map(SessionState::name).toList();
        if (!waiting.isEmpty()) {
            work.add(String.join(", ", waiting) + (waiting.size() == 1 ? "'s background work" : "' background work"));
        }
        // each job named with the panel whose Terminal tab runs it, in the panels' order
        for (final PanelState p : s.panels()) {
            final String job = jobs.get(p.id());
            if (job != null) {
                work.add(TerminalTabs.jobLine(job, p.title()));
            }
        }
        // Gradle runs show as console views until they finish
        s.views().stream().filter(v -> v.spec() instanceof ViewSpec.Console c && c.state() == ViewSpec.RunState.RUNNING)
                .forEach(v -> work.add(v.title()));
        return work;
    }
}
