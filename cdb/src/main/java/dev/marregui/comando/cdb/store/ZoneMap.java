package dev.marregui.comando.cdb.store;

import java.lang.foreign.MemorySegment;
import java.util.concurrent.atomic.AtomicReferenceArray;

import org.jetbrains.annotations.TestOnly;

/**
 * The smallest and largest value of each block of {@link #BLOCK_ROWS} rows of one 8-byte column of a {@link Version},
 * NULLs left out, so a scan's pre-filter skips a block no row of which can match and keeps a block every row of which
 * does without reading it. Values are compared as keys: a LONG or TIMESTAMP is its own key; a DOUBLE's key is
 * {@link #doubleKey}, ordered as {@link Double#compare} orders the values. A block holding a NULL (a LONG's
 * {@link Long#MIN_VALUE}, a DOUBLE's NaN) is never skipped.
 * <p>
 * A block's bounds are read from the mapped column the first time a scan meets the block, so a scan of a few blocks
 * reads only those. One map serves every reader thread of its version: two threads that race on a block read equal
 * bounds, and either publication stands. A version that appends in place to another starts from the other's map:
 * the whole blocks below the other's row count hold the same rows, and only the rest are read again.
 */
final class ZoneMap {

    /** How far a pre-filter can trust a block's bounds for one comparison. */
    enum Verdict {
        /** No row of the block can hold, and none is NULL: the block is skipped. */
        NONE,
        /** Some rows may hold: each row is read. */
        SOME,
        /** Every row holds or is NULL: the block is kept without a read. */
        ALL
    }

    /** Rows per block, a power of two; set by the design (4,096), its scan gain not measured on its own. */
    static final int BLOCK_SHIFT = 12;
    static final int BLOCK_ROWS = 1 << BLOCK_SHIFT;

    private static final long EXPONENT_BITS = 0x7FF0_0000_0000_0000L;

    private final long rows;
    private final boolean doubles;
    // per block, null until the first scan that meets it; written by any reader thread, read by every one
    private final AtomicReferenceArray<Zone> zones;
    // the blocks taken built from the version this one appends to
    private final int carried;

    private ZoneMap(final long rows, final boolean doubles, final AtomicReferenceArray<Zone> zones,
            final int carried) {
        this.rows = rows;
        this.doubles = doubles;
        this.zones = zones;
        this.carried = carried;
    }

    /** An empty zone map of the first {@code rows} rows of a column, a DOUBLE column when {@code doubles}. */
    static ZoneMap of(final long rows, final boolean doubles) {
        return new ZoneMap(rows, doubles, new AtomicReferenceArray<>(blocks(rows)), 0);
    }

    /**
     * The zone map of the first {@code rows} rows of a column whose first {@code old.rows} rows are {@code old}'s:
     * each whole block of {@code old} it has built is taken as it is. {@code rows} is at least {@code old.rows}.
     */
    static ZoneMap carrying(final ZoneMap old, final long rows) {
        assert rows >= old.rows : "rows=" + rows + ", old=" + old.rows;
        final AtomicReferenceArray<Zone> zones = new AtomicReferenceArray<>(blocks(rows));
        int carried = 0;
        for (int b = 0; b < (int) (old.rows >>> BLOCK_SHIFT); b++) {
            final Zone zone = old.zones.get(b);
            if (zone != null) {
                zones.set(b, zone);
                carried++;
            }
        }
        return new ZoneMap(rows, old.doubles, zones, carried);
    }

    /**
     * The key of a DOUBLE given its raw bits: keys order as {@link Double#compare} orders the values that are not NaN,
     * {@code -0.0} below {@code 0.0}.
     */
    static long doubleKey(final long bits) {
        return bits ^ ((bits >> 63) & Long.MAX_VALUE);
    }

    /** True when the raw bits of a DOUBLE are a NaN, of any sign or payload. */
    static boolean isNaN(final long bits) {
        return (bits & Long.MAX_VALUE) > EXPONENT_BITS;
    }

    /** The blocks this map read from its column, not counting those it carried. */
    @TestOnly
    int built() {
        int present = 0;
        for (int b = 0; b < zones.length(); b++) {
            present += zones.get(b) == null ? 0 : 1;
        }
        return present - carried;
    }

    /**
     * What a pre-filter keeping the keys of {@code [low, high]} ({@code low > high}: none), or every key outside it
     * when {@code outside}, can conclude about {@code block} of {@code column}, the mapped column of this map.
     */
    Verdict verdict(final MemorySegment column, final int block, final long low, final long high,
            final boolean outside) {
        Zone zone = zones.get(block);
        if (zone == null) {
            zone = read(column, block);
            zones.set(block, zone);
        }
        final long lo = zone.min();
        final long hi = zone.max();
        if (lo > hi) {
            return Verdict.ALL;
        }
        final boolean noneInside = low > high || hi < low || lo > high;
        final boolean allInside = !noneInside && low <= lo && hi <= high;
        if (outside ? noneInside : allInside) {
            return Verdict.ALL;
        }
        if ((outside ? allInside : noneInside) && !zone.hasNull()) {
            return Verdict.NONE;
        }
        return Verdict.SOME;
    }

    private Zone read(final MemorySegment column, final int block) {
        final long end = Math.min(rows, (long) (block + 1) << BLOCK_SHIFT);
        long low = Long.MAX_VALUE;
        long high = Long.MIN_VALUE;
        boolean nulls = false;
        for (long row = (long) block << BLOCK_SHIFT; row < end; row++) {
            final long bits = column.get(Version.LONG_LE, row * Long.BYTES);
            if (doubles ? isNaN(bits) : bits == Long.MIN_VALUE) {
                nulls = true;
            } else {
                final long key = doubles ? doubleKey(bits) : bits;
                low = Math.min(low, key);
                high = Math.max(high, key);
            }
        }
        return new Zone(low, high, nulls);
    }

    private static int blocks(final long rows) {
        return (int) ((rows + BLOCK_ROWS - 1) >>> BLOCK_SHIFT);
    }

    /** One block's smallest and largest key, {@code min > max} when every row is NULL, and whether one is. */
    private record Zone(long min, long max, boolean hasNull) {
    }
}
