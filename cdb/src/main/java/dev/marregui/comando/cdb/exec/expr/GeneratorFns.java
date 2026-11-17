package dev.marregui.comando.cdb.exec.expr;

import java.util.List;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;

import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.exec.Page;
import dev.marregui.comando.cdb.exec.Vector;
import dev.marregui.comando.cdb.plan.Bound;

/**
 * QuestDB's generator functions, whose value at a row is not the row's own, so {@link Functions#rowLocal} is false for
 * them: {@code rnd_symbol} and {@code rnd_double}, each compiled function drawing from a generator of its own, and
 * {@code timestamp_sequence}, whose compiled function counts the rows it has produced, so the k-th row it evaluates
 * (from 0) is {@code start + k * step} microseconds.
 */
final class GeneratorFns {

    private GeneratorFns() {
    }

    /** {@code rnd_symbol(s1, ...)}: per row one of the texts, each as likely; every argument must be a constant. */
    static VectorFn rndSymbol(final List<Bound> args, final CdbType type) {
        final String[] symbols = new String[args.size()];
        for (int i = 0; i < symbols.length; i++) {
            symbols[i] = Functions.constantText(args.get(i), "rnd_symbol");
        }
        final RandomGenerator random = new SplittableRandom();
        final Vector out = Vector.of(CdbType.SYMBOL, Page.CAPACITY);
        return Cast.exact(page -> {
            for (int i = 0, n = page.size; i < n; i++) {
                out.setText(i, symbols[random.nextInt(symbols.length)]);
            }
            return out;
        }, CdbType.SYMBOL, type);
    }

    /** {@code rnd_double()}: per row a DOUBLE uniform in [0, 1). */
    static VectorFn rndDouble(final CdbType type) {
        final RandomGenerator random = new SplittableRandom();
        final Vector out = Vector.of(CdbType.DOUBLE, Page.CAPACITY);
        return Cast.exact(page -> {
            for (int i = 0, n = page.size; i < n; i++) {
                out.setDouble(i, random.nextDouble());
            }
            return out;
        }, CdbType.DOUBLE, type);
    }

    /**
     * {@code timestamp_sequence(start, step)}: a text start is read as a timestamp, as a cast reads it, and must be
     * one; a NULL start gives NULL. The step is a LONG constant in microseconds.
     */
    static VectorFn timestampSequence(final List<Bound> args, final CdbType type, final long nowMicros) {
        if (!(args.getLast() instanceof Bound.Constant(Long stepMicros, CdbType _))) {
            throw new CdbException("argument must be a constant [function=timestamp_sequence, argument=step]", -1);
        }
        final VectorFn starts = Cast.operand(args.getFirst(), CdbType.TIMESTAMP, nowMicros);
        return Cast.exact(new Sequence(starts, stepMicros), CdbType.TIMESTAMP, type);
    }

    /** The rows of one {@code timestamp_sequence}; {@code row} is the number of rows produced so far. */
    private static final class Sequence implements VectorFn {

        private final Vector out = Vector.of(CdbType.TIMESTAMP, Page.CAPACITY);
        private final VectorFn starts;
        private final long stepMicros;
        private long row;

        Sequence(final VectorFn starts, final long stepMicros) {
            this.starts = starts;
            this.stepMicros = stepMicros;
        }

        @Override
        public Vector eval(final Page page) {
            final Vector start = starts.eval(page);
            for (int i = 0, n = page.size; i < n; i++) {
                final long micros = start.getLong(i);
                out.setLong(i, micros == Vector.NULL_LONG ? Vector.NULL_LONG : micros + (row + i) * stepMicros);
            }
            row += page.size;
            return out;
        }
    }
}
