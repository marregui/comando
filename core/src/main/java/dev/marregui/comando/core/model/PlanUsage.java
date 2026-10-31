package dev.marregui.comando.core.model;

import java.util.List;
import java.util.Objects;

/**
 * A plan's usage limits as its command-line tool reports them. Claude Code's: the current session's window, the week
 * across all models, and the week of a single model (Fable); Codex's: the 5-hour session window and the week. A read
 * that failed has no windows and says why.
 *
 * @param provider     whose plan: {@link #CLAUDE} or {@link #CODEX}
 * @param windows      in the order the tool lists them
 * @param readAtMillis when the read finished
 * @param problem      why there are no windows; empty when the read succeeded
 */
public record PlanUsage(String provider, List<Window> windows, long readAtMillis, String problem) {

    public static final String CLAUDE = "Claude";
    public static final String CODEX = "Codex";

    /**
     * One limit window.
     *
     * @param name    "Session", "Week", or the model a weekly window is for, such as "Fable"
     * @param percent how much of the window is used, 0 to 100
     * @param resets  when it resets, worded as Claude Code words it ("Oct 14 at 7:19pm (Europe/Madrid)"); empty when
     *                not said
     */
    public record Window(String name, int percent, String resets) {
        public Window {
            Objects.requireNonNull(name, "name");
            resets = resets == null ? "" : resets;
        }
    }

    public PlanUsage {
        Objects.requireNonNull(provider, "provider");
        windows = List.copyOf(windows);
        problem = problem == null ? "" : problem;
    }

    /** Claude Code's plan. */
    public PlanUsage(final List<Window> windows, final long readAtMillis, final String problem) {
        this(CLAUDE, windows, readAtMillis, problem);
    }

    /** A failed read of Claude Code's plan. */
    public static PlanUsage failed(final String problem, final long readAtMillis) {
        return failed(CLAUDE, problem, readAtMillis);
    }

    public static PlanUsage failed(final String provider, final String problem, final long readAtMillis) {
        return new PlanUsage(provider, List.of(), readAtMillis, problem);
    }
}
