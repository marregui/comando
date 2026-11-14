package dev.marregui.comando.cdb.exec;

import java.util.List;

import dev.marregui.comando.cdb.plan.Plan;

/**
 * The input sorted by its keys, stable; NULLs first ascending, last descending (a descending key reverses the
 * ascending order); text by {@link String#compareTo}. The first {@link #next()} reads the whole input and closes it,
 * then pages are emitted full, the last may be shorter.
 */
final class SortCursor implements Cursor {

    /** Runs this short are insertion-sorted: a merge below it costs more than it saves (the usual cut-over). */
    private static final int INSERTION_RUN = 32;

    private final Cursor child;
    private final int[] keyColumns;
    private final boolean[] descending;
    private RowsBuffer rows;
    private int[] order;
    private int emitted;

    SortCursor(final Cursor child, final List<Plan.SortKey> keys) {
        this.child = child;
        keyColumns = new int[keys.size()];
        descending = new boolean[keys.size()];
        for (int k = 0; k < keyColumns.length; k++) {
            keyColumns[k] = keys.get(k).column();
            descending[k] = keys.get(k).descending();
        }
    }

    @Override
    public List<Field> fields() {
        return child.fields();
    }

    @Override
    public Page next() {
        if (order == null) {
            sort();
        }
        final int n = order.length;
        if (emitted == n) {
            return null;
        }
        final int count = Math.min(Page.CAPACITY, n - emitted);
        final Page out = Page.allocate(child.fields());
        for (int c = 0; c < out.columns.length; c++) {
            final Vector dst = out.columns[c];
            for (int i = 0; i < count; i++) {
                final int row = order[emitted + i];
                dst.copy(rows.pageOf(row).columns[c], row & Rows.ROW_MASK, i);
            }
        }
        out.size = count;
        emitted += count;
        if (emitted == n) {
            rows = null;
        }
        return out;
    }

    @Override
    public void close() {
        child.close();
    }

    private void sort() {
        final RowsBuffer buffer = new RowsBuffer(child.fields());
        Page page;
        while ((page = child.next()) != null) {
            buffer.addAll(page);
        }
        child.close();
        rows = buffer;
        final int n = buffer.rowCount();
        final int[] index = new int[n];
        for (int i = 0; i < n; i++) {
            index[i] = i;
        }
        if (keyColumns.length > 0 && n > 1) {
            mergeSort(index, new int[n], 0, n);
        }
        order = index;
    }

    /** Sorts {@code a[from, to)} stably, {@code aux} as scratch of the same length. */
    private void mergeSort(final int[] a, final int[] aux, final int from, final int to) {
        if (to - from <= INSERTION_RUN) {
            for (int i = from + 1; i < to; i++) {
                final int v = a[i];
                int j = i - 1;
                while (j >= from && compare(a[j], v) > 0) {
                    a[j + 1] = a[j];
                    j--;
                }
                a[j + 1] = v;
            }
            return;
        }
        final int mid = (from + to) >>> 1;
        mergeSort(a, aux, from, mid);
        mergeSort(a, aux, mid, to);
        if (compare(a[mid - 1], a[mid]) <= 0) {
            return;
        }
        System.arraycopy(a, from, aux, from, to - from);
        int i = from;
        int j = mid;
        for (int k = from; k < to; k++) {
            if (j >= to || i < mid && compare(aux[i], aux[j]) <= 0) {
                a[k] = aux[i++];
            } else {
                a[k] = aux[j++];
            }
        }
    }

    private int compare(final int x, final int y) {
        final Page px = rows.pageOf(x);
        final Page py = rows.pageOf(y);
        final int rx = x & Rows.ROW_MASK;
        final int ry = y & Rows.ROW_MASK;
        for (int k = 0; k < keyColumns.length; k++) {
            final int c = keyColumns[k];
            final int r = Rows.compare(px.columns[c], rx, py.columns[c], ry);
            if (r != 0) {
                return descending[k] ? -r : r;
            }
        }
        return 0;
    }
}
