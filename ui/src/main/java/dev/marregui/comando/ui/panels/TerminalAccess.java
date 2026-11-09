package dev.marregui.comando.ui.panels;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.port.TerminalPort;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.ui.kit.async.Edt;
import dev.marregui.comando.ui.widgets.terminal.TerminalWidget;

/**
 * The panels' Terminals as agents reach them (record 148). Terminals are created, started, typed into and looked at on
 * the EDT, each hop bounded by {@link #EDT_BOUND}; a page and the job checks are read on the calling thread. A shell an
 * agent starts is not brought to the front. Until the window is built, every terminal is listed as not started and
 * every action is refused.
 */
public final class TerminalAccess implements TerminalPort {

    /** How long a call waits for the EDT: the port answers within a few seconds, whatever the EDT does. */
    private static final Duration EDT_BOUND = Duration.ofSeconds(2);
    /** How long an interrupt waits for the job to end before it answers that the job still runs (record 148). */
    private static final long INTERRUPT_WAIT_MILLIS = 2_000;
    /** How often an interrupt checks whether the job ended, as the tools' waits poll: one {@code ps} each time. */
    private static final long INTERRUPT_POLL_MILLIS = 100;
    private static final String NOT_READY = "refused: terminals are not ready";
    private static final String NO_ANSWER = "refused: the window did not answer within " + EDT_BOUND.toSeconds() + " s";

    private final Supplier<TerminalTabs> tabs;
    private final Supplier<AppState> state;

    /**
     * @param tabs  the panels' terminals, null before the window is built; called on the EDT only
     * @param state the latest state; called on the EDT only
     */
    public TerminalAccess(final Supplier<TerminalTabs> tabs, final Supplier<AppState> state) {
        this.tabs = tabs;
        this.state = state;
    }

    @Override
    public List<Terminal> list() {
        final List<Seen> seen = onEdt(this::seen).orElseThrow(TerminalAccess::noAnswer);
        final Map<PanelId, TerminalWidget.Shell> shells = new LinkedHashMap<>();
        for (final Seen x : seen) {
            if (x.shell() != null) {
                shells.put(x.panel().id(), x.shell());
            }
        }
        final Map<PanelId, String> jobs = shells.isEmpty() ? Map.of() : TerminalWidget.foregroundJobs(shells, TerminalTabs.CHECK_MILLIS);
        final List<Terminal> out = new ArrayList<>(seen.size());
        for (final Seen x : seen) {
            final PanelId id = x.panel().id();
            out.add(new Terminal(id, x.panel().title(), x.started(), x.shell() != null, x.shell() == null ? -1 : x.shell().pid(),
                    jobs.getOrDefault(id, ""), x.directory(), x.next(), x.alternate()));
        }
        return out;
    }

    /** Every editor panel's terminal as the EDT sees it; on the EDT. */
    private List<Seen> seen(final TerminalTabs t) {
        final AppState s = state.get();
        final List<Seen> out = new ArrayList<>(s.panels().size());
        for (final PanelState p : s.panels()) {
            final TerminalWidget w = t == null ? null : t.terminal(p.id());
            if (w == null) {
                out.add(new Seen(p, false, null, TerminalTabs.directory(s, p), 0, false));
            } else {
                out.add(new Seen(p, w.isStarted(), w.shell().orElse(null), w.directory(), w.nextLine(), w.alternateScreen()));
            }
        }
        return out;
    }

    @Override
    public String start(final PanelId panel, final Optional<Path> directory) {
        if (directory.isPresent() && !Files.isDirectory(directory.get())) {
            return "refused: no such directory [directory=" + directory.get() + ']';
        }
        return answer(t -> {
            final AppState s = state.get();
            final Optional<PanelState> p = s.panel(panel);
            if (p.isEmpty()) {
                return unknown(panel);
            }
            final String title = p.get().title();
            final TerminalWidget w = t.ensure(panel);
            if (w.isRunning()) {
                return "the shell of " + title + " already runs in " + w.directory();
            }
            w.setDirectory(directory.orElseGet(() -> TerminalTabs.directory(s, p.get())));
            w.start();
            return w.isRunning() ? "started a shell in " + w.directory() + " in the Terminal of " + title
                    : "refused: cannot start a shell in " + w.directory();
        });
    }

    @Override
    public String send(final PanelId panel, final String text, final boolean enter) {
        return answer(t -> switch (reach(t, panel)) {
            case Refused(final String refusal) -> refusal;
            case Running(final String title, final TerminalWidget widget, final TerminalWidget.Shell _) -> widget.type(enter ? text + '\r' : text)
                    ? "typed " + text.length() + " characters" + (enter ? " and Enter" : "") + " into the Terminal of " + title
                    : notRunning(title);
        });
    }

    @Override
    public String interrupt(final PanelId panel) {
        final Optional<Reach> reached = onEdt(t -> t == null ? new Refused(NOT_READY) : reach(t, panel));
        if (reached.isEmpty()) {
            return NO_ANSWER;
        }
        return switch (reached.get()) {
            case Refused(final String refusal) -> refusal;
            case Running(final String title, final TerminalWidget widget, final TerminalWidget.Shell shell) -> interrupt(panel, title, widget, shell);
        };
    }

