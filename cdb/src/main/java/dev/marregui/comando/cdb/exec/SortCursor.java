package dev.marregui.comando.cdb.exec;

import java.util.List;

import org.jetbrains.annotations.TestOnly;

import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.plan.Plan;

/**
 * The input sorted by its keys, stable; NULLs first ascending, last descending (a descending key reverses the
 * ascending order), except a DOUBLE NULL, which QuestDB orders as the largest value (its NaN): last ascending, first
 * descending; text by {@link String#compareTo}. The first {@link #next()} reads the whole input and closes it, then
 * pages are emitted full, the last may be shorter.
 *
 * <p>With a limit of k, at least 0, only the first k rows of that order are kept and emitted: the first k input rows
 * are buffered as read; from row k + 1 on they form a max-heap (the row that sorts last at the root, ties broken by
 * input order), and a later row replaces the root only when it sorts strictly before it, so it costs one comparison
 * unless it enters. The k rows held are then sorted by key and input order. Its gain over the full sort is not
 * measured.
 */
final class SortCursor implements Cursor {

    /** Runs this short are insertion-sorted: a merge below it costs more than it saves (the usual cut-over). */
    private static final int INSERTION_RUN = 32;

    private final Cursor child;
    private final int[] keyColumns;
    private final boolean[] descending;
    // the rows to keep, -1 for all
    private final int limit;
    // true for a DOUBLE key, whose NULL sorts above every number
    private final boolean[] nullLargest;
    private RowsBuffer rows;
    // the input order of each held row once the heap replaced one, else null and a row's index is its input order
    private long[] sequence;
    private int[] order;
    private int emitted;

    @TestOnly
    SortCursor(final Cursor child, final List<Plan.SortKey> keys) {
        this(child, keys, -1);
    }

    SortCursor(final Cursor child, final List<Plan.SortKey> keys, final long limit) {
        this.child = child;
        keyColumns = new int[keys.size()];
        descending = new boolean[keys.size()];
        nullLargest = new boolean[keys.size()];
        final List<Field> fields = child.fields();
        for (int k = 0; k < keyColumns.length; k++) {
            keyColumns[k] = keys.get(k).column();
            descending[k] = keys.get(k).descending();
            nullLargest[k] = fields.get(keyColumns[k]).type() == CdbType.DOUBLE;
        }
        // a buffer holds fewer than 2^31 rows, so a larger limit keeps them all
        this.limit = limit < 0 || limit > Integer.MAX_VALUE ? -1 : (int) limit;
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
        rows = buffer;
        final int[] heap;
        if (limit < 0) {
            readAll(buffer);
            heap = null;
        } else {
            heap = keepFirst(buffer);
        }
        child.close();
        if (heap != null) {
            if (heap.length > 1) {
                mergeSort(heap, new int[heap.length], 0, heap.length);
            }
            order = heap;
            return;
        }
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

    private void readAll(final RowsBuffer buffer) {
        Page page;
        while ((page = child.next()) != null) {
            buffer.addAll(page);
        }
    }

    /**
     * Reads the input keeping the first {@link #limit} rows of the order; returns the heap of their row indexes, or
     * null when the input held no more than {@link #limit} rows, all of them in the buffer in input order.
     */
    private int[] keepFirst(final RowsBuffer buffer) {
        int[] heap = null;
        long base = 0;
        Page page;
        while ((page = child.next()) != null) {
            int r = 0;
            if (heap == null) {
                final int room = limit - buffer.rowCount();
                if (page.size <= room) {
                    buffer.addAll(page);
                    base += page.size;
                    continue;
                }
                for (; r < room; r++) {
                    buffer.add(page, r);
                }
                heap = heapOf(buffer.rowCount());
            }
            if (heap.length > 0 && keyColumns.length > 0) {
                for (; r < page.size; r++) {
                    final int root = heap[0];
                    if (compare(page, r, rows.pageOf(root), root & Rows.ROW_MASK) < 0) {
                        buffer.set(root, page, r);
                        sequence[root] = base + r;
                        siftDown(heap, 0, heap.length);
                    }
                }
            }
            base += page.size;
        }
        return heap;
    }

    /** The rows {@code 0..n-1}, held in input order, as a max-heap. */
    private int[] heapOf(final int n) {
        final int[] heap = new int[n];
        sequence = new long[n];
        for (int i = 0; i < n; i++) {
            heap[i] = i;
            sequence[i] = i;
        }
        for (int i = (n >>> 1) - 1; i >= 0; i--) {
            siftDown(heap, i, n);
        }
        return heap;
    }

    private void siftDown(final int[] heap, final int from, final int n) {
        final int moving = heap[from];
        int i = from;
        while (true) {
            int larger = 2 * i + 1;
            if (larger >= n) {
                break;
            }
            if (larger + 1 < n && compare(heap[larger + 1], heap[larger]) > 0) {
                larger++;
            }
            if (compare(heap[larger], moving) <= 0) {
                break;
            }
            heap[i] = heap[larger];
            i = larger;
        }
        heap[i] = moving;
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

    /** Held rows {@code x} and {@code y} by key, then by input order once {@link #sequence} holds it. */
    private int compare(final int x, final int y) {
        final int r = compare(rows.pageOf(x), x & Rows.ROW_MASK, rows.pageOf(y), y & Rows.ROW_MASK);
        return r != 0 || sequence == null ? r : Long.compare(sequence[x], sequence[y]);
    }

    private int compare(final Page px, final int rx, final Page py, final int ry) {
        for (int k = 0; k < keyColumns.length; k++) {
            final int c = keyColumns[k];
            final Vector vx = px.columns[c];
            final Vector vy = py.columns[c];
            int r = Rows.compare(vx, rx, vy, ry);
            if (r != 0 && nullLargest[k] && (vx.isNull(rx) || vy.isNull(ry))) {
                r = -r;
            }
            if (r != 0) {
                return descending[k] ? -r : r;
            }
        }
        return 0;
    }
}
