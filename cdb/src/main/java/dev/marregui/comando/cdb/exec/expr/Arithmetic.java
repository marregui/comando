package dev.marregui.comando.cdb.exec.expr;

import java.util.List;

import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.exec.Page;
import dev.marregui.comando.cdb.exec.Vector;
import dev.marregui.comando.cdb.plan.Bound;

/**
 * Arithmetic, {@code abs} and {@code round}, with QuestDB's results: a NULL operand gives NULL; LONG division is
 * integer division truncated toward zero and {@code %} takes the dividend's sign; dividing by zero gives NULL (NaN for
 * DOUBLE); a LONG result that lands on {@link Long#MIN_VALUE} reads as NULL, so {@code MAX + 1} is NULL. The operands
 * meet in DOUBLE when either is DOUBLE or the result is; otherwise in longs, TIMESTAMP and LONG alike.
 */
final class Arithmetic {

    static final int ADD = 0;
    static final int SUB = 1;
    static final int MUL = 2;
    static final int DIV = 3;
    static final int MOD = 4;

    // QuestDB's Numbers.roundHalfUp tolerance, kept so that 1.005 rounds to 1.01 as it does there
    private static final double TOLERANCE = 1E-15;
    // the scales roundHalfUp accepts: scale + 2 < 18 and scale > -18
    private static final int MAX_POWER = 18;
    private static final double[] POW10 = new double[MAX_POWER];
    private static final double[] POW10_NEG = new double[MAX_POWER];

    static {
        for (int i = 0; i < MAX_POWER; i++) {
            POW10[i] = Double.parseDouble("1e" + i);
            POW10_NEG[i] = Double.parseDouble("1e-" + i);
        }
    }

    private Arithmetic() {
    }

    static VectorFn binary(final int op, final List<Bound> args, final CdbType type, final long nowMicros) {
        final Bound left = args.get(0);
        final Bound right = args.get(1);
        if (type == CdbType.DOUBLE || left.type() == CdbType.DOUBLE || right.type() == CdbType.DOUBLE) {
            final VectorFn a = Cast.arg(left, CdbType.DOUBLE, nowMicros);
            final VectorFn b = Cast.arg(right, CdbType.DOUBLE, nowMicros);
            final Vector out = Vector.of(CdbType.DOUBLE, Page.CAPACITY);
            return Cast.exact(page -> {
                final double[] x = a.eval(page).doubles;
                final double[] y = b.eval(page).doubles;
                for (int i = 0, n = page.size; i < n; i++) {
                    out.doubles[i] = doubleOp(op, x[i], y[i]);
                }
                return out;
            }, CdbType.DOUBLE, type);
        }
        final CdbType natural = Cast.isLongs(type) ? type : CdbType.LONG;
        final VectorFn a = Cast.arg(left, CdbType.LONG, nowMicros);
        final VectorFn b = Cast.arg(right, CdbType.LONG, nowMicros);
        final Vector out = Vector.of(natural, Page.CAPACITY);
        return Cast.exact(page -> {
            final long[] x = a.eval(page).longs;
            final long[] y = b.eval(page).longs;
            for (int i = 0, n = page.size; i < n; i++) {
                out.longs[i] = longOp(op, x[i], y[i]);
            }
            return out;
        }, natural, type);
    }

    static VectorFn negate(final Bound arg, final CdbType type, final long nowMicros) {
        final CdbType natural = arg.type() == CdbType.DOUBLE ? CdbType.DOUBLE : Cast.isLongs(type) ? type : CdbType.LONG;
        final VectorFn a = Cast.arg(arg, natural, nowMicros);
        final Vector out = Vector.of(natural, Page.CAPACITY);
        return Cast.exact(page -> {
            final Vector in = a.eval(page);
            for (int i = 0, n = page.size; i < n; i++) {
                if (natural == CdbType.DOUBLE) {
                    out.doubles[i] = -in.doubles[i];
                } else {
                    // -MIN_VALUE is MIN_VALUE: NULL stays NULL
                    out.longs[i] = -in.longs[i];
                }
            }
            return out;
        }, natural, type);
    }

