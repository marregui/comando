package dev.marregui.comando.cdb.exec.agg;

import java.util.Arrays;

/**
 * In-place selection of the k-th smallest value in the order {@link Arrays#sort} gives ({@link Double#compare} for
 * doubles, so -0.0 sorts before 0.0): a quickselect with a median-of-three pivot and a three-way partition, so runs of
 * equal values cost one pass; past a depth of twice the range's bit length it sorts what is left, which bounds the
 * worst case to that of the sort. Doubles in a range of {@link #RADIX_RANGE} or more first go through radix passes:
 * each counts the values per digit of their order-preserving bits, the digit {@code DIGIT_BITS} wide just below the
 * bits the range shares, and keeps the bucket holding rank k, partitioned three ways by moves with no data-dependent
 * branch. On return {@code a[k]} holds the value the sort would put there, no value before it is greater and no value
 * after it is smaller; the rest of the order is unspecified.
 */
final class Select {

    // 2^11 counters, 8 KB, stay in the L1 cache
    private static final int DIGIT_BITS = 11;
    // ranges this short finish faster by insertion than by partitioning (not measured)
    private static final int INSERTION_RANGE = 16;
    // below this many values the radix passes' sweeps and 8 KB of counters are not expected to pay; the cut itself
    // is not measured. The passes took percentile_cont over 2 M doubles from 60.3 ms to 44.5 ms, best of 9 HTTP
    // requests over the bench data, M2 Max, -Xmx512m
    private static final int RADIX_RANGE = 4_096;

    private Select() {
    }

    /** The k-th smallest of {@code a[0, n)}, {@code k} 0-based, {@code a} partitioned around it. */
    static double kthDouble(final double[] a, final int n, final int k) {
        int lo = 0;
        int end = n;
        int[] counts = null;
        while (end - lo >= RADIX_RANGE) {
            long all = -1L;
            long any = 0L;
            for (int i = lo; i < end; i++) {
                final long u = orderedBits(a[i]);
                all &= u;
                any |= u;
            }
            final long differ = all ^ any;
            if (differ == 0) {
                // every value in the range is the k-th
                return a[k];
            }
            final int shift = Math.max(0, 64 - Long.numberOfLeadingZeros(differ) - DIGIT_BITS);
            if (counts == null) {
                counts = new int[1 << DIGIT_BITS];
            } else {
                Arrays.fill(counts, 0);
            }
            for (int i = lo; i < end; i++) {
                counts[digit(a[i], shift)]++;
            }
            int below = lo;
            int digit = 0;
            while (below + counts[digit] <= k) {
                below += counts[digit];
                digit++;
            }
            moveFirst(a, lo, end, shift, digit, true);
            end = moveFirst(a, below, end, shift, digit, false);
            lo = below;
        }
        return quickDouble(a, lo, end - 1, k);
    }

    /** The k-th smallest of {@code a[0, n)}, {@code k} 0-based, {@code a} partitioned around it. */
    static long kthLong(final long[] a, final int n, final int k) {
        int lo = 0;
        int hi = n - 1;
        int depth = 2 * (32 - Integer.numberOfLeadingZeros(n));
        while (hi - lo >= INSERTION_RANGE) {
            if (depth-- == 0) {
                Arrays.sort(a, lo, hi + 1);
                return a[k];
            }
            final long pivot = medianOfThree(a[lo], a[(lo + hi) >>> 1], a[hi]);
            int lt = lo;
            int i = lo;
            int gt = hi;
            while (i <= gt) {
                if (a[i] < pivot) {
                    swap(a, lt++, i++);
                } else if (a[i] > pivot) {
                    swap(a, i, gt--);
                } else {
                    i++;
                }
            }
            if (k < lt) {
                hi = lt - 1;
            } else if (k > gt) {
                lo = gt + 1;
            } else {
                return a[k];
            }
        }
        for (int i = lo + 1; i <= hi; i++) {
            final long v = a[i];
            int j = i - 1;
            while (j >= lo && a[j] > v) {
                a[j + 1] = a[j];
                j--;
            }
            a[j + 1] = v;
        }
        return a[k];
    }

