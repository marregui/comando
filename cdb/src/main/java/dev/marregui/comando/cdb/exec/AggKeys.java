package dev.marregui.comando.cdb.exec;

import java.util.Arrays;
import java.util.List;

import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.exec.agg.Values;

/**
 * The distinct key tuples seen, numbered from 0 in first-seen order: an open-addressing table of group numbers over
 * one {@link Values} column per key. NULL equals NULL, so NULL keys form one group. With no key column every row is
 * group 0. A SYMBOL key whose vector carries keys in the dictionary the table saw first is compared by key, without
 * decoding; any other by its text. The hash of a text is its {@link String#hashCode} either way. With exactly one key
 * column, of type SYMBOL, a row carrying a key of that dictionary finds its group by the key, dense from 0, without
 * hashing; the first row of each key takes the hashed path, so a group is numbered, hashed and slotted alike on both.
 */
final class AggKeys {

    private static final int INITIAL_GROUPS = 16;
    private static final int INITIAL_SLOTS = 64;
    // the dense array's bound, set by the design: 16 KiB whatever the groups, else 8 slots (32 bytes) per group
    private static final int DENSE_KEYS = 4096;
    private static final int KEYS_PER_GROUP = 8;
    // the largest array size every JVM allocates
    private static final int MAX_ARRAY_SIZE = Integer.MAX_VALUE - 8;

    private final Values[] columns;
    // per key column the dictionary of the first vector seen that carries keys, null until one is seen
    private final SymbolDictionary[] dictionaries;
    // per SYMBOL key column, per group the key of its value in that column's dictionary or Vector.NO_KEY; null for
    // other columns
    private final int[][] symbolKeys;
    // with one SYMBOL key column, group + 1 per key of that column's dictionary, 0 for a key no row has carried yet;
    // null for any other key set
    private int[] groupByKey;
    private int[] hashes = new int[INITIAL_GROUPS];
    private int size;
    // group + 1 per slot, 0 for an empty slot
    private int[] slots = new int[INITIAL_SLOTS];

    AggKeys(final List<CdbType> types) {
        columns = new Values[types.size()];
        dictionaries = new SymbolDictionary[columns.length];
        symbolKeys = new int[columns.length][];
        for (int k = 0; k < columns.length; k++) {
            columns[k] = new Values(types.get(k));
            if (types.get(k) == CdbType.SYMBOL) {
                symbolKeys[k] = new int[INITIAL_GROUPS];
            }
        }
        groupByKey = columns.length == 1 && symbolKeys[0] != null ? new int[INITIAL_GROUPS] : null;
    }

    /** The group of row {@code row} of {@code keys}, one vector per key column, added when new. */
    int groupOf(final Vector[] keys, final int row) {
        if (groupByKey != null) {
            final int key = keyOf(0, keys[0], row);
            if (key != Vector.NO_KEY) {
                if (key < groupByKey.length && groupByKey[key] != 0) {
                    return groupByKey[key] - 1;
                }
                // the key's value may already have a group, from a row that held it as text or from a merge
                final int group = probed(keys, row);
                setGroupByKey(key, group);
                return group;
            }
        }
        return probed(keys, row);
    }

    /**
     * The group of the key tuple of group {@code otherGroup} of {@code other}, a table of the same key types, added
     * when new.
     */
    int groupOf(final AggKeys other, final int otherGroup) {
        // both tables hash a tuple alike, so the other's hash is this one's
        final int hash = other.hashes[otherGroup];
        final int mask = slots.length - 1;
        int i = hash & mask;
        while (slots[i] != 0) {
            final int group = slots[i] - 1;
            if (hashes[group] == hash && same(group, other, otherGroup)) {
                return group;
            }
            i = (i + 1) & mask;
        }
        final int group = size;
        for (int k = 0; k < columns.length; k++) {
            columns[k].ensure(group + 1);
            columns[k].set(group, other.columns[k], otherGroup);
            if (symbolKeys[k] != null) {
                if (dictionaries[k] == null) {
                    dictionaries[k] = other.dictionaries[k];
                }
                setSymbolKey(k, group, other.dictionaries[k] == dictionaries[k]
                        ? other.symbolKeys[k][otherGroup]
                        : Vector.NO_KEY);
            }
        }
        return added(i, hash);
    }

    /** True when rows {@code a} and {@code b} of {@code keys} hold the same key tuple, so they share a group. */
    boolean sameRows(final Vector[] keys, final int a, final int b) {
        for (int k = 0; k < columns.length; k++) {
            if (symbolKeys[k] != null) {
                // two keys of one vector are of one dictionary
                final int keyA = keys[k].symbolKey(a);
                final int keyB = keys[k].symbolKey(b);
                if (keyA != Vector.NO_KEY && keyB != Vector.NO_KEY) {
                    if (keyA != keyB) {
                        return false;
                    }
                    continue;
                }
            }
            if (!Values.equal(keys[k], a, b)) {
                return false;
            }
        }
        return true;
    }

    int size() {
        return size;
    }

    /** Writes key column {@code key} of {@code group} to {@code out[row]}, a vector of that column's type. */
    void write(final int group, final int key, final Vector out, final int row) {
        columns[key].write(group, out, row);
    }