    static VectorFn abs(final Bound arg, final CdbType type, final long nowMicros) {
        final CdbType natural = arg.type() == CdbType.DOUBLE ? CdbType.DOUBLE : CdbType.LONG;
        final VectorFn a = Cast.arg(arg, natural, nowMicros);
        final Vector out = Vector.of(natural, Page.CAPACITY);
        return Cast.exact(page -> {
            final Vector in = a.eval(page);
            for (int i = 0, n = page.size; i < n; i++) {
                if (natural == CdbType.DOUBLE) {
                    out.doubles[i] = Math.abs(in.doubles[i]);
                } else {
                    // Math.abs(MIN_VALUE) is MIN_VALUE: NULL stays NULL
                    out.longs[i] = Math.abs(in.longs[i]);
                }
            }
            return out;
        }, natural, type);
    }

    /** {@code round(x)}: half away from zero as {@link Math#round(double)}; {@code round(x, scale)}: QuestDB's half-up. */
    static VectorFn round(final List<Bound> args, final CdbType type, final long nowMicros) {
        final VectorFn a = Cast.arg(args.get(0), CdbType.DOUBLE, nowMicros);
        final VectorFn s = args.size() > 1 ? Cast.arg(args.get(1), CdbType.LONG, nowMicros) : null;
        final Vector out = Vector.of(CdbType.DOUBLE, Page.CAPACITY);
        return Cast.exact(page -> {
            final double[] x = a.eval(page).doubles;
            final long[] scale = s == null ? null : s.eval(page).longs;
            for (int i = 0, n = page.size; i < n; i++) {
                out.doubles[i] = scale == null ? round(x[i]) : roundHalfUp(x[i], scale[i]);
            }
            return out;
        }, CdbType.DOUBLE, type);
    }

    static long longOp(final int op, final long a, final long b) {
        if (a == Vector.NULL_LONG || b == Vector.NULL_LONG) {
            return Vector.NULL_LONG;
        }
        return switch (op) {
            case ADD -> a + b;
            case SUB -> a - b;
            case MUL -> a * b;
            case DIV -> b == 0 ? Vector.NULL_LONG : a / b;
            case MOD -> b == 0 ? Vector.NULL_LONG : a % b;
            default -> throw new IllegalArgumentException("unknown arithmetic operator [op=" + op + "]");
        };
    }

    static double doubleOp(final int op, final double a, final double b) {
        return switch (op) {
            case ADD -> a + b;
            case SUB -> a - b;
            case MUL -> a * b;
            case DIV -> b == 0 ? Double.NaN : a / b;
            case MOD -> b == 0 ? Double.NaN : a % b;
            default -> throw new IllegalArgumentException("unknown arithmetic operator [op=" + op + "]");
        };
    }

    static double round(final double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return Double.NaN;
        }
        return Math.round(value);
    }

    /** QuestDB's {@code Numbers.roundHalfUp}: NaN for a NULL value or scale, or a scale outside (-18, 16). */
    static double roundHalfUp(final double value, final long scale) {
        if (Double.isNaN(value) || Double.isInfinite(value) || scale == Vector.NULL_LONG
                || !(scale + 2 < MAX_POWER && scale > -MAX_POWER)) {
            return Double.NaN;
        }
        final double magnitude = Math.abs(value);
        final double rounded = scale > 0 ? roundPositive(magnitude, (int) scale) : roundNegative(magnitude, (int) -scale);
        return Math.copySign(rounded, value);
    }

    private static double roundPositive(final double value, final int scale) {
        final long digits = (long) ((value + TOLERANCE) * POW10[scale + 2]);
        if (digits % 100 < 50) {
            return (double) (long) ((value + TOLERANCE) * POW10[scale]) * POW10_NEG[scale];
        }
        return (double) (long) (value * POW10[scale] + 1 - TOLERANCE) * POW10_NEG[scale];
    }

    private static double roundNegative(final double value, final int scale) {
        final long digits = (long) (value * POW10_NEG[scale] * 100 + TOLERANCE);
        if (digits % 100 < 50) {
            return (double) (long) ((value + TOLERANCE) * POW10_NEG[scale]) * POW10[scale];
        }
        return (double) (long) (value * POW10_NEG[scale] + 1 - TOLERANCE) * POW10[scale];
    }
}
