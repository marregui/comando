package dev.marregui.comando.cdb.exec;

import java.util.Objects;

import dev.marregui.comando.cdb.CdbType;

/**
 * Row-level helpers the row operators share: copies between vectors, the row identity of the set operators (NULL equals
 * NULL, as QuestDB compares rows), the sort order (NULL first) and the close of two children.
 */
final class Rows {

    /** A row index's page in a packed list of pages: every page full but the last. */
    static final int PAGE_SHIFT = Integer.numberOfTrailingZeros(Page.CAPACITY);
    /** A row index's row within its page in a packed list of pages. */
    static final int ROW_MASK = Page.CAPACITY - 1;

    private Rows() {
    }

    /** Copies {@code n} values of {@code src} from {@code from} to {@code dst} at {@code to}; same storage. */
    static void copyRange(final Vector src, final int from, final Vector dst, final int to, final int n) {
        switch (dst.type) {
            case LONG, TIMESTAMP -> System.arraycopy(src.longs, from, dst.longs, to, n);
            case DOUBLE -> System.arraycopy(src.doubles, from, dst.doubles, to, n);
            case BOOLEAN -> System.arraycopy(src.booleans, from, dst.booleans, to, n);
            case SYMBOL, VARCHAR -> System.arraycopy(src.texts, from, dst.texts, to, n);
        }
    }

    /** True when values of {@code a} and {@code b} are held in the same array kind of a {@link Vector}. */
    static boolean sameStorage(final CdbType a, final CdbType b) {
        return storage(a) == storage(b);
    }

    /** The hash of row {@code row} of {@code page} over every column, consistent with {@link #same}. */
    static int hash(final Page page, final int row) {
        int h = 1;
        for (final Vector v : page.columns) {
            h = 31 * h + hash(v, row);
        }
        return mix(h);
    }

    /** The hash of value {@code i} of {@code v}, consistent with {@link #same(Vector, int, Vector, int)}. */
    static int hash(final Vector v, final int i) {
        return switch (v.type) {
            case LONG, TIMESTAMP -> Long.hashCode(v.longs[i]);
            case DOUBLE -> {
                final double d = v.doubles[i];
                // 0.0 and -0.0 are one value; Double.hashCode folds every NaN into one, and NaN is the one NULL
                yield d == 0.0 ? 0 : Double.hashCode(d);
            }
            case BOOLEAN -> v.booleans[i] ? 1231 : 1237;
            case SYMBOL, VARCHAR -> Objects.hashCode(v.texts[i]);
        };
    }

    /** True when every column of row {@code ra} of {@code a} equals that of row {@code rb} of {@code b}. */
    static boolean same(final Page a, final int ra, final Page b, final int rb) {
        final Vector[] ca = a.columns;
        final Vector[] cb = b.columns;
        for (int c = 0; c < ca.length; c++) {
            if (!same(ca[c], ra, cb[c], rb)) {
                return false;
            }
        }
        return true;
    }

    /** True when value {@code ia} of {@code a} equals value {@code ib} of {@code b}; NULL equals NULL. */
    static boolean same(final Vector a, final int ia, final Vector b, final int ib) {
        return switch (a.type) {
            case LONG, TIMESTAMP -> a.longs[ia] == b.longs[ib];
            case DOUBLE -> {
                final double x = a.doubles[ia];
                final double y = b.doubles[ib];
                yield x == y || Double.isNaN(x) && Double.isNaN(y);
            }
            case BOOLEAN -> a.booleans[ia] == b.booleans[ib];
            case SYMBOL, VARCHAR -> Objects.equals(a.texts[ia], b.texts[ib]);
        };
    }

    /**
     * The ascending order of value {@code ia} of {@code a} and value {@code ib} of {@code b}: NULL before every value,
     * text by {@link String#compareTo}, false before true.
     */
    static int compare(final Vector a, final int ia, final Vector b, final int ib) {
        return switch (a.type) {
            case LONG, TIMESTAMP -> Long.compare(a.longs[ia], b.longs[ib]);
            case DOUBLE -> {
                final double x = a.doubles[ia];
                final double y = b.doubles[ib];
                if (Double.isNaN(x)) {
                    yield Double.isNaN(y) ? 0 : -1;
                }
                if (Double.isNaN(y)) {
                    yield 1;
                }
                yield x < y ? -1 : x > y ? 1 : 0;
            }
            case BOOLEAN -> Boolean.compare(a.booleans[ia], b.booleans[ib]);
            case SYMBOL, VARCHAR -> {
                final String x = a.texts[ia];
                final String y = b.texts[ib];
                if (x == null) {
                    yield y == null ? 0 : -1;
                }
                yield y == null ? 1 : x.compareTo(y);
            }
        };
    }

    /** Closes both cursors; a failure of the first does not skip the second, and the second's is suppressed into it. */
    static void close(final Cursor first, final Cursor second) {
        RuntimeException failure = null;
        try {
            first.close();
        } catch (final RuntimeException e) {
            failure = e;
        }
        try {
            second.close();
        } catch (final RuntimeException e) {
            if (failure == null) {
                failure = e;
            } else {
                failure.addSuppressed(e);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    /** The murmur3 32-bit finaliser: spreads a hash over the low bits a power-of-two table masks. */
    static int mix(final int h) {
        int x = h;
        x ^= x >>> 16;
        x *= 0x85ebca6b;
        x ^= x >>> 13;
        x *= 0xc2b2ae35;
        x ^= x >>> 16;
        return x;
    }

    private static int storage(final CdbType type) {
        return switch (type) {
            case LONG, TIMESTAMP -> 0;
            case DOUBLE -> 1;
            case BOOLEAN -> 2;
            case SYMBOL, VARCHAR -> 3;
        };
    }
}
