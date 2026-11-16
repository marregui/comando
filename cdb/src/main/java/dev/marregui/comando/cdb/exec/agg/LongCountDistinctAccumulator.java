package dev.marregui.comando.cdb.exec.agg;

import java.util.Arrays;

import dev.marregui.comando.cdb.exec.Vector;

/**
 * {@code count_distinct} of LONG, TIMESTAMP, DOUBLE or BOOLEAN: one open-addressing table of (group, value bits) pairs
 * for all groups, NULLs skipped. DOUBLE values compare by their bits, BOOLEAN as 0 and 1.
 */
final class LongCountDistinctAccumulator implements Accumulator {

    // a slot whose group is EMPTY holds no pair; groups are indexes, never negative
    private static final int EMPTY = -1;
    private static final int INITIAL_SLOTS = 64;
    // the end of a chain of slots
    private static final int NO_SLOT = -1;

    // per group its first slot, per slot the next slot of its group: the pairs of one group without a pass over the
    // whole table, built when this table is first merged from, dropped when it gains a pair
    private int[] chainHeads;
    private int[] chainNext;
    private long[] counts = new long[16];
    private int[] groups = emptySlots(INITIAL_SLOTS);
    private long[] keys = new long[INITIAL_SLOTS];
    private int size;

    @Override
    public void add(final int group, final Vector value, final int row) {
        switch (value.type) {
            case LONG, TIMESTAMP -> {
                if (value.longs[row] != Vector.NULL_LONG) {
                    insert(group, value.longs[row]);
                }
            }
            case DOUBLE -> {
                if (!Double.isNaN(value.doubles[row])) {
                    insert(group, Double.doubleToLongBits(value.doubles[row]));
                }
            }
            case BOOLEAN -> insert(group, value.booleans[row] ? 1 : 0);
            case SYMBOL, VARCHAR -> throw new IllegalStateException("cannot count text values as numbers");
        }
    }

    @Override
    public void addAll(final int[] groupOfRow, final Vector value, final int rows) {
        if (value.longs != null) {
            final long[] in = value.longs;
            for (int i = 0; i < rows; i++) {
                if (in[i] != Vector.NULL_LONG) {
                    insert(groupOfRow[i], in[i]);
                }
            }
        } else {
            for (int i = 0; i < rows; i++) {
                add(groupOfRow[i], value, i);
            }
        }
    }

    @Override
    public void ensure(final int groupCount) {
        if (groupCount > counts.length) {
            counts = Arrays.copyOf(counts, Values.grownCapacity(counts.length, groupCount));
        }
    }

    @Override
    public void merge(final int group, final Accumulator from, final int fromGroup) {
        assert from != this : "cannot merge an accumulator into itself";
        final LongCountDistinctAccumulator other = (LongCountDistinctAccumulator) from;
        if (other.counts[fromGroup] == 0) {
            return;
        }
        other.chain();
        for (int i = other.chainHeads[fromGroup]; i != NO_SLOT; i = other.chainNext[i]) {
            insert(group, other.keys[i]);
        }
    }

    @Override
    public void write(final int group, final Vector out, final int row) {
        Aggregates.writeLong(counts[group], out, row);
    }

    private static int[] emptySlots(final int capacity) {
        final int[] slots = new int[capacity];
        Arrays.fill(slots, EMPTY);
        return slots;
    }

    private static int slot(final int group, final long key, final int mask) {
        long h = key * 0x9E3779B97F4A7C15L + group;
        h ^= h >>> 29;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 32;
        return (int) h & mask;
    }

    private void chain() {
        if (chainHeads != null) {
            return;
        }
        final int[] heads = new int[counts.length];
        Arrays.fill(heads, NO_SLOT);
        final int[] next = new int[keys.length];
        for (int i = 0; i < keys.length; i++) {
            if (groups[i] != EMPTY) {
                next[i] = heads[groups[i]];
                heads[groups[i]] = i;
            }
        }
        chainHeads = heads;
        chainNext = next;
    }

    private void insert(final int group, final long key) {
        final int mask = keys.length - 1;
        int i = slot(group, key, mask);
        while (groups[i] != EMPTY) {
            if (groups[i] == group && keys[i] == key) {
                return;
            }
            i = (i + 1) & mask;
        }
        groups[i] = group;
        keys[i] = key;
        counts[group]++;
        chainHeads = null;
        chainNext = null;
        // a load factor of at most one half keeps probe runs short
        if (++size > keys.length >>> 1) {
            rehash();
        }
    }

    private void rehash() {
        final int[] oldGroups = groups;
        final long[] oldKeys = keys;
        final int capacity = Values.grownCapacity(oldKeys.length, oldKeys.length * 2);
        groups = emptySlots(capacity);
        keys = new long[capacity];
        final int mask = capacity - 1;
        for (int j = 0; j < oldKeys.length; j++) {
            if (oldGroups[j] != EMPTY) {
                int i = slot(oldGroups[j], oldKeys[j], mask);
                while (groups[i] != EMPTY) {
                    i = (i + 1) & mask;
                }
                groups[i] = oldGroups[j];
                keys[i] = oldKeys[j];
            }
        }
    }
}
