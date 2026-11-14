package dev.marregui.comando.cdb.exec;

/** A stable merge sort of row indexes by a comparison of the rows they name, without boxing. */
final class AggSort {

    // runs this short sort faster by insertion than by merging
    private static final int INSERTION_RUN = 16;

    private AggSort() {
    }

    /** {@code 0..n-1} sorted by {@code order}; equal rows keep their index order. */
    static int[] sorted(final int n, final RowOrder order) {
        final int[] index = new int[n];
        for (int i = 0; i < n; i++) {
            index[i] = i;
        }
        final int[] scratch = new int[n];
        sort(index, scratch, 0, n, order);
        return index;
    }

    private static void sort(final int[] a, final int[] scratch, final int from, final int to, final RowOrder order) {
        if (to - from <= INSERTION_RUN) {
            for (int i = from + 1; i < to; i++) {
                final int v = a[i];
                int j = i - 1;
                while (j >= from && order.compare(a[j], v) > 0) {
                    a[j + 1] = a[j];
                    j--;
                }
                a[j + 1] = v;
            }
            return;
        }
        final int mid = (from + to) >>> 1;
        sort(a, scratch, from, mid, order);
        sort(a, scratch, mid, to, order);
        if (order.compare(a[mid - 1], a[mid]) <= 0) {
            return;
        }
        System.arraycopy(a, from, scratch, from, to - from);
        int i = from;
        int j = mid;
        for (int k = from; k < to; k++) {
            if (j >= to || (i < mid && order.compare(scratch[i], scratch[j]) <= 0)) {
                a[k] = scratch[i++];
            } else {
                a[k] = scratch[j++];
            }
        }
    }

    /** Compares rows {@code a} and {@code b}: negative, zero or positive. */
    @FunctionalInterface
    interface RowOrder {
        int compare(int a, int b);
    }
}
