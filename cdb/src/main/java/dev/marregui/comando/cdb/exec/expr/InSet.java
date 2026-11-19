package dev.marregui.comando.cdb.exec.expr;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.exec.Cursor;
import dev.marregui.comando.cdb.exec.Page;
import dev.marregui.comando.cdb.exec.Vector;
import dev.marregui.comando.cdb.plan.Bound;

/**
 * The values of an IN subquery, read once into a set in the type its operand meets them in ({@link Cast#common}), so
 * that {@code in_query} answers as {@code in} over the same values written out: a NULL operand is in the set only when
 * the set holds a NULL, as two NULLs compare equal; doubles are equal by value, so -0.0 is 0.0; texts are equal by
 * their characters, a SYMBOL's and a VARCHAR's alike. Not changed once read, so the functions compiled over it on
 * several threads share it.
 */
public final class InSet {

    private final CdbType domain;
    // DOUBLE values, sorted, -0.0 read as 0.0
    private final double[] doubles;
    // LONG, TIMESTAMP and BOOLEAN (as 0 or 1) values, sorted
    private final long[] longs;
    private final boolean nullListed;
    // the rows read, NULLs and repeats included
    private final long size;
    private final Set<String> texts;

    private InSet(
            final CdbType domain,
            final double[] doubles,
            final long[] longs,
            final boolean nullListed,
            final long size,
            final Set<String> texts
    ) {
        this.domain = domain;
        this.doubles = doubles;
        this.longs = longs;
        this.nullListed = nullListed;
        this.size = size;
        this.texts = texts;
    }

    /**
     * Every row of {@code rows}, a cursor of one column, read as values met by an operand of {@code operandType}; the
     * caller closes {@code rows}. More than {@code maxRows} rows fail at {@code pos}, the IN's position.
     */
    public static InSet read(
            final Cursor rows,
            final CdbType operandType,
            final long nowMicros,
            final int maxRows,
            final int pos
    ) {
        final CdbType valueType = rows.fields().getFirst().type();
        final CdbType domain = Cast.common(operandType, valueType);
        final VectorFn values = Cast.operand(new Bound.Column(0, valueType), domain, nowMicros);
        final boolean text = domain.isText();
        final Set<String> texts = new HashSet<>();
        long[] longs = new long[text || domain == CdbType.DOUBLE ? 0 : Page.CAPACITY];
        double[] doubles = new double[domain == CdbType.DOUBLE ? Page.CAPACITY : 0];
        boolean nullListed = false;
        int count = 0;
        long size = 0;
        for (Page page = rows.next(); page != null; page = rows.next()) {
            size += page.size;
            if (size > maxRows) {
                throw new CdbException("cannot run an IN subquery past its rows [limit=" + maxRows + ']', pos);
            }
            final Vector v = values.eval(page);
            for (int i = 0; i < page.size; i++) {
                if (v.isNull(i)) {
                    nullListed = true;
                } else if (text) {
                    texts.add(v.textAt(i));
                } else if (domain == CdbType.DOUBLE) {
                    if (count == doubles.length) {
                        doubles = Arrays.copyOf(doubles, 2 * count);
                    }
                    doubles[count++] = byValue(v.getDouble(i));
                } else {
                    if (count == longs.length) {
                        longs = Arrays.copyOf(longs, 2 * count);
                    }
                    longs[count++] = asLong(v, i);
                }
            }
        }
        final long[] keptLongs = Arrays.copyOf(longs, domain == CdbType.DOUBLE || text ? 0 : count);
        final double[] keptDoubles = Arrays.copyOf(doubles, domain == CdbType.DOUBLE ? count : 0);
        Arrays.sort(keptLongs);
        Arrays.sort(keptDoubles);
        return new InSet(domain, keptDoubles, keptLongs, nullListed, size, texts);
    }

    /**
     * {@code operand IN} this set, compiled; {@code operand} is of the type the set was read for. The function keeps
     * its own output vector, so each operator compiles its own.
     */
    VectorFn membership(final Bound operand, final long nowMicros) {
        final VectorFn x = Cast.operand(operand, domain, nowMicros);
        final Vector out = Vector.of(CdbType.BOOLEAN, Page.CAPACITY);
        return page -> {
            final Vector v = x.eval(page);
            for (int i = 0, n = page.size; i < n; i++) {
                out.setBoolean(i, contains(v, i));
            }
            return out;
        };
    }

    @Override
    public String toString() {
        return "InSet[domain=" + domain + ", size=" + size + ']';
    }

    private static long asLong(final Vector v, final int i) {
        return v.type == CdbType.BOOLEAN ? v.getBoolean(i) ? 1 : 0 : v.getLong(i);
    }

    /** {@code d} with -0.0 read as 0.0, as the comparisons read it, so a sorted search finds either. */
    private static double byValue(final double d) {
        return d == 0.0 ? 0.0 : d;
    }

    private boolean contains(final Vector v, final int i) {
        if (v.isNull(i)) {
            return nullListed;
        }
        if (domain.isText()) {
            return texts.contains(v.textAt(i));
        }
        if (domain == CdbType.DOUBLE) {
            return Arrays.binarySearch(doubles, byValue(v.getDouble(i))) >= 0;
        }
        return Arrays.binarySearch(longs, asLong(v, i)) >= 0;
    }
}
