package dev.marregui.comando.core.state;

import java.util.Objects;

/**
 * When the session says its work ends: the step it does now, and the goal the whole task ends at, each with the time it
 * expects to be done by. The session sets it with the {@code session_eta} action; a part with no time is not shown.
 *
 * @param step         the step under way, empty when none
 * @param stepAtMillis when the step is expected to end, 0 when it has no estimate
 * @param goal         the task's end state, empty when none
 * @param goalAtMillis when the goal is expected to be reached, 0 when it has no estimate
 */
public record SessionEta(String step, long stepAtMillis, String goal, long goalAtMillis) {

    /** No estimate. */
    public static final SessionEta NONE = new SessionEta("", 0L, "", 0L);

    public SessionEta {
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(goal, "goal");
        if (stepAtMillis < 0L || goalAtMillis < 0L) {
            throw new IllegalArgumentException("an ETA is negative [stepAtMillis=" + stepAtMillis + ", goalAtMillis="
                    + goalAtMillis + "]");
        }
    }
}
