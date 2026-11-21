package dev.marregui.comando.cdb.exec.expr;

import java.util.Arrays;
import java.util.List;

import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.exec.Page;
import dev.marregui.comando.cdb.exec.SymbolDictionary;
import dev.marregui.comando.cdb.exec.Vector;
import dev.marregui.comando.cdb.plan.Bound;

/**
 * Comparisons, {@code in}, {@code not_in} and {@code between}, with QuestDB's NULL rules: two NULLs compare equal, so
 * {@code =}, {@code <=} and {@code >=} are true and the others false; one NULL makes only {@code !=} true. The operands
 * meet in {@link Cast#common}, a text meeting a TIMESTAMP is read as one and fails when it is none; doubles compare by
 * value, so -0.0 equals 0.0. {@code between} is false when any operand
 * is NULL and swaps reversed bounds. A text constant meeting a text column is matched against each row's value in
 * place, without decoding the row; for {@code =}, {@code !=} and {@code in}, a SYMBOL row that carries a dictionary
 * key is matched by that key against the constant's key in the same dictionary, resolved once per dictionary.
 */
final class Comparison {

    static final int EQ = 0;
    static final int NE = 1;
    static final int LT = 2;
    static final int LE = 3;
    static final int GT = 4;
    static final int GE = 5;

    private static final int BOTH_NULL = 2;
    private static final int ONE_NULL = 3;
    // per operator: the outcome when the left side is less, equal, greater
    private static final boolean[][] TRUTH = {
            {false, true, false},
            {true, false, true},
            {true, false, false},
            {true, true, false},
            {false, false, true},
            {false, true, true},
    };

    private Comparison() {
    }

    static VectorFn compare(final int op, final List<Bound> args, final CdbType type, final long nowMicros) {
        final CdbType domain = Cast.common(args);
        final VectorFn a = Cast.operand(args.get(0), domain, nowMicros);
        final VectorFn b = Cast.operand(args.get(1), domain, nowMicros);
        final String right = textConstant(args.get(1), domain);
        final String left = right == null ? textConstant(args.get(0), domain) : null;
        final boolean equality = op == EQ || op == NE;
        final KeyedConstant keyed = equality ? new KeyedConstant(right != null ? right : left) : null;
        final Vector out = Vector.of(CdbType.BOOLEAN, Page.CAPACITY);
        return Cast.exact(page -> {
            final int n = page.size;
            if (right != null) {
                final Vector x = a.eval(page);
                if (equality) {
                    final int key = keyed.key(x);
                    for (int i = 0; i < n; i++) {
                        out.setBoolean(i, outcome(op, equalityOrder(x, i, key, right)));
                    }
                } else {
                    for (int i = 0; i < n; i++) {
                        out.setBoolean(i, outcome(op, order(x, i, right)));
                    }
                }
            } else if (left != null) {
                final Vector y = b.eval(page);
                if (equality) {
                    final int key = keyed.key(y);
                    for (int i = 0; i < n; i++) {
                        out.setBoolean(i, outcome(op, equalityOrder(y, i, key, left)));
                    }
                } else {
                    for (int i = 0; i < n; i++) {
                        out.setBoolean(i, outcome(op, reversed(order(y, i, left))));
                    }
                }
            } else {
                final Vector x = a.eval(page);
                final Vector y = b.eval(page);
                for (int i = 0; i < n; i++) {
                    out.setBoolean(i, outcome(op, order(x, y, i)));
                }
            }
            return out;
        }, CdbType.BOOLEAN, type);
    }

