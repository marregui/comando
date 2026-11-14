package dev.marregui.comando.cdb.exec.agg;

import java.util.List;

import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.exec.Vector;
import dev.marregui.comando.cdb.plan.Bound;

/**
 * The aggregate functions of the corpus ({@code count}, {@code sum}, {@code avg}, {@code min}, {@code max},
 * {@code first}, {@code last}, {@code count_distinct}, {@code approx_percentile}, {@code percentile_disc},
 * {@code approx_median}, …) as accumulators the grouping operators use; each produces the type its
 * {@code Bound.Agg} carries, which {@link dev.marregui.comando.cdb.plan.Signatures} decided.
 */
public final class Aggregates {

    // HdrHistogram's default precision, the one QuestDB's approx_percentile and approx_median use without the argument
    private static final int DEFAULT_PRECISION = 1;
    // HdrHistogram takes 0 to 5 significant digits
    private static final int MAX_PRECISION = 5;

    private Aggregates() {
    }

    /**
     * The accumulator of {@code agg}. Only its first argument is a value read per row: the operator evaluates it and
     * passes it to {@link Accumulator#add}; the others (a percentile, a precision) are constants read here.
     * {@code count(*)} has no argument, and {@code count(DISTINCT x)} is {@code count_distinct(x)}.
     */
    public static Accumulator create(final Bound.Agg agg) {
        final String function = agg.function();
        final List<Bound> args = agg.args();
        if (agg.distinct() && !"count".equals(function)) {
            throw new CdbException("cannot run DISTINCT in this aggregate [function=" + function + ']', -1);
        }
        if (args.isEmpty()) {
            if ("count".equals(function)) {
                return new CountAccumulator(true);
            }
            throw new CdbException("cannot run an aggregate without its argument [function=" + function + ']', -1);
        }
        final CdbType in = args.getFirst().type();
        return switch (function) {
            case "count" -> agg.distinct() ? countDistinct(in) : new CountAccumulator(false);
            case "count_distinct" -> countDistinct(in);
            case "sum" -> switch (in) {
                case LONG, TIMESTAMP -> new LongSumAccumulator();
                case DOUBLE -> new DoubleSumAccumulator();
                default -> throw unsupported(function, in);
            };
            case "avg" -> switch (in) {
                case LONG, TIMESTAMP, DOUBLE -> new AvgAccumulator();
                default -> throw unsupported(function, in);
            };
            case "min", "max" -> minMax(function, in);
            case "first", "last" -> new FirstLastAccumulator(in, "last".equals(function));
            case "approx_percentile" -> percentile(function, in, fraction(function, args, 1),
                    precision(function, args, 2), true);
            case "approx_median" -> percentile(function, in, 0.5, precision(function, args, 1), true);
            case "percentile_disc" -> percentile(function, in, fraction(function, args, 1), DEFAULT_PRECISION, false);
            default -> throw new CdbException("cannot run unknown aggregate [function=" + function + ']', -1);
        };
    }

    /** Writes {@code value}, LONG's NULL for NULL, to {@code out[row]}, converted to the vector's type. */
    static void writeLong(final long value, final Vector out, final int row) {
        switch (out.type) {
            case LONG, TIMESTAMP -> out.longs[row] = value;
            case DOUBLE -> out.doubles[row] = value == Vector.NULL_LONG ? Double.NaN : value;
            default -> throw new CdbException("cannot write a number as text [type=" + out.type + ']', -1);
        }
    }

    /** Writes {@code value}, NaN for NULL, to {@code out[row]}, converted to the vector's type. */
    static void writeDouble(final double value, final Vector out, final int row) {
        switch (out.type) {
            case DOUBLE -> out.doubles[row] = value;
            case LONG, TIMESTAMP -> out.longs[row] = Double.isNaN(value) ? Vector.NULL_LONG : (long) value;
            default -> throw new CdbException("cannot write a number as text [type=" + out.type + ']', -1);
        }
    }

    private static Accumulator countDistinct(final CdbType in) {
        return in.isText() ? new TextCountDistinctAccumulator() : new LongCountDistinctAccumulator();
    }

    private static double fraction(final String function, final List<Bound> args, final int index) {
        if (args.size() <= index) {
            throw new CdbException("cannot run a percentile without its fraction [function=" + function + ']', -1);
        }
        final double p = constant(function, args.get(index));
        if (!(p >= 0.0 && p <= 1.0)) {
            throw new CdbException("cannot take a percentile outside [0, 1] [function=" + function + ", percentile="
                    + p + ']', -1);
        }
        return p;
    }

    private static double constant(final String function, final Bound arg) {
        if (arg instanceof Bound.Constant(Object value, CdbType _) && value instanceof Number n) {
            return n.doubleValue();
        }
        throw new CdbException("cannot take a non-constant aggregate argument [function=" + function + ']', -1);
    }

    private static Accumulator minMax(final String function, final CdbType in) {
        final boolean max = "max".equals(function);
        return switch (in) {
            case LONG, TIMESTAMP -> new LongMinMaxAccumulator(max);
            case DOUBLE -> new DoubleMinMaxAccumulator(max);
            case SYMBOL, VARCHAR -> new TextMinMaxAccumulator(max);
            case BOOLEAN -> throw unsupported(function, in);
        };
    }

    private static Accumulator percentile(
            final String function,
            final CdbType in,
            final double fraction,
            final int precision,
            final boolean approximate
    ) {
        return switch (in) {
            case LONG, TIMESTAMP -> new LongPercentileAccumulator(fraction, approximate ? precision : -1);
            case DOUBLE -> new DoublePercentileAccumulator(fraction);
            default -> throw unsupported(function, in);
        };
    }

    private static int precision(final String function, final List<Bound> args, final int index) {
        if (args.size() <= index) {
            return DEFAULT_PRECISION;
        }
        final double precision = constant(function, args.get(index));
        if (precision != Math.rint(precision) || precision < 0 || precision > MAX_PRECISION) {
            throw new CdbException("cannot take a precision outside 0 to 5 [function=" + function + ", precision="
                    + precision + ']', -1);
        }
        return (int) precision;
    }

    private static CdbException unsupported(final String function, final CdbType in) {
        return new CdbException("cannot run aggregate on this type [function=" + function + ", type=" + in + ']', -1);
    }
}
