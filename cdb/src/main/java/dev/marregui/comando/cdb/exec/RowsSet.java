package dev.marregui.comando.cdb.exec;

import java.util.Arrays;
import java.util.List;

import dev.marregui.comando.cdb.CdbException;

/**
 * Distinct whole rows, NULL equal to NULL: an open-addressing table of row indexes into a {@link RowsBuffer}, a
 * power-of-two table with linear probing over the rows' mixed hashes; no removal.
 */
final class RowsSet {

    private final RowsBuffer rows;
    private int[] hashes = new int[16];
    private int[] slots = new int[16];
    private int mask = 15;

    RowsSet(final List<Field> fields) {
        rows = new RowsBuffer(fields);
    }

    /** Adds row {@code row} of {@code page}; false when an equal row is already in. */
    boolean add(final Page page, final int row) {
        final int hash = Rows.hash(page, row);
        int slot = hash & mask;
        int entry;
        while ((entry = slots[slot]) != 0) {
            if (matches(entry - 1, hash, page, row)) {
                return false;
            }
            slot = (slot + 1) & mask;
        }
        final int index = rows.add(page, row);
        if (index == hashes.length) {
            hashes = Arrays.copyOf(hashes, hashes.length * 2);
        }
        hashes[index] = hash;
        slots[slot] = index + 1;
        if ((index + 1) * 2 > slots.length) {
            grow();
        }
        return true;
    }

    /** True when a row equal to row {@code row} of {@code page} is in. */
    boolean contains(final Page page, final int row) {
        final int hash = Rows.hash(page, row);
        int slot = hash & mask;
        int entry;
        while ((entry = slots[slot]) != 0) {
            if (matches(entry - 1, hash, page, row)) {
                return true;
            }
            slot = (slot + 1) & mask;
        }
        return false;
    }

    private boolean matches(final int index, final int hash, final Page page, final int row) {
        return hashes[index] == hash && Rows.same(rows.pageOf(index), index & Rows.ROW_MASK, page, row);
    }

    private void grow() {
        final int capacity = slots.length * 2;
        if (capacity < 0) {
            throw new CdbException("cannot hold more distinct rows in memory [rows=" + rows.rowCount() + "]", -1);
        }
        final int[] grown = new int[capacity];
        final int m = capacity - 1;
        for (int i = 0, n = rows.rowCount(); i < n; i++) {
            int slot = hashes[i] & m;
            while (grown[slot] != 0) {
                slot = (slot + 1) & m;
            }
            grown[slot] = i + 1;
        }
        slots = grown;
        mask = m;
    }
}
