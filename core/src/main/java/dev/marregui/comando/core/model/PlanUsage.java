package dev.marregui.comando.core.model;

import java.util.List;
import java.util.Objects;

/**
 * The plan's usage limits as Claude Code reports them: the current session's window, the week across all models,
 * and the week of a single model (Fable). A read that failed has no windows and says why.
 *
 * @param windows      in the order Claude Code lists them
 * @param readAtMillis when the read finished
 * @param problem      why there are no windows; empty when the read succeeded
 */
public record PlanUsage(List<Window> windows, long readAtMillis, String problem) {

    /**
     * One limit window.
     *
     * @param name    "Session", "Week", or the model a weekly window is for, such as "Fable"
     * @param percent how much of the window is used, 0 to 100
     * @param resets  when it resets, as Claude Code words it ("Oct 14 at 7:19pm (Europe/Madrid)"); empty when not said
     */
    public record Window(String name, int percent, String resets) {
        public Window {
            Objects.requireNonNull(name, "name");
            resets = resets == null ? "" : resets;
        }
    }

    public PlanUsage {
        windows = List.copyOf(windows);
        problem = problem == null ? "" : problem;
    }

    public static PlanUsage failed(String problem, long readAtMillis) {
        return new PlanUsage(List.of(), readAtMillis, problem);
    }
}
