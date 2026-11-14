package dev.marregui.comando.cdb.exec.expr;

import java.util.List;

import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.exec.Page;
import dev.marregui.comando.cdb.exec.Vector;
import dev.marregui.comando.cdb.plan.Bound;

/**
 * The TIMESTAMP functions, with QuestDB's units and results. {@code dateadd} and {@code datediff} take {@code y M w d h
 * m s T u} (T milliseconds, u microseconds); month steps clamp the day to the month's end; {@code datediff} is the
 * absolute difference, truncated. {@code timestamp_floor} takes an optional stride and {@code M y w d h m s T U},
 * aligned to the epoch; {@code date_trunc} takes the unit's name. A NULL operand gives NULL. Units are constants.
 */
final class TimeFns {

    private TimeFns() {
    }

    static VectorFn dateAdd(final List<Bound> args, final CdbType type, final long nowMicros) {
        final char unit = unit(Functions.constantText(args.get(0), "dateadd"), "yMwdhmsTu", "dateadd");
        final VectorFn amounts = Cast.arg(args.get(1), CdbType.LONG, nowMicros);
        final VectorFn timestamps = Cast.arg(args.get(2), CdbType.TIMESTAMP, nowMicros);
        final Vector out = Vector.of(CdbType.TIMESTAMP, Page.CAPACITY);
        return Cast.exact(page -> {
            final long[] amount = amounts.eval(page).longs;
            final long[] micros = timestamps.eval(page).longs;
            for (int i = 0, n = page.size; i < n; i++) {
                if (amount[i] == Vector.NULL_LONG || micros[i] == Vector.NULL_LONG) {
                    out.longs[i] = Vector.NULL_LONG;
                } else {
                    out.longs[i] = switch (unit) {
                        case 'y' -> Civil.addMonths(micros[i], amount[i] * 12);
                        case 'M' -> Civil.addMonths(micros[i], amount[i]);
                        default -> micros[i] + amount[i] * fixedMicros(unit);
                    };
                }
            }
            return out;
        }, CdbType.TIMESTAMP, type);
    }

    static VectorFn dateDiff(final List<Bound> args, final CdbType type, final long nowMicros) {
        final char unit = unit(Functions.constantText(args.get(0), "datediff"), "yMwdhmsTu", "datediff");
        final VectorFn left = Cast.arg(args.get(1), CdbType.TIMESTAMP, nowMicros);
        final VectorFn right = Cast.arg(args.get(2), CdbType.TIMESTAMP, nowMicros);
        final Vector out = Vector.of(CdbType.LONG, Page.CAPACITY);
        return Cast.exact(page -> {
            final long[] a = left.eval(page).longs;
            final long[] b = right.eval(page).longs;
            for (int i = 0, n = page.size; i < n; i++) {
                if (a[i] == Vector.NULL_LONG || b[i] == Vector.NULL_LONG) {
                    out.longs[i] = Vector.NULL_LONG;
                } else {
                    out.longs[i] = switch (unit) {
                        case 'y' -> Civil.monthsBetween(a[i], b[i]) / 12;
                        case 'M' -> Civil.monthsBetween(a[i], b[i]);
                        default -> Math.abs(a[i] - b[i]) / fixedMicros(unit);
                    };
                }
            }
            return out;
        }, CdbType.LONG, type);
    }

    static VectorFn timestampFloor(final List<Bound> args, final CdbType type, final long nowMicros) {
        final String spec = Functions.constantText(args.get(0), "timestamp_floor");
        final long stride = stride(spec);
        final char unit = unit(spec.isEmpty() ? spec : spec.substring(spec.length() - 1), "MywdhmsTU", "timestamp_floor");
        return floor(Cast.arg(args.get(1), CdbType.TIMESTAMP, nowMicros), unit, stride, type);
    }

