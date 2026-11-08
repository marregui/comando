package dev.marregui.comando.core.state;

/**
 * What a session asks of the user now, the one answer the Sessions tab, the work list and the panels' tabs go by
 * (record 136). In the order the Sessions tab groups them: the first that applies.
 */
public enum Attention {
    /** A request of this session waits for an answer: an approval, a question, a plan. */
    NEEDS_YOU,
    /** A turn runs, background work has not ended, or that work has just returned and its reading turn is to come. */
    WORKING,
    /** The session finished work the user has not looked at. */
    TO_READ,
    /** Nothing goes on and nothing waits. */
    IDLE;

    /**
     * How long after background work returns its reading turn may take to make its first model call before the work
     * counts as finished: a large context with a cold cache takes tens of seconds; a return that starts no turn is
     * rare. Unmeasured: the time from a task's end to its reading turn's first call, read from the metrics log, would
     * settle it.
     */
    public static final long REPORT_GRACE_MILLIS = 60_000;

    /** What {@code x} asks of the user in {@code s} at {@code nowMillis}. */
    public static Attention of(final AppState s, final SessionState x, final long nowMillis) {
        if (s.pendingApprovals(x.name()) > 0) {
            return NEEDS_YOU;
        }
        if (x.isBusy() || x.isWaiting() || returning(x, nowMillis)) {
            return WORKING;
        }
        return x.spec().unread() ? TO_READ : IDLE;
    }

    /**
     * Whether {@code x} has an outcome to read at {@code nowMillis}. Work that has just returned has none yet: the turn
     * that reads it is the outcome, unless no such turn starts within {@link #REPORT_GRACE_MILLIS}.
     */
    public static boolean unread(final SessionState x, final long nowMillis) {
        return x.spec().unread() && !returning(x, nowMillis);
    }

    /** A failed hand-off turn is its own outcome: nothing is awaited from the work it left behind. */
    private static boolean returning(final SessionState x, final long nowMillis) {
        return x.isReturning(nowMillis, REPORT_GRACE_MILLIS) && x.lastTurnError().isEmpty();
    }
}
