package dev.marregui.comando.cdb.store;

import java.lang.foreign.MemorySegment;

/**
 * The smallest and largest value of each block of {@link #BLOCK_ROWS} rows of one 8-byte column of a {@link Version},
 * NULLs left out, so a scan's pre-filter skips a block no row of which can match and keeps a block every row of which
 * does without reading it. Values are compared as keys: a LONG or TIMESTAMP is its own key; a DOUBLE's key is
 * {@link #doubleKey}, ordered as {@link Double#compare} orders the values. A block holding a NULL (a LONG's
 * {@link Long#MIN_VALUE}, a DOUBLE's NaN) is never skipped. Built once from the mapped column, immutable after.
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

    private final long[] min;
    private final long[] max;
    private final boolean[] hasNull;

    private ZoneMap(final long[] min, final long[] max, final boolean[] hasNull) {
        this.min = min;
        this.max = max;
        this.hasNull = hasNull;
    }

    /** The zone map of the first {@code rows} rows of {@code column}, a DOUBLE column when {@code doubles}. */
    static ZoneMap of(final MemorySegment column, final long rows, final boolean doubles) {
        final int blocks = (int) ((rows + BLOCK_ROWS - 1) >>> BLOCK_SHIFT);
        final long[] min = new long[blocks];
        final long[] max = new long[blocks];
        final boolean[] hasNull = new boolean[blocks];
        for (int b = 0; b < blocks; b++) {
            final long end = Math.min(rows, (long) (b + 1) << BLOCK_SHIFT);
            long low = Long.MAX_VALUE;
            long high = Long.MIN_VALUE;
            boolean nulls = false;
            for (long row = (long) b << BLOCK_SHIFT; row < end; row++) {
                final long bits = column.get(Version.LONG_LE, row * Long.BYTES);
                if (doubles ? isNaN(bits) : bits == Long.MIN_VALUE) {
                    nulls = true;
                } else {
                    final long key = doubles ? doubleKey(bits) : bits;
                    low = Math.min(low, key);
                    high = Math.max(high, key);
                }
            }
            min[b] = low;
            max[b] = high;
            hasNull[b] = nulls;
        }
        return new ZoneMap(min, max, hasNull);
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

    /**
     * What a pre-filter keeping the keys of {@code [low, high]} ({@code low > high}: none), or every key outside it
     * when {@code outside}, can conclude about {@code block}.
     */
    Verdict verdict(final int block, final long low, final long high, final boolean outside) {
        final long lo = min[block];
        final long hi = max[block];
        if (lo > hi) {
            return Verdict.ALL;
        }
        final boolean noneInside = low > high || hi < low || lo > high;
        final boolean allInside = !noneInside && low <= lo && hi <= high;
        if (outside ? noneInside : allInside) {
            return Verdict.ALL;
        }
        if ((outside ? allInside : noneInside) && !hasNull[block]) {
            return Verdict.NONE;
        }
        return Verdict.SOME;
    }
}
