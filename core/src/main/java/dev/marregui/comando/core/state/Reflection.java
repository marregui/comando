package dev.marregui.comando.core.state;

/**
 * Whether a session is due its checkpoint (a {@code /reflect}): once a turn ends with the context filled to
 * {@link #THRESHOLD} of the window, the session is due, and stays due, through a compaction too, until its checkpoint
 * command is sent. After a checkpoint the context has to drop below the threshold (a compaction) and cross it again
 * before the session is due once more, so a checkpoint near the threshold is not followed by another at the next turn.
 *
 * @param due   the context crossed the threshold and no checkpoint was sent since
 * @param armed the context has been below the threshold since the last checkpoint: the next crossing makes it due
 */
public record Reflection(boolean due, boolean armed) {

    /** The share of the context window at which a session is due. */
    public static final double THRESHOLD = 0.6;

    /** A session nobody has checkpointed: due at its first crossing. */
    public static final Reflection FRESH = new Reflection(false, true);

    /** A turn ended with {@code tokens} of context in a window of {@code window}; an unknown window changes nothing. */
    public Reflection afterTurn(final long tokens, final long window) {
        if (window <= 0L || tokens < 0L) {
            return this;
        }
        if (!above(tokens, window)) {
            return armed ? this : new Reflection(due, true);
        }
        return armed ? new Reflection(true, false) : this;
    }

    /** The checkpoint command was sent while the context held {@code tokens} of {@code window}. */
    public static Reflection checkpointed(final long tokens, final long window) {
        return new Reflection(false, window <= 0L || !above(tokens, window));
    }

    private static boolean above(final long tokens, final long window) {
        return tokens >= THRESHOLD * window;
    }
}