    /** {@code args[0] in (args[1], ...)} by the equality of {@link #compare}; {@code negated} for {@code not_in}. */
    static VectorFn in(final List<Bound> args, final boolean negated, final CdbType type, final long nowMicros) {
        final CdbType domain = Cast.common(args);
        final VectorFn operand = Cast.operand(args.get(0), domain, nowMicros);
        final VectorFn[] values = Cast.operands(args, 1, domain, nowMicros);
        if ((domain == CdbType.LONG || domain == CdbType.TIMESTAMP) && allConstant(args)) {
            return Cast.exact(new LongSet(operand, values, negated), CdbType.BOOLEAN, type);
        }
        final String[] constants = new String[values.length];
        for (int j = 0; j < constants.length; j++) {
            constants[j] = textConstant(args.get(1 + j), domain);
        }
        final KeyedConstant[] keyed = new KeyedConstant[values.length];
        for (int j = 0; j < keyed.length; j++) {
            keyed[j] = constants[j] == null ? null : new KeyedConstant(constants[j]);
        }
        final Vector[] evaluated = new Vector[values.length];
        final int[] keys = new int[values.length];
        final Vector out = Vector.of(CdbType.BOOLEAN, Page.CAPACITY);
        return Cast.exact(page -> {
            final Vector x = operand.eval(page);
            for (int j = 0; j < values.length; j++) {
                evaluated[j] = constants[j] == null ? values[j].eval(page) : null;
                keys[j] = constants[j] == null ? Vector.NO_KEY : keyed[j].key(x);
            }
            for (int i = 0, n = page.size; i < n; i++) {
                boolean found = false;
                for (int j = 0; j < evaluated.length && !found; j++) {
                    found = constants[j] == null
                            ? outcome(EQ, order(x, evaluated[j], i))
                            : equalityOrder(x, i, keys[j], constants[j]) == 0;
                }
                out.setBoolean(i, found != negated);
            }
            return out;
        }, CdbType.BOOLEAN, type);
    }

    static VectorFn between(final List<Bound> args, final CdbType type, final long nowMicros) {
        final CdbType domain = Cast.common(args);
        final VectorFn operand = Cast.operand(args.get(0), domain, nowMicros);
        final VectorFn low = Cast.operand(args.get(1), domain, nowMicros);
        final VectorFn high = Cast.operand(args.get(2), domain, nowMicros);
        final Vector out = Vector.of(CdbType.BOOLEAN, Page.CAPACITY);
        return Cast.exact(page -> {
            final Vector x = operand.eval(page);
            final Vector lo = low.eval(page);
            final Vector hi = high.eval(page);
            for (int i = 0, n = page.size; i < n; i++) {
                final int toLow = order(x, lo, i);
                final int toHigh = order(x, hi, i);
                final int bounds = order(lo, hi, i);
                if (toLow > 1 || toHigh > 1 || bounds > 1) {
                    out.setBoolean(i, false);
                } else if (bounds > 0) {
                    out.setBoolean(i, toHigh >= 0 && toLow <= 0);
                } else {
                    out.setBoolean(i, toLow >= 0 && toHigh <= 0);
                }
            }
            return out;
        }, CdbType.BOOLEAN, type);
    }

    private static boolean outcome(final int op, final int order) {
        return switch (order) {
            case BOTH_NULL -> TRUTH[op][1];
            case ONE_NULL -> op == NE;
            default -> TRUTH[op][order + 1];
        };
    }

    /** -1, 0 or 1 for value {@code i} of {@code a} against that of {@code b}, of one storage; else a NULL code. */
    private static int order(final Vector a, final Vector b, final int i) {
        final boolean aNull = a.isNull(i);
        final boolean bNull = b.isNull(i);
        if (aNull || bNull) {
            return aNull && bNull ? BOTH_NULL : ONE_NULL;
        }
        return switch (a.type) {
            case LONG, TIMESTAMP -> Long.compare(a.getLong(i), b.getLong(i));
            case DOUBLE -> a.getDouble(i) < b.getDouble(i) ? -1 : a.getDouble(i) == b.getDouble(i) ? 0 : 1;
            case BOOLEAN -> Boolean.compare(a.getBoolean(i), b.getBoolean(i));
            case SYMBOL, VARCHAR, JSON -> Integer.signum(a.textCompare(i, b, i));
        };
    }

    /** {@link #order} of text value {@code i} of {@code a} against the non-null {@code value}. */
    private static int order(final Vector a, final int i, final String value) {
        return a.isNull(i) ? ONE_NULL : Integer.signum(a.textCompare(i, value));
    }

