package dev.marregui.comando.cdb.store;

import java.util.Objects;

import dev.marregui.comando.cdb.CdbType;

/** Orders a partition's rows for a commit, and drops the rows a later one replaces. */
final class Dedup {

    private Dedup() {
    }

    /**
     * The indexes of the rows of {@code block} to keep, in designated-timestamp order, stable (rows of one timestamp
     * in the order they were written); with {@code keyColumns}, of the rows that share the timestamp and every key
     * column's value only the last written is kept. {@code keyColumns} empty: a sort and nothing dropped.
     */
    static int[] order(final RowBlock block, final int timestampColumn, final int[] keyColumns) {
        final int n = block.size;
        if (timestampColumn < 0) {
            final int[] identity = new int[n];
            for (int i = 0; i < n; i++) {
                identity[i] = i;
            }
            return identity;
        }
        final long[] ts = block.longs[timestampColumn];
        final int[] sorted = stableOrder(ts, n);
        if (keyColumns.length == 0) {
            return sorted;
        }
        final boolean[] dropped = new boolean[n];
        int dropCount = 0;
        int runStart = 0;
        while (runStart < n) {
            int runEnd = runStart + 1;
            while (runEnd < n && ts[sorted[runEnd]] == ts[sorted[runStart]]) {
                runEnd++;
            }
            if (runEnd - runStart > 1) {
                dropCount += dropReplaced(block, keyColumns, sorted, runStart, runEnd, dropped);
            }
            runStart = runEnd;
        }
        if (dropCount == 0) {
            return sorted;
        }
        final int[] kept = new int[n - dropCount];
        int k = 0;
        for (int i = 0; i < n; i++) {
            if (!dropped[i]) {
                kept[k++] = sorted[i];
            }
        }
        return kept;
    }

    /**
     * The row indexes {@code 0..n-1} sorted by {@code keys}, stable: a merge sort, and none at all when the keys are
     * already in order, as appends mostly are.
     */
    static int[] stableOrder(final long[] keys, final int n) {
        final int[] idx = new int[n];
        boolean ordered = true;
        for (int i = 0; i < n; i++) {
            idx[i] = i;
            if (i > 0 && keys[i] < keys[i - 1]) {
                ordered = false;
            }
        }
        if (ordered) {
            return idx;
        }
        final int[] scratch = new int[n];
        int[] src = idx;
        int[] dst = scratch;
        for (int width = 1; width < n; width <<= 1) {
            for (int lo = 0; lo < n; lo += width << 1) {
                final int mid = Math.min(lo + width, n);
                final int hi = Math.min(lo + (width << 1), n);
                int a = lo;
                int b = mid;
                int out = lo;
                while (a < mid && b < hi) {
                    // ties take the left run first: the earlier written row stays first
                    dst[out++] = keys[src[b]] < keys[src[a]] ? src[b++] : src[a++];
                }
                while (a < mid) {
                    dst[out++] = src[a++];
                }
                while (b < hi) {
                    dst[out++] = src[b++];
                }
            }
            final int[] swap = src;
            src = dst;
            dst = swap;
        }
        return src;
    }

    /**
     * Marks in {@code dropped} (indexed by position in {@code sorted}) the rows of the run {@code [from, to)} of one
     * timestamp that a later row of the run with equal keys replaces; returns how many it marked. Rows of the run are
     * hashed on their keys and sorted by hash, so a run of k rows costs k log k, not k squared.
     */
    private static int dropReplaced(
            final RowBlock block,
            final int[] keyColumns,
            final int[] sorted,
            final int from,
            final int to,
            final boolean[] dropped
    ) {
        final int k = to - from;
        final long[] hashes = new long[k];
        for (int i = 0; i < k; i++) {
            hashes[i] = hash(block, keyColumns, sorted[from + i]);
        }
        // by hash, and within one hash in written order, as the run is
        final int[] byHash = stableOrder(hashes, k);
        int marked = 0;
        int groupStart = 0;
        while (groupStart < k) {
            int groupEnd = groupStart + 1;
            while (groupEnd < k && hashes[byHash[groupEnd]] == hashes[byHash[groupStart]]) {
                groupEnd++;
            }
            for (int i = groupStart; i < groupEnd; i++) {
                final int row = sorted[from + byHash[i]];
                for (int j = i + 1; j < groupEnd; j++) {
                    if (sameKeys(block, keyColumns, row, sorted[from + byHash[j]])) {
                        dropped[from + byHash[i]] = true;
                        marked++;
                        break;
                    }
                }
            }
            groupStart = groupEnd;
        }
        return marked;
    }

    private static long hash(final RowBlock block, final int[] keyColumns, final int row) {
        long h = 1L;
        for (final int c : keyColumns) {
            final long v = switch (block.types[c]) {
                case LONG, TIMESTAMP -> block.longs[c][row];
                case DOUBLE -> Double.doubleToLongBits(block.doubles[c][row]);
                case BOOLEAN -> block.booleans[c][row] ? 1L : 0L;
                case SYMBOL, VARCHAR -> Objects.hashCode(block.texts[c][row]);
            };
            h = h * 0x9E3779B97F4A7C15L + v;
        }
        return h ^ (h >>> 31);
    }

    private static boolean sameKeys(final RowBlock block, final int[] keyColumns, final int a, final int b) {
        for (final int c : keyColumns) {
            final CdbType type = block.types[c];
            final boolean same = switch (type) {
                case LONG, TIMESTAMP -> block.longs[c][a] == block.longs[c][b];
                case DOUBLE -> Double.doubleToLongBits(block.doubles[c][a])
                        == Double.doubleToLongBits(block.doubles[c][b]);
                case BOOLEAN -> block.booleans[c][a] == block.booleans[c][b];
                case SYMBOL, VARCHAR -> Objects.equals(block.texts[c][a], block.texts[c][b]);
            };
            if (!same) {
                return false;
            }
        }
        return true;
    }
}
