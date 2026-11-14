package dev.marregui.comando.cdb.exec;

import java.util.Arrays;
import java.util.List;

import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.exec.agg.Values;

/**
 * The distinct key tuples seen, numbered from 0 in first-seen order: an open-addressing table of group numbers over
 * one {@link Values} column per key. NULL equals NULL, so NULL keys form one group. With no key column every row is
 * group 0.
 */
final class AggKeys {

    private static final int INITIAL_SLOTS = 64;

    private final Values[] columns;
    private int[] hashes = new int[16];
    private int size;
    // group + 1 per slot, 0 for an empty slot
    private int[] slots = new int[INITIAL_SLOTS];

    AggKeys(final List<CdbType> types) {
        columns = new Values[types.size()];
        for (int k = 0; k < columns.length; k++) {
            columns[k] = new Values(types.get(k));
        }
    }

    /** The group of row {@code row} of {@code keys}, one vector per key column, added when new. */
    int groupOf(final Vector[] keys, final int row) {
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
        if (group == hashes.length) {
            hashes = Arrays.copyOf(hashes, Math.max(hashes.length * 2, group + 1));
        }
        for (int k = 0; k < columns.length; k++) {
            columns[k].ensure(group + 1);
            columns[k].set(group, keys[k], row);
        }
        hashes[group] = hash;
        slots[i] = group + 1;
        size++;
        // a load factor of at most one half keeps probe runs short
        if (size > slots.length >>> 1) {
            rehash();
        }
        return group;
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

    private int hash(final Vector[] keys, final int row) {
        int h = 1;
        for (int k = 0; k < columns.length; k++) {
            h = 31 * h + Values.hash(keys[k], row);
        }
        return mix(h);
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

    private boolean same(final int group, final Vector[] keys, final int row) {
        for (int k = 0; k < columns.length; k++) {
            if (!columns[k].sameAs(group, keys[k], row)) {
                return false;
            }
        }
        return true;
    }
}