    static VectorFn dateTrunc(final List<Bound> args, final CdbType type, final long nowMicros) {
        final String name = Functions.constantText(args.get(0), "date_trunc");
        final VectorFn timestamps = Cast.arg(args.get(1), CdbType.TIMESTAMP, nowMicros);
        return switch (name) {
            case "microsecond" -> floor(timestamps, 'U', 1, type);
            case "millisecond" -> floor(timestamps, 'T', 1, type);
            case "second" -> floor(timestamps, 's', 1, type);
            case "minute" -> floor(timestamps, 'm', 1, type);
            case "hour" -> floor(timestamps, 'h', 1, type);
            case "day" -> floor(timestamps, 'd', 1, type);
            case "week" -> floor(timestamps, 'w', 1, type);
            case "month" -> floor(timestamps, 'M', 1, type);
            case "quarter" -> floor(timestamps, 'M', 3, type);
            case "year" -> floor(timestamps, 'y', 1, type);
            default -> throw new CdbException("unknown date_trunc unit [unit=" + name + "]", -1);
        };
    }

    static VectorFn hour(final Bound arg, final CdbType type, final long nowMicros) {
        final VectorFn timestamps = Cast.arg(arg, CdbType.TIMESTAMP, nowMicros);
        final Vector out = Vector.of(CdbType.LONG, Page.CAPACITY);
        return Cast.exact(page -> {
            final long[] micros = timestamps.eval(page).longs;
            for (int i = 0, n = page.size; i < n; i++) {
                out.longs[i] = micros[i] == Vector.NULL_LONG
                        ? Vector.NULL_LONG
                        : Math.floorMod(micros[i], Civil.DAY_MICROS) / Civil.HOUR_MICROS;
            }
            return out;
        }, CdbType.LONG, type);
    }

    static VectorFn now(final CdbType type, final long nowMicros) {
        final Vector out = Vector.of(CdbType.TIMESTAMP, Page.CAPACITY);
        for (int i = 0; i < Page.CAPACITY; i++) {
            out.longs[i] = nowMicros;
        }
        return Cast.exact(_ -> out, CdbType.TIMESTAMP, type);
    }

    private static VectorFn floor(final VectorFn timestamps, final char unit, final long stride, final CdbType type) {
        final Vector out = Vector.of(CdbType.TIMESTAMP, Page.CAPACITY);
        return Cast.exact(page -> {
            final long[] micros = timestamps.eval(page).longs;
            for (int i = 0, n = page.size; i < n; i++) {
                final long t = micros[i];
                if (t == Vector.NULL_LONG) {
                    out.longs[i] = Vector.NULL_LONG;
                } else {
                    out.longs[i] = switch (unit) {
                        case 'M' -> Civil.floorMonths(t, stride);
                        case 'y' -> Civil.floorYears(t, stride);
                        case 'w' -> Civil.floorWeeks(t, stride);
                        default -> Civil.floorFixed(t, stride * fixedMicros(unit));
                    };
                }
            }
            return out;
        }, CdbType.TIMESTAMP, type);
    }

    private static long fixedMicros(final char unit) {
        return switch (unit) {
            case 'w' -> Civil.WEEK_MICROS;
            case 'd' -> Civil.DAY_MICROS;
            case 'h' -> Civil.HOUR_MICROS;
            case 'm' -> Civil.MINUTE_MICROS;
            case 's' -> Civil.SECOND_MICROS;
            case 'T' -> Civil.MILLI_MICROS;
            default -> 1;
        };
    }

    /** The stride before the unit letter of a {@code timestamp_floor} unit: {@code 15m} is 15, {@code m} is 1. */
    private static long stride(final String spec) {
        if (spec.length() == 1) {
            return 1;
        }
        long stride = 0;
        for (int i = 0; i < spec.length() - 1; i++) {
            final char c = spec.charAt(i);
            if (c < '0' || c > '9' || stride > Integer.MAX_VALUE) {
                throw new CdbException("invalid timestamp_floor unit [unit=" + spec + "]", -1);
            }
            stride = stride * 10 + c - '0';
        }
        if (stride == 0) {
            throw new CdbException("timestamp_floor stride must be positive [unit=" + spec + "]", -1);
        }
        return stride;
    }

    private static char unit(final String unit, final String allowed, final String function) {
        if (unit.length() != 1 || allowed.indexOf(unit.charAt(0)) < 0) {
            throw new CdbException("unknown time unit [function=" + function + ", unit=" + unit + "]", -1);
        }
        return unit.charAt(0);
    }
}