    private static int mix(final int h) {
        int x = h * 0x9E3779B9;
        x ^= x >>> 16;
        x *= 0x85EBCA6B;
        x ^= x >>> 13;
        return x;
    }

    // numbers the next group, whose key columns are set: its hash kept, its slot taken, the table grown when due
    private int added(final int slot, final int hash) {
        final int group = size;
        if (group == hashes.length) {
            hashes = Arrays.copyOf(hashes, Math.max(hashes.length * 2, group + 1));
        }
        hashes[group] = hash;
        slots[slot] = group + 1;
        size++;
        // a load factor of at most one half keeps probe runs short
        if (size > slots.length >>> 1) {
            rehash();
        }
        return group;
    }

    private int hash(final Vector[] keys, final int row) {
        int h = 1;
        for (int k = 0; k < columns.length; k++) {
            h = 31 * h + Values.hash(keys[k], row);
        }
        return mix(h);
    }

    // the key of value row of vector in key column k's dictionary, Vector.NO_KEY when the vector carries none of it;
    // the first vector that carries keys gives the column its dictionary
    private int keyOf(final int k, final Vector vector, final int row) {
        final SymbolDictionary carried = vector.symbolDictionary();
        if (carried == null || vector.isNull(row)) {
            return Vector.NO_KEY;
        }
        if (dictionaries[k] == null) {
            dictionaries[k] = carried;
        }
        return carried == dictionaries[k] ? vector.symbolKey(row) : Vector.NO_KEY;
    }

    // the group of row row of keys found by its hash and probe, added when new
    private int probed(final Vector[] keys, final int row) {
        final int hash = hash(keys, row);
        final int mask = slots.length - 1;
        int i = hash & mask;
        while (slots[i] != 0) {
            final int group = slots[i] - 1;
            if (hashes[group] == hash && same(group, keys, row)) {
                return group;
            }
            i = (i + 1) & mask;
        }
        final int group = size;
        for (int k = 0; k < columns.length; k++) {
            columns[k].ensure(group + 1);
            columns[k].set(group, keys[k], row);
            if (symbolKeys[k] != null) {
                setSymbolKey(k, group, keyOf(k, keys[k], row));
            }
        }
        return added(i, hash);
    }

    private void rehash() {
        final int capacity = slots.length * 2;
        if (capacity <= 0) {
            throw new CdbException("cannot grow the key table [slots=" + slots.length + ']', -1);
        }
        slots = new int[capacity];
        final int mask = capacity - 1;
        for (int group = 0; group < size; group++) {
            int i = hashes[group] & mask;
            while (slots[i] != 0) {
                i = (i + 1) & mask;
            }
            slots[i] = group + 1;
        }
    }

    private boolean same(final int group, final AggKeys other, final int otherGroup) {
        for (int k = 0; k < columns.length; k++) {
            if (symbolKeys[k] != null && other.dictionaries[k] == dictionaries[k]) {
                final int key = other.symbolKeys[k][otherGroup];
                final int mine = symbolKeys[k][group];
                if (key != Vector.NO_KEY && mine != Vector.NO_KEY) {
                    if (key != mine) {
                        return false;
                    }
                    continue;
                }
            }
            if (!columns[k].sameAs(group, other.columns[k], otherGroup)) {
                return false;
            }
        }
        return true;
    }

    private boolean same(final int group, final Vector[] keys, final int row) {
        for (int k = 0; k < columns.length; k++) {
            if (symbolKeys[k] != null && keys[k].symbolDictionary() == dictionaries[k]) {
                final int key = keys[k].symbolKey(row);
                final int mine = symbolKeys[k][group];
                if (key != Vector.NO_KEY && mine != Vector.NO_KEY) {
                    if (key != mine) {
                        return false;
                    }
                    continue;
                }
            }
            if (!columns[k].sameAs(group, keys[k], row)) {
                return false;
            }
        }
        return true;
    }

    // the table doubles, or grows to the key when that is further
    // a key past the array's length is kept only while the array stays within DENSE_KEYS or KEYS_PER_GROUP slots per
    // group, so a few high keys of a large dictionary take the hashed path instead of an array sized by the key
    private void setGroupByKey(final int key, final int group) {
        if (key >= groupByKey.length && key >= Math.max(DENSE_KEYS, (long) KEYS_PER_GROUP * size)) {
            return;
        }
        if (key >= groupByKey.length) {
            if (key >= MAX_ARRAY_SIZE) {
                throw new CdbException("cannot grow the symbol key table [key=" + key + ']', -1);
            }
            final long capacity = Math.max((long) groupByKey.length << 1, key + 1L);
            groupByKey = Arrays.copyOf(groupByKey, (int) Math.min(capacity, MAX_ARRAY_SIZE));
        }
        groupByKey[key] = group + 1;
    }

    private void setSymbolKey(final int k, final int group, final int key) {
        if (group == symbolKeys[k].length) {
            symbolKeys[k] = Arrays.copyOf(symbolKeys[k], Math.max(symbolKeys[k].length * 2, group + 1));
        }
        symbolKeys[k][group] = key;
    }
}