    /**
     * {@link #order} of text value {@code i} of {@code a} against the non-null {@code value} as far as equality goes:
     * 0 when equal, 1 when not, a NULL code; a row carrying a dictionary key is compared by it with {@code key}, the
     * value's key in that dictionary or -1.
     */
    private static int equalityOrder(final Vector a, final int i, final int key, final String value) {
        if (a.isNull(i)) {
            return ONE_NULL;
        }
        final int rowKey = a.symbolKey(i);
        if (rowKey != Vector.NO_KEY) {
            return rowKey == key ? 0 : 1;
        }
        return a.textEquals(i, value) ? 0 : 1;
    }

    /** Whether every value of {@code in}'s list, {@code args} from the second on, is a constant. */
    private static boolean allConstant(final List<Bound> args) {
        for (int j = 1; j < args.size(); j++) {
            if (!(args.get(j) instanceof Bound.Constant)) {
                return false;
            }
        }
        return true;
    }

    /** {@code order} with its two sides swapped; a NULL code is symmetric. */
    private static int reversed(final int order) {
        return order > 1 ? order : -order;
    }

    /** The text of {@code arg} when it is a non-NULL text constant read in the text {@code domain}, else null. */
    private static String textConstant(final Bound arg, final CdbType domain) {
        return domain.isText() && arg instanceof Bound.Constant(Object value, CdbType _) && value instanceof String s
                ? s
                : null;
    }

    /**
     * A text constant's key in the dictionary of the vectors it meets, resolved again only for another dictionary, or
     * when it was absent and the dictionary has grown since: a key a page carries is below the size read after it.
     * Used by one thread at a time, as the function that holds it.
     */
    private static final class KeyedConstant {
        private final String value;
        private SymbolDictionary dictionary;
        private int key = -1;
        private int size = -1;

        KeyedConstant(final String value) {
            this.value = value;
        }

        /** The value's key in {@code v}'s dictionary, -1 when it holds none or {@code v} carries none. */
        int key(final Vector v) {
            final SymbolDictionary carried = v.symbolDictionary();
            if (carried == null) {
                return -1;
            }
            if (carried != dictionary || key < 0 && carried.size() != size) {
                dictionary = carried;
                size = carried.size();
                key = carried.keyOf(value);
            }
            return key;
        }
    }

    /**
     * {@code in} over a list of LONG or TIMESTAMP constants: one binary search per row in the sorted non-NULL values,
     * instead of one comparison per value. The values are read once, from the first page with a row, as a constant
     * reads the same on every row; a NULL row is in the list only when the list holds a NULL, as two NULLs compare
     * equal. Used by one thread at a time, as the function that holds it.
     */
    private static final class LongSet implements VectorFn {
        private final boolean negated;
        private final VectorFn operand;
        private final Vector out = Vector.of(CdbType.BOOLEAN, Page.CAPACITY);
        private final VectorFn[] values;
        private boolean nullListed;
        private long[] sorted;

        LongSet(final VectorFn operand, final VectorFn[] values, final boolean negated) {
            this.operand = operand;
            this.values = values;
            this.negated = negated;
        }

        @Override
        public Vector eval(final Page page) {
            final int n = page.size;
            if (n == 0) {
                return out;
            }
            if (sorted == null) {
                read(page);
            }
            final Vector x = operand.eval(page);
            for (int i = 0; i < n; i++) {
                final boolean found = x.isNull(i) ? nullListed : Arrays.binarySearch(sorted, x.getLong(i)) >= 0;
                out.setBoolean(i, found != negated);
            }
            return out;
        }

        private void read(final Page page) {
            final long[] read = new long[values.length];
            int count = 0;
            for (final VectorFn value : values) {
                final Vector v = value.eval(page);
                if (v.isNull(0)) {
                    nullListed = true;
                } else {
                    read[count++] = v.getLong(0);
                }
            }
            final long[] kept = Arrays.copyOf(read, count);
            Arrays.sort(kept);
            sorted = kept;
        }
    }
}
