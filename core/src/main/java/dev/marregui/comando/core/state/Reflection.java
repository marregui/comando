package dev.marregui.comando.core.state;

/**
 * Whether a session is due its checkpoint (a {@code /reflect}): once a turn ends with the context filled to the
 * checkpoint threshold, a share of the session's own context window (the window's setting, 40 % unless the user chose
 * another, record 129), the session is due, and stays due, through a compaction too, until its checkpoint command is
 * sent. The automatic checkpoint runs at the same crossing. After a checkpoint a turn has to end below the threshold
 * (after a compaction, say) and the context cross it again before the session is due once more, so a checkpoint near
 * the threshold is not followed by another at the next turn. The window's threshold is between {@link #MIN_PERCENT} and
 * 100 % ({@link #clamp}: Miguel's decision D3 of the first-release review, no off); a threshold of 0, which only a
 * test's reducer passes, makes no session due.
 *
 * @param due   the context crossed the threshold and no checkpoint was sent since
 * @param armed the context has been below the threshold since the last checkpoint: the next crossing makes it due
 */
public record Reflection(boolean due, boolean armed) {

    /** The checkpoint threshold until the user chooses another: this share, in percent, of the context window. */
    public static final int DEFAULT_PERCENT = 40;

    /** The lowest threshold the window takes, in percent: a session cannot be left without its checkpoint. */
    public static final int MIN_PERCENT = 10;

    /** A session nobody has checkpointed: due at its first crossing. */
    public static final Reflection FRESH = new Reflection(false, true);

    /**
     * A turn ended with {@code tokens} of context in a window of {@code window}, the threshold at {@code percent} of
     * the window; an unknown window, or a threshold of 0 or below, changes nothing.
     */
    public Reflection afterTurn(final long tokens, final long window, final int percent) {
        if (window <= 0L || tokens < 0L || percent <= 0) {
            return this;
        }
        if (!above(tokens, window, percent)) {
            return armed ? this : new Reflection(due, true);
        }
        return armed ? new Reflection(true, false) : this;
    }

    /**
     * The checkpoint command was sent: not due, and not armed until a turn ends below the threshold, as the automatic
     * checkpoint is not, so both fall due at the same crossing.
     */
    public static Reflection checkpointed() {
        return new Reflection(false, false);
    }

    /** {@code percent} as the window's threshold: at least {@link #MIN_PERCENT}, at most 100. */
    public static int clamp(final int percent) {
        return Math.clamp(percent, MIN_PERCENT, 100);
    }

    /**
     * Whether {@code tokens} of context reach the threshold, {@code percent} of {@code window}; never for an unknown
     * window or a threshold of 0 or below.
     */
    public static boolean above(final long tokens, final long window, final int percent) {
        return window > 0L && percent > 0 && tokens * 100L >= (long) percent * window;
    }

    /** Whether the context is known to sit below the threshold; an unknown window or no threshold is neither. */
    public static boolean below(final long tokens, final long window, final int percent) {
        return window > 0L && percent > 0 && !above(tokens, window, percent);
    }
}
