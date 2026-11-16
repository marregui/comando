package dev.marregui.comando.cdb.exec.agg;

import java.util.Arrays;

import dev.marregui.comando.cdb.exec.SymbolDictionary;
import dev.marregui.comando.cdb.exec.Vector;

/**
 * {@code count_distinct} of SYMBOL or VARCHAR: one open-addressing table of (group, text) pairs, NULLs skipped. A
 * SYMBOL value whose vector carries its key in the dictionary this table saw first is compared by key, without
 * decoding; any other value by its text.
 */
final class TextCountDistinctAccumulator implements Accumulator {

    private static final int INITIAL_SLOTS = 64;
    // the end of a chain of slots
    private static final int NO_SLOT = -1;

    // per group its first slot, per slot the next slot of its group: the pairs of one group without a pass over the
    // whole table, built when this table is first merged from, dropped when it gains a pair
    private int[] chainHeads;
    private int[] chainNext;
    private long[] counts = new long[16];
    // the dictionary of the first vector seen that carries keys; the keys below are of it
    private SymbolDictionary dictionary;
    // group + 1 per slot, 0 for an empty slot
    private int[] groups = new int[INITIAL_SLOTS];
    private int[] hashes = new int[INITIAL_SLOTS];
    // per slot the key of its text in dictionary, Vector.NO_KEY when not known
    private int[] keys = new int[INITIAL_SLOTS];
    private int size;
    // per slot its text, a String copied out of the page, so it outlives the page
    private String[] strings = new String[INITIAL_SLOTS];

    @Override
    public void add(final int group, final Vector value, final int row) {
        if (!value.isNull(row)) {
            insert(group, value, row, keyed(value) ? value.symbolKey(row) : Vector.NO_KEY);
        }
    }

    @Override
    public void addAll(final int[] groupOfRow, final Vector value, final int rows) {
        final boolean keyed = keyed(value);
        for (int i = 0; i < rows; i++) {
            if (!value.isNull(i)) {
                insert(groupOfRow[i], value, i, keyed ? value.symbolKey(i) : Vector.NO_KEY);
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
        if (dictionary == null) {
            dictionary = other.dictionary;
        }
        final boolean sameKeys = other.dictionary != null && other.dictionary == dictionary;
        other.chain();
        for (int i = other.chainHeads[fromGroup]; i != NO_SLOT; i = other.chainNext[i]) {
            insert(group, other.strings[i], sameKeys ? other.keys[i] : Vector.NO_KEY);
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
        final int[] next = new int[strings.length];
        for (int i = 0; i < strings.length; i++) {
            if (groups[i] != 0) {
                next[i] = heads[groups[i] - 1];
                heads[groups[i] - 1] = i;
            }
        }
        chainHeads = heads;
        chainNext = next;
    }

    // the non-NULL value row of value, whose key in dictionary is key or Vector.NO_KEY
    private void insert(final int group, final Vector value, final int row, final int key) {
        final int hash = mix(group, value.textHash(row));
        final int mask = strings.length - 1;
        int i = hash & mask;
        while (groups[i] != 0) {
            if (groups[i] == group + 1 && hashes[i] == hash) {
                if (key != Vector.NO_KEY && keys[i] != Vector.NO_KEY) {
                    if (keys[i] == key) {
                        return;
                    }
                } else if (value.textEquals(row, strings[i])) {
                    if (keys[i] == Vector.NO_KEY) {
                        keys[i] = key;
                    }
                    return;
                }
            }
            i = (i + 1) & mask;
        }
        inserted(i, group, hash, value.textAt(row), key);
    }

    // a pair of another table: its text and its key in dictionary or Vector.NO_KEY
    private void insert(final int group, final String text, final int key) {
        final int hash = mix(group, text.hashCode());
        final int mask = strings.length - 1;
        int i = hash & mask;
        while (groups[i] != 0) {
            if (groups[i] == group + 1 && hashes[i] == hash) {
                if (key != Vector.NO_KEY && keys[i] != Vector.NO_KEY) {
                    if (keys[i] == key) {
                        return;
                    }
                } else if (strings[i].equals(text)) {
                    if (keys[i] == Vector.NO_KEY) {
                        keys[i] = key;
                    }
                    return;
                }
            }
            i = (i + 1) & mask;
        }
        inserted(i, group, hash, text, key);
    }

    // the slot of a new pair, its group, hash, text and key set, the table grown when due
    private void inserted(final int slot, final int group, final int hash, final String text, final int key) {
        groups[slot] = group + 1;
        hashes[slot] = hash;
        keys[slot] = key;
        strings[slot] = text;
        counts[group]++;
        chainHeads = null;
        chainNext = null;
        // a load factor of at most one half keeps probe runs short
        if (++size > strings.length >>> 1) {
            rehash();
        }
    }

    // true when the keys value carries are of dictionary, which becomes value's when this table has seen none
    private boolean keyed(final Vector value) {
        final SymbolDictionary carried = value.symbolDictionary();
        if (carried == null) {
            return false;
        }
        if (dictionary == null) {
            dictionary = carried;
        }
        return carried == dictionary;
    }

    private void rehash() {
        final int[] oldGroups = groups;
        final int[] oldHashes = hashes;
        final int[] oldKeys = keys;
        final String[] oldStrings = strings;
        final int capacity = Values.grownCapacity(oldStrings.length, oldStrings.length * 2);
        groups = new int[capacity];
        hashes = new int[capacity];
        keys = new int[capacity];
        strings = new String[capacity];
        final int mask = capacity - 1;
        for (int j = 0; j < oldStrings.length; j++) {
            if (oldGroups[j] != 0) {
                int i = oldHashes[j] & mask;
                while (groups[i] != 0) {
                    i = (i + 1) & mask;
                }
                groups[i] = oldGroups[j];
                hashes[i] = oldHashes[j];
                keys[i] = oldKeys[j];
                strings[i] = oldStrings[j];
            }
        }
    }
}
