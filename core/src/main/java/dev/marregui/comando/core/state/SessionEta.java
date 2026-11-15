package dev.marregui.comando.core.state;

import java.util.Objects;

/**
 * When the session says its work ends: the step it does now, and the goal the whole task ends at, each with the time it
 * expects to be done by, and the timebox the user gave the task. The session sets it with the {@code session_eta}
 * action; a part with no time is not shown.
 *
 * @param step             the step under way, empty when none
 * @param stepAtMillis     when the step is expected to end, 0 when it has no estimate
 * @param goal             the task's end state, empty when none
 * @param goalAtMillis     when the goal is expected to be reached, 0 when it has no estimate
 * @param boxMinutes       the timebox the user gave the task, 0 when none
 * @param boxGivenAtMillis when the user gave it, 0 when there is no timebox
 * @param setAtMillis      when the session last set the estimate, 0 when it never did
 */
public record SessionEta(String step, long stepAtMillis, String goal, long goalAtMillis, int boxMinutes,
                         long boxGivenAtMillis, long setAtMillis) {

    /** No estimate. */
    public static final SessionEta NONE = new SessionEta("", 0L, "", 0L, 0, 0L, 0L);

    public SessionEta {
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(goal, "goal");
        if (stepAtMillis < 0L || goalAtMillis < 0L || boxMinutes < 0 || boxGivenAtMillis < 0L || setAtMillis < 0L) {
            throw new IllegalArgumentException("an ETA is negative [stepAtMillis=" + stepAtMillis + ", goalAtMillis="
                    + goalAtMillis + ", boxMinutes=" + boxMinutes + ", boxGivenAtMillis=" + boxGivenAtMillis
                    + ", setAtMillis=" + setAtMillis + "]");
        }
    }
}