    /** The smallest of {@code a[from, n)} by {@link Double#compare}; {@code from < n}. */
    static double minDouble(final double[] a, final int from, final int n) {
        double min = a[from];
        for (int i = from + 1; i < n; i++) {
            if (Double.compare(a[i], min) < 0) {
                min = a[i];
            }
        }
        return min;
    }

    /** The smallest of {@code a[0, n)}; {@code n > 0}. */
    static long minLong(final long[] a, final int n) {
        long min = a[0];
        for (int i = 1; i < n; i++) {
            min = Math.min(min, a[i]);
        }
        return min;
    }

    private static double quickDouble(final double[] a, final int from, final int to, final int k) {
        int lo = from;
        int hi = to;
        int depth = 2 * (32 - Integer.numberOfLeadingZeros(to - from + 1));
        while (hi - lo >= INSERTION_RANGE) {
            if (depth-- == 0) {
                Arrays.sort(a, lo, hi + 1);
                return a[k];
            }
            final double pivot = medianOfThree(a[lo], a[(lo + hi) >>> 1], a[hi]);
            int lt = lo;
            int i = lo;
            int gt = hi;
            while (i <= gt) {
                final int c = Double.compare(a[i], pivot);
                if (c < 0) {
                    swap(a, lt++, i++);
                } else if (c > 0) {
                    swap(a, i, gt--);
                } else {
                    i++;
                }
            }
            if (k < lt) {
                hi = lt - 1;
            } else if (k > gt) {
                lo = gt + 1;
            } else {
                return a[k];
            }
        }
        for (int i = lo + 1; i <= hi; i++) {
            final double v = a[i];
            int j = i - 1;
            while (j >= lo && Double.compare(a[j], v) > 0) {
                a[j + 1] = a[j];
                j--;
            }
            a[j + 1] = v;
        }
        return a[k];
    }

    private static int digit(final double v, final int shift) {
        return (int) (orderedBits(v) >>> shift) & ((1 << DIGIT_BITS) - 1);
    }

    /**
     * Moves the values of {@code a[from, to)} whose digit at {@code shift} is below {@code digit} ({@code below}) or
     * equal to it to the front of the range; returns the end of what moved. Every step swaps, whether its
     * value moves or not, so the loop has no data-dependent branch.
     */
    private static int moveFirst(final double[] a, final int from, final int to, final int shift, final int digit,
                                 final boolean below) {
        int w = from;
        for (int i = from; i < to; i++) {
            final double v = a[i];
            final int d = digit(v, shift);
            final int moves = (below ? d < digit : d == digit) ? 1 : 0;
            a[i] = a[w];
            a[w] = v;
            w += moves;
        }
        return w;
    }

    /** The bits of {@code v} as an unsigned number in {@link Double#compare} order (NaN last, -0.0 before 0.0). */
    private static long orderedBits(final double v) {
        final long bits = Double.doubleToLongBits(v);
        return bits ^ (bits >> 63 | Long.MIN_VALUE);
    }

    private static double medianOfThree(final double a, final double b, final double c) {
        if (Double.compare(a, b) > 0) {
            if (Double.compare(b, c) >= 0) {
                return b;
            }
            return Double.compare(a, c) > 0 ? c : a;
        }
        if (Double.compare(a, c) >= 0) {
            return a;
        }
        return Double.compare(b, c) > 0 ? c : b;
    }

    private static long medianOfThree(final long a, final long b, final long c) {
        if (a > b) {
            return b >= c ? b : Math.min(a, c);
        }
        return a >= c ? a : Math.min(b, c);
    }

    private static void swap(final double[] a, final int i, final int j) {
        final double t = a[i];
        a[i] = a[j];
        a[j] = t;
    }

    private static void swap(final long[] a, final int i, final int j) {
        final long t = a[i];
        a[i] = a[j];
        a[j] = t;
    }
}
