package dev.marregui.comando.cdb.exec.agg;

import java.util.Arrays;

import dev.marregui.comando.cdb.exec.Vector;

/** {@code count_distinct} of SYMBOL or VARCHAR: one open-addressing table of (group, text) pairs, NULLs skipped. */
final class TextCountDistinctAccumulator implements Accumulator {

    private static final int INITIAL_SLOTS = 64;
    // the end of a chain of slots
    private static final int NO_SLOT = -1;

    // per group its first slot, per slot the next slot of its group: the pairs of one group without a pass over the
    // whole table, built when this table is first merged from, dropped when it gains a pair
    private int[] chainHeads;
    private int[] chainNext;
    private long[] counts = new long[16];
    // group + 1 per slot, 0 for an empty slot
    private int[] groups = new int[INITIAL_SLOTS];
    private int[] hashes = new int[INITIAL_SLOTS];
    private int size;
    private String[] texts = new String[INITIAL_SLOTS];

    @Override
    public void add(final int group, final Vector value, final int row) {
        final String text = value.texts[row];
        if (text != null) {
            insert(group, text);
        }
    }

    @Override
    public void addAll(final int[] groupOfRow, final Vector value, final int rows) {
        final String[] in = value.texts;
        for (int i = 0; i < rows; i++) {
            if (in[i] != null) {
                insert(groupOfRow[i], in[i]);
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
        final TextCountDistinctAccumulator other = (TextCountDistinctAccumulator) from;
        if (other.counts[fromGroup] == 0) {
            return;
        }
        other.chain();
        for (int i = other.chainHeads[fromGroup]; i != NO_SLOT; i = other.chainNext[i]) {
            insert(group, other.texts[i]);
        }
    }

    @Override
    public void write(final int group, final Vector out, final int row) {
        Aggregates.writeLong(counts[group], out, row);
    }

    private static int mix(final int group, final int hash) {
        int h = hash * 0x9E3779B9 + group;
        h ^= h >>> 16;
        h *= 0x85EBCA6B;
        h ^= h >>> 13;
        return h;
    }

    private void chain() {
        if (chainHeads != null) {
            return;
        }
        final int[] heads = new int[counts.length];
        Arrays.fill(heads, NO_SLOT);
        final int[] next = new int[texts.length];
        for (int i = 0; i < texts.length; i++) {
            if (groups[i] != 0) {
                next[i] = heads[groups[i] - 1];
                heads[groups[i] - 1] = i;
            }
        }
        chainHeads = heads;
        chainNext = next;
    }

    private void insert(final int group, final String text) {
        final int hash = mix(group, text.hashCode());
        final int mask = texts.length - 1;
        int i = hash & mask;
        while (groups[i] != 0) {
            if (groups[i] == group + 1 && hashes[i] == hash && texts[i].equals(text)) {
                return;
            }
            i = (i + 1) & mask;
        }
        groups[i] = group + 1;
        hashes[i] = hash;
        texts[i] = text;
        counts[group]++;
        chainHeads = null;
        chainNext = null;
        // a load factor of at most one half keeps probe runs short
        if (++size > texts.length >>> 1) {
            rehash();
        }
    }

    private void rehash() {
        final int[] oldGroups = groups;
        final int[] oldHashes = hashes;
        final String[] oldTexts = texts;
        final int capacity = Values.grownCapacity(oldTexts.length, oldTexts.length * 2);
        groups = new int[capacity];
        hashes = new int[capacity];
        texts = new String[capacity];
        final int mask = capacity - 1;
        for (int j = 0; j < oldTexts.length; j++) {
            if (oldGroups[j] != 0) {
                int i = oldHashes[j] & mask;
                while (groups[i] != 0) {
                    i = (i + 1) & mask;
                }
                groups[i] = oldGroups[j];
                hashes[i] = oldHashes[j];
                texts[i] = oldTexts[j];
            }
        }
    }
}