    /** Ctrl-C to the job {@code shell} runs in front, when it runs one, then whether the job ended within the wait. */
    private String interrupt(final PanelId panel, final String title, final TerminalWidget widget, final TerminalWidget.Shell shell) {
        final Optional<String> job = job(panel, shell);
        if (job.isEmpty()) {
            return "refused: no job runs in the Terminal of " + title;
        }
        final Optional<Boolean> typed = onEdt(_ -> widget.type("\u0003"));
        if (typed.isEmpty()) {
            return NO_ANSWER;
        }
        if (!typed.get()) {
            return notRunning(title);
        }
        final String sent = "sent Ctrl-C to " + TerminalTabs.jobLine(job.get(), title);
        final long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(INTERRUPT_WAIT_MILLIS);
        Optional<String> still = job;
        try {
            while (still.isPresent() && System.nanoTime() - deadlineNanos < 0) {
                Thread.sleep(INTERRUPT_POLL_MILLIS);
                still = job(panel, shell);
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return sent + "; interrupted before it was seen to end";
        }
        return still.isEmpty() ? sent + "; it ended" : sent + "; it still runs after " + INTERRUPT_WAIT_MILLIS / 1_000 + " s: " + still.get();
    }

    @Override
    public String stop(final PanelId panel) {
        return answer(t -> {
            final Optional<PanelState> p = state.get().panel(panel);
            if (p.isEmpty()) {
                return unknown(panel);
            }
            final TerminalWidget w = t.terminal(panel);
            final boolean running = w != null && w.isRunning();
            t.stop(panel);
            return running ? "stopped the shell of " + p.get().title() : "the shell of " + p.get().title() + " was not running";
        });
    }

    @Override
    public Optional<Page> read(final PanelId panel, final long since, final int lines, final int maxChars) {
        return widget(panel).flatMap(w -> w.page(since, lines, maxChars));
    }

    @Override
    public Optional<Long> lastOutputNanos(final PanelId panel) {
        return onEdt(t -> t == null ? Optional.<Long>empty() : Optional.ofNullable(t.terminal(panel)).flatMap(TerminalWidget::lastOutputNanos))
                .orElseThrow(TerminalAccess::noAnswer);
    }

    /** The panel's terminal, empty when it has none or the window is not built. */
    private Optional<TerminalWidget> widget(final PanelId panel) {
        return onEdt(t -> Optional.ofNullable(t == null ? null : t.terminal(panel))).orElseThrow(TerminalAccess::noAnswer);
    }

    /** The panel's running shell, or the refusal; on the EDT. */
    private Reach reach(final TerminalTabs t, final PanelId panel) {
        final Optional<PanelState> p = state.get().panel(panel);
        if (p.isEmpty()) {
            return new Refused(unknown(panel));
        }
        final TerminalWidget w = t.terminal(panel);
        final Optional<TerminalWidget.Shell> shell = w == null ? Optional.empty() : w.shell();
        return shell.isEmpty() ? new Refused(notRunning(p.get().title())) : new Running(p.get().title(), w, shell.get());
    }

    /** The job {@code shell} runs in front, checked on the calling thread. */
    private static Optional<String> job(final PanelId panel, final TerminalWidget.Shell shell) {
        return Optional.ofNullable(TerminalWidget.foregroundJobs(Map.of(panel, shell), TerminalTabs.CHECK_MILLIS).get(panel));
    }

    /** {@code task}'s answer over the built window's terminals, on the EDT. */
    private String answer(final Function<TerminalTabs, String> task) {
        return onEdt(t -> t == null ? NOT_READY : task.apply(t)).orElse(NO_ANSWER);
    }

    /**
     * {@code task} run on the EDT over the terminals (null before the window is built); empty when the EDT did not run
     * it within {@link #EDT_BOUND}. The task never answers null.
     */
    private <T> Optional<T> onEdt(final Function<TerminalTabs, T> task) {
        final AtomicReference<T> out = new AtomicReference<>();
        if (!Edt.runAndWait(() -> out.set(task.apply(tabs.get())), EDT_BOUND)) {
            return Optional.empty();
        }
        return Optional.of(out.get());
    }

    private static IllegalStateException noAnswer() {
        return new IllegalStateException("cannot reach the terminals, the window did not answer [boundMillis=" + EDT_BOUND.toMillis() + ']');
    }

    private static String unknown(final PanelId panel) {
        return "refused: no such panel [panel=" + panel.value() + ']';
    }

    private static String notRunning(final String title) {
        return "refused: the shell of " + title + " is not running";
    }

    /** One panel's terminal as the EDT saw it; {@code shell} null when no shell runs. */
    private record Seen(PanelState panel, boolean started, TerminalWidget.Shell shell, Path directory, long next, boolean alternate) {
    }

    /** A panel's running shell, or why an action cannot reach it. */
    private sealed interface Reach permits Refused, Running {
    }

    private record Refused(String answer) implements Reach {
    }

    private record Running(String title, TerminalWidget widget, TerminalWidget.Shell shell) implements Reach {
    }
}
