package dev.marregui.comando.core.state;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * When the session says its work ends: the step it does now, and the goal the whole task ends at, each with the time it
 * expects to be done by, and the timebox the user gave the task. The session sets it with the {@code session_eta}
 * action; a part with no time is not shown. Beside it, the {@link Run} the session bar shows at all times: the task
 * the session declared, else the turn under way, else the last one; and the lengths of the session's last turns, which
 * give a turn the session gave no estimate its default deadline. Held in memory only.
 *
 * @param step             the step under way, empty when none
 * @param stepAtMillis     when the step is expected to end, 0 when it has no estimate
 * @param goal             the task's end state, empty when none
 * @param goalAtMillis     when the goal is expected to be reached, 0 when it has no estimate
 * @param boxMinutes       the timebox the user gave the task, 0 when none
 * @param boxGivenAtMillis when the user gave it, 0 when there is no timebox
 * @param setAtMillis      when the session last set the estimate, 0 when it never did
 * @param run              the run the session bar shows; {@link Run#NONE} before the first
 * @param turnMillis       the lengths of the session's last turns, oldest first, at most {@link #TURN_HISTORY}
 */
public record SessionEta(String step, long stepAtMillis, String goal, long goalAtMillis, int boxMinutes,
                         long boxGivenAtMillis, long setAtMillis, Run run, List<Long> turnMillis) {

    /** No estimate. */
    public static final SessionEta NONE = new SessionEta("", 0L, "", 0L, 0, 0L, 0L);

    /** How many of the last turns' lengths give the default deadline; set 2026-11-15 with the always-on ETA. */
    public static final int TURN_HISTORY = 20;

    /** A turn's default deadline while the session has no turn behind it; set 2026-11-15 with the always-on ETA. */
    public static final long DEFAULT_TURN_MILLIS = 5L * 60_000L;

    /** The shortest default deadline, so a run of quick turns does not make the next one late at once. */
    private static final long MIN_DEFAULT_TURN_MILLIS = 60_000L;

    public SessionEta {
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(goal, "goal");
        // an intent log written before the run existed reads both as absent, null
        run = run == null ? Run.NONE : run;
        turnMillis = turnMillis == null ? List.of() : List.copyOf(turnMillis);
        if (stepAtMillis < 0L || goalAtMillis < 0L || boxMinutes < 0 || boxGivenAtMillis < 0L || setAtMillis < 0L) {
            throw new IllegalArgumentException("an ETA is negative [stepAtMillis=" + stepAtMillis + ", goalAtMillis="
                    + goalAtMillis + ", boxMinutes=" + boxMinutes + ", boxGivenAtMillis=" + boxGivenAtMillis
                    + ", setAtMillis=" + setAtMillis + "]");
        }
        if (turnMillis.size() > TURN_HISTORY) {
            throw new IllegalArgumentException("too many turn lengths [size=" + turnMillis.size() + ", max=" + TURN_HISTORY + "]");
        }
    }

    /** The estimate alone, with no run and no turn behind it. */
    public SessionEta(final String step, final long stepAtMillis, final String goal, final long goalAtMillis,
                      final int boxMinutes, final long boxGivenAtMillis, final long setAtMillis) {
        this(step, stepAtMillis, goal, goalAtMillis, boxMinutes, boxGivenAtMillis, setAtMillis, Run.NONE, List.of());
    }

    public SessionEta withRun(final Run newRun) {
        return new SessionEta(step, stepAtMillis, goal, goalAtMillis, boxMinutes, boxGivenAtMillis, setAtMillis, newRun, turnMillis);
    }

    /** This estimate with a turn of {@code millis} added to the history, the oldest dropped past {@link #TURN_HISTORY}. */
    public SessionEta withTurn(final long millis) {
        final List<Long> next = new ArrayList<>(turnMillis);
        next.add(Math.max(0L, millis));
        while (next.size() > TURN_HISTORY) {
            next.removeFirst();
        }
        return new SessionEta(step, stepAtMillis, goal, goalAtMillis, boxMinutes, boxGivenAtMillis, setAtMillis, run, next);
    }

    /**
     * A turn's default length: the median of the last turns (the upper one of an even count), at least a minute; else
     * {@link #DEFAULT_TURN_MILLIS}.
     */
    public long defaultTurnMillis() {
        if (turnMillis.isEmpty()) {
            return DEFAULT_TURN_MILLIS;
        }
        final List<Long> sorted = turnMillis.stream().sorted().toList();
        return Math.max(MIN_DEFAULT_TURN_MILLIS, sorted.get(sorted.size() / 2));
    }

    /** Whether the session declared a task: it gave a goal with a time, or a timebox. */
    public boolean hasTask() {
        return goalAtMillis > 0L || boxMinutes > 0;
    }

    /**
     * What the session bar shows: one run of work, from when it started to its deadline, with how far the session says
     * it is.
     *
     * @param startedMillis   when it started: the user's go (the timebox's time) for a task, the prompt's send for a turn; 0 for {@link #NONE}
     * @param deadlineMillis  when it is due: the timebox's end, else the goal's ETA, else the turn's default
     * @param source          where the deadline came from
     * @param percent         how far the session says it is, 0 to 100; -1 before it says
     * @param percentAtMillis when it said so, 0 before it did
     * @param endedMillis     when it ended, 0 while it runs
     */
    public record Run(long startedMillis, long deadlineMillis, Source source, int percent, long percentAtMillis, long endedMillis) {

        /** No run yet. */
        public static final Run NONE = new Run(0L, 0L, Source.DEFAULT, -1, 0L, 0L);

        public Run {
            Objects.requireNonNull(source, "source");
            if (startedMillis < 0L || deadlineMillis < 0L || percentAtMillis < 0L || endedMillis < 0L || percent < -1 || percent > 100) {
                throw new IllegalArgumentException("a run is out of range [startedMillis=" + startedMillis + ", deadlineMillis="
                        + deadlineMillis + ", percent=" + percent + ", percentAtMillis=" + percentAtMillis + ", endedMillis="
                        + endedMillis + "]");
            }
        }

        public boolean exists() {
            return startedMillis > 0L;
        }

        public boolean running() {
            return startedMillis > 0L && endedMillis == 0L;
        }

        /** This run with {@code newPercent}, said at {@code atMillis}. */
        public Run withPercent(final int newPercent, final long atMillis) {
            return new Run(startedMillis, deadlineMillis, source, newPercent, atMillis, endedMillis);
        }

        public Run endedAt(final long atMillis) {
            return new Run(startedMillis, deadlineMillis, source, percent, percentAtMillis, atMillis);
        }

        /** Where a run's deadline came from. */
        public enum Source {
            /** The timebox the user gave. */
            BOX,
            /** The session's estimate of the goal. */
            ESTIMATE,
            /** comando's default for a turn the session gave no estimate: the median of its last turns. */
            DEFAULT
        }
    }
}
