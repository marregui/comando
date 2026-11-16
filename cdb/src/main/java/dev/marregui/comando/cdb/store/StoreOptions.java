package dev.marregui.comando.cdb.store;

/**
 * How a store makes its commits durable, set when it opens and not persisted.
 *
 * @param logForceMillis 0: a logged commit is answered after its log record is forced, as before; N &gt; 0: it is
 *     answered after its record is written to the page cache, and each table log with records written since its last
 *     force is forced at most N ms after the first of them, so an OS crash or a power cut loses at most the commits
 *     answered in the last N ms plus one round of forces; a process crash loses none
 */
public record StoreOptions(long logForceMillis) {

    /** The force per commit. */
    public static final StoreOptions DEFAULT = new StoreOptions(0L);

    /** Validates the interval. */
    public StoreOptions {
        if (logForceMillis < 0L) {
            throw new IllegalArgumentException("refuse negative log force interval [logForceMillis=" + logForceMillis
                    + ']');
        }
    }

    /** True when commits are answered before their force. */
    public boolean isTimed() {
        return logForceMillis > 0L;
    }
}
