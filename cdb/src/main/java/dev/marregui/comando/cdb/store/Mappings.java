package dev.marregui.comando.cdb.store;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.jetbrains.annotations.TestOnly;

/**
 * The memory mappings one {@link Store} holds, counted against the process's limit ({@code vm.max_map_count} on
 * Linux): a {@link Version} reports each file it maps and unmaps. One per store, shared by its tables' versions on
 * every reader thread. Past {@link #WARN_PERCENT} of the limit it logs one warning, again only after the count fell
 * under {@link #REARM_PERCENT}; without a readable limit (macOS) it counts and never warns.
 */
final class Mappings {

    private static final System.Logger LOG = System.getLogger(Mappings.class.getName());
    /** Warns once the count passes this share of the limit: Miguel's figure, room to act before a map fails. */
    static final int WARN_PERCENT = 81;
    /**
     * Re-arms the warning once the count falls under this share: 5 points of hysteresis, set with the warning and not
     * measured, so a count hovering at the threshold logs once, not on every map.
     */
    static final int REARM_PERCENT = 76;

    private final AtomicLong count = new AtomicLong();
    // set by the map that crossed WARN_PERCENT, cleared by the unmap that fell under REARM_PERCENT; a map and an unmap
    // racing at the edges may move one warning to the next map, never repeat it while the count stays above
    private final AtomicBoolean warned = new AtomicBoolean();
    // the process's limit, read once; 0 when unknown
    private final long limit;
    private final String store;
    private final System.Logger log;

    /** Counts without a limit, so never warns. */
    @TestOnly
    Mappings() {
        this.limit = 0L;
        this.store = "";
        this.log = LOG;
    }

    /** Counts against the limit {@code limitFile} holds ({@code /proc/sys/vm/max_map_count}), for {@code store}. */
    Mappings(final Path limitFile, final String store) {
        this(limitFile, store, LOG);
    }

    /** As {@link #Mappings(Path, String)}, warning through {@code log}. */
    Mappings(final Path limitFile, final String store, final System.Logger log) {
        this.limit = readLimit(limitFile);
        this.store = store;
        this.log = log;
    }

    /** Counts {@code segments} mappings made; the one that passes {@link #WARN_PERCENT} of the limit warns. */
    void mapped(final int segments) {
        final long now = count.addAndGet(segments);
        if (limit > 0L && now * 100L > limit * WARN_PERCENT && warned.compareAndSet(false, true)) {
            log.log(System.Logger.Level.WARNING, "mappings near the process limit [mappings=" + now + ", limit="
                    + limit + ", store=" + store + ']');
        }
    }

    /** Counts {@code segments} mappings released; under {@link #REARM_PERCENT} of the limit the warning re-arms. */
    void unmapped(final int segments) {
        final long now = count.addAndGet(-segments);
        if (limit > 0L && now * 100L < limit * REARM_PERCENT) {
            warned.set(false);
        }
    }

    /** The mappings held now. */
    long count() {
        return count.get();
    }

    /** The process's limit on mappings, 0 when it could not be read. */
    long limit() {
        return limit;
    }

    // absent, unreadable or not a positive number: 0, no limit
    private static long readLimit(final Path limitFile) {
        try {
            final long limit = Long.parseLong(StoreFiles.readText(limitFile).strip());
            return Math.max(0L, limit);
        } catch (final IOException | NumberFormatException _) {
            return 0L;
        }
    }
}
