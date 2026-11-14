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
 * <p>The session bar's Reflect blinks from {@link #BLINK_POINTS} below the threshold (record 156) until the checkpoint
 * command is sent, while due as well, and again only once a turn has ended below that band and the context has come
 * back into it, or the context has crossed the threshold again:
 * {@code quiet} keeps that, apart from {@code armed}, which is measured at the threshold itself.
 *
 * @param due   the context crossed the threshold and no checkpoint was sent since
 * @param armed the context has been below the threshold since the last checkpoint: the next crossing makes it due
 * @param quiet the checkpoint command was sent and no turn has ended below the blink band since
 */
public record Reflection(boolean due, boolean armed, boolean quiet) {

    /** The checkpoint threshold until the user chooses another: this share, in percent, of the context window. */
    public static final int DEFAULT_PERCENT = 40;

    /** The lowest threshold the window takes, in percent: a session cannot be left without its checkpoint. */
    public static final int MIN_PERCENT = 10;

    /** How many points of the context window below the threshold Reflect starts to blink. */
    public static final int BLINK_POINTS = 2;

    /** A session nobody has checkpointed: due at its first crossing. */
    public static final Reflection FRESH = new Reflection(false, true);

    /** {@code due} and {@code armed}, not quiet: no checkpoint command sent since the context was below the band. */
    public Reflection(final boolean due, final boolean armed) {
        this(due, armed, false);
    }

    /**
     * A turn ended with {@code tokens} of context in a window of {@code window}, the threshold at {@code percent} of
     * the window; an unknown window, or a threshold of 0 or below, changes nothing.
     */
    public Reflection afterTurn(final long tokens, final long window, final int percent) {
        if (window <= 0L || tokens < 0L || percent <= 0) {
            return this;
        }
        // below the blink band the command sent no longer quiets it: the next rise into the band blinks again
        final boolean stillQuiet = quiet && !below(tokens, window, percent - BLINK_POINTS);
        if (!above(tokens, window, percent)) {
            return new Reflection(due, true, stillQuiet);
        }
        return armed ? new Reflection(true, false, stillQuiet) : new Reflection(due, armed, stillQuiet);
    }

    /**
     * The checkpoint command was sent: not due, and not armed until a turn ends below the threshold, as the automatic
     * checkpoint is not, so both fall due at the same crossing.
     */
    public static Reflection checkpointed() {
        return new Reflection(false, false, true);
    }

    /**
     * Whether Reflect blinks with {@code tokens} of context in a window of {@code window}, the threshold at
     * {@code percent}: while the session is due, a compaction since included, or while the context reaches
     * {@link #BLINK_POINTS} below the threshold and the command has not been sent since it last came into that band.
     */
    public boolean blinks(final long tokens, final long window, final int percent) {
        return due || !quiet && above(tokens, window, percent - BLINK_POINTS);
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
