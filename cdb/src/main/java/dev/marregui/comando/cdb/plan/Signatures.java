package dev.marregui.comando.cdb.plan;

import java.util.List;
import java.util.Set;

import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;

/**
 * The result type of every scalar function, operator and aggregate, as QuestDB types them ({@code count} LONG,
 * {@code sum} of LONG LONG, {@code avg} DOUBLE, {@code dateadd} TIMESTAMP and so on): the planner's one source of
 * types, which the functions and aggregates follow.
 *
 * <p>QuestDB's INT, SHORT and BYTE results are LONG here, its CHAR and STRING results VARCHAR (the types of
 * {@link CdbType}). A window call is typed by {@link #windowType}: QuestDB types an aggregate with {@code OVER} apart
 * from the same aggregate in a {@code GROUP BY} ({@code sum(x) OVER ()} is DOUBLE, {@code sum(x)} LONG).
 */
public final class Signatures {

    private static final Set<String> AGGREGATES = Set.of(
            "count", "count_distinct", "sum", "avg", "min", "max", "first", "last",
            "approx_percentile", "approx_median", "percentile_disc"
    );
    private static final Set<String> COMPARISONS = Set.of("=", "!=", "<", "<=", ">", ">=");
    private static final Set<String> WINDOW_ONLY = Set.of("lag", "lead", "row_number", "first_value", "last_value");

    private Signatures() {
    }

    /** True when {@code function} is an aggregate. */
    public static boolean isAggregate(final String function) {
        return AGGREGATES.contains(function);
    }

    /**
     * The type of {@code function} over arguments of {@code args}; a {@link dev.marregui.comando.cdb.CdbException} for
     * an unknown function or arguments it does not take.
     */
    public static CdbType returnType(final String function, final List<CdbType> args) {
        return switch (function) {
            case "+", "-" -> additive(function, args);
            case "*", "/", "%" -> numeric(function, args, 2);
            case "neg" -> negation(function, args);
            case "||", "concat" -> concat(function, args);
            case "=", "!=", "<", "<=", ">", ">=" -> comparison(function, args);
            case "and", "or" -> logical(function, args, 2);
            case "not" -> logical(function, args, 1);
            case "in", "not_in" -> in(function, args);
            case "between" -> between(function, args);
            case "is_null", "is_not_null" -> unary(function, args, CdbType.BOOLEAN);
            case "like", "ilike" -> like(function, args);
            case "case" -> caseOf(function, args);
            case "count", "count_distinct" -> CdbType.LONG;
            case "sum" -> numeric(function, args, 1);
            case "avg", "approx_median" -> numericTo(function, args, CdbType.DOUBLE);
            case "min", "max" -> minMax(function, args);
            case "first", "last" -> unary(function, args, null);
            case "approx_percentile" -> approxPercentile(function, args);
            case "percentile_disc" -> percentileDisc(function, args);
            case "now" -> arity(function, args, 0, CdbType.TIMESTAMP);
            case "dateadd" -> dateadd(function, args);
            case "datediff" -> datediff(function, args);
            case "timestamp_floor", "date_trunc" -> unitAndTimestamp(function, args);
            case "to_str" -> toStr(function, args);
            case "hour" -> hour(function, args);
            case "round" -> round(function, args);
            case "substring" -> substring(function, args);
            case "replace" -> replace(function, args);
            case "lag", "lead", "first_value", "last_value" -> windowValue(function, args);
            case "row_number" -> arity(function, args, 0, CdbType.LONG);
            default -> throw new CdbException(
                    "cannot type unknown function [function=" + function + ", args=" + args + ']', -1);
        };
    }

    /** True when {@code function} is a comparison, {@code = != < <= > >=}. */
    static boolean isComparison(final String function) {
        return COMPARISONS.contains(function);
    }

    /** True when {@code function} is valid only with {@code OVER}. */
    static boolean isWindowOnly(final String function) {
        return WINDOW_ONLY.contains(function);
    }

    /**
     * The type of {@code function} called with {@code OVER}: {@code sum} and {@code avg} DOUBLE, {@code count} and
     * {@code row_number} LONG, {@code min}, {@code max}, {@code lag} and the value functions their argument's type.
     */
    static CdbType windowType(final String function, final List<CdbType> args) {
        return switch (function) {
            case "sum", "avg" -> numericTo(function, args, CdbType.DOUBLE);
            case "count", "row_number" -> CdbType.LONG;
            case "min", "max", "first", "last" -> unary(function, args, null);
            case "lag", "lead", "first_value", "last_value" -> windowValue(function, args);
            default -> throw new CdbException("cannot type unknown window function [function=" + function + ']', -1);
        };
    }

    /**
     * The common type of two values of a set operation's column or a CASE's branches, as QuestDB widens them (LONG and
     * DOUBLE to DOUBLE, SYMBOL and VARCHAR to VARCHAR); null when they have none.
     */
    static CdbType unify(final CdbType a, final CdbType b) {
        if (a == b) {
            return a;
        }
        if (isNumeric(a) && isNumeric(b)) {
            return CdbType.DOUBLE;
        }
        if (a.isText() && b.isText()) {
            return CdbType.VARCHAR;
        }
        return null;
    }

    /** TIMESTAMP - TIMESTAMP is LONG, TIMESTAMP ± LONG is TIMESTAMP, as in QuestDB. */
    private static CdbType additive(final String function, final List<CdbType> args) {
        expect(function, args, 2);
        final CdbType l = args.getFirst();
        final CdbType r = args.getLast();
        if (l == CdbType.TIMESTAMP && r == CdbType.TIMESTAMP && "-".equals(function)) {
            return CdbType.LONG;
        }
        if (l == CdbType.TIMESTAMP && r == CdbType.LONG) {
            return CdbType.TIMESTAMP;
        }
        if (l == CdbType.LONG && r == CdbType.TIMESTAMP && "+".equals(function)) {
            return CdbType.TIMESTAMP;
        }
        return numeric(function, args, 2);
    }

    private static CdbType numeric(final String function, final List<CdbType> args, final int arity) {
        expect(function, args, arity);
        boolean dbl = false;
        for (final CdbType t : args) {
            if (!isNumeric(t)) {
                throw mismatch(function, args);
            }
            dbl |= t == CdbType.DOUBLE;
        }
        return dbl ? CdbType.DOUBLE : CdbType.LONG;
    }

    private static CdbType numericTo(final String function, final List<CdbType> args, final CdbType out) {
        numeric(function, args, 1);
        return out;
    }

    private static CdbType negation(final String function, final List<CdbType> args) {
        expect(function, args, 1);
        return numeric(function, args, 1);
    }

    private static CdbType concat(final String function, final List<CdbType> args) {
        if (args.isEmpty()) {
            throw mismatch(function, args);
        }
        return CdbType.VARCHAR;
    }

    private static CdbType comparison(final String function, final List<CdbType> args) {
        expect(function, args, 2);
        if (!comparable(args.getFirst(), args.getLast())) {
            throw mismatch(function, args);
        }
        return CdbType.BOOLEAN;
    }

    private static CdbType logical(final String function, final List<CdbType> args, final int arity) {
        expect(function, args, arity);
        for (final CdbType t : args) {
            if (t != CdbType.BOOLEAN) {
                throw mismatch(function, args);
            }
        }
        return CdbType.BOOLEAN;
    }

    private static CdbType in(final String function, final List<CdbType> args) {
        if (args.size() < 2) {
            throw mismatch(function, args);
        }
        for (int i = 1; i < args.size(); i++) {
            if (!comparable(args.getFirst(), args.get(i))) {
                throw mismatch(function, args);
            }
        }
        return CdbType.BOOLEAN;
    }

    private static CdbType between(final String function, final List<CdbType> args) {
        expect(function, args, 3);
        if (!comparable(args.get(0), args.get(1)) || !comparable(args.get(0), args.get(2))) {
            throw mismatch(function, args);
        }
        return CdbType.BOOLEAN;
    }

    private static CdbType like(final String function, final List<CdbType> args) {
        expect(function, args, 2);
        if (!args.getFirst().isText() || !args.getLast().isText()) {
            throw mismatch(function, args);
        }
        return CdbType.BOOLEAN;
    }

    /** Arguments {@code cond1, value1, …[, else]}: the values' common type. */
    private static CdbType caseOf(final String function, final List<CdbType> args) {
        if (args.size() < 2) {
            throw mismatch(function, args);
        }
        CdbType out = null;
        for (int i = 0; i < args.size(); i++) {
            final boolean condition = i % 2 == 0 && i + 1 < args.size();
            if (condition) {
                if (args.get(i) != CdbType.BOOLEAN) {
                    throw mismatch(function, args);
                }
                continue;
            }
            out = out == null ? args.get(i) : unify(out, args.get(i));
            if (out == null) {
                throw mismatch(function, args);
            }
        }
        return out;
    }

    /** QuestDB's {@code min} and {@code max} of text are STRING, of SYMBOL too. */
    private static CdbType minMax(final String function, final List<CdbType> args) {
        expect(function, args, 1);
        final CdbType t = args.getFirst();
        if (t == CdbType.BOOLEAN) {
            throw mismatch(function, args);
        }
        return t.isText() ? CdbType.VARCHAR : t;
    }

    private static CdbType approxPercentile(final String function, final List<CdbType> args) {
        if (args.size() < 2 || args.size() > 3) {
            throw mismatch(function, args);
        }
        numeric(function, args, args.size());
        return CdbType.DOUBLE;
    }

    /** The corpus writes {@code percentile_disc(x, p)}: the type of {@code x}. */
    private static CdbType percentileDisc(final String function, final List<CdbType> args) {
        numeric(function, args, 2);
        return args.getFirst();
    }

    private static CdbType dateadd(final String function, final List<CdbType> args) {
        expect(function, args, 3);
        if (!args.get(0).isText() || args.get(1) != CdbType.LONG || args.get(2) != CdbType.TIMESTAMP) {
            throw mismatch(function, args);
        }
        return CdbType.TIMESTAMP;
    }

    private static CdbType datediff(final String function, final List<CdbType> args) {
        expect(function, args, 3);
        if (!args.get(0).isText() || args.get(1) != CdbType.TIMESTAMP || args.get(2) != CdbType.TIMESTAMP) {
            throw mismatch(function, args);
        }
        return CdbType.LONG;
    }

    private static CdbType unitAndTimestamp(final String function, final List<CdbType> args) {
        expect(function, args, 2);
        if (!args.getFirst().isText() || args.getLast() != CdbType.TIMESTAMP) {
            throw mismatch(function, args);
        }
        return CdbType.TIMESTAMP;
    }

    private static CdbType toStr(final String function, final List<CdbType> args) {
        expect(function, args, 2);
        if (args.getFirst() != CdbType.TIMESTAMP || !args.getLast().isText()) {
            throw mismatch(function, args);
        }
        return CdbType.VARCHAR;
    }

    private static CdbType hour(final String function, final List<CdbType> args) {
        expect(function, args, 1);
        if (args.getFirst() != CdbType.TIMESTAMP) {
            throw mismatch(function, args);
        }
        return CdbType.LONG;
    }

    /** QuestDB's {@code round} is DOUBLE whatever its argument. */
    private static CdbType round(final String function, final List<CdbType> args) {
        if (args.isEmpty() || args.size() > 2 || args.size() == 2 && args.getLast() != CdbType.LONG) {
            throw mismatch(function, args);
        }
        numeric(function, args.subList(0, 1), 1);
        return CdbType.DOUBLE;
    }

    private static CdbType substring(final String function, final List<CdbType> args) {
        expect(function, args, 3);
        if (!args.get(0).isText() || args.get(1) != CdbType.LONG || args.get(2) != CdbType.LONG) {
            throw mismatch(function, args);
        }
        return CdbType.VARCHAR;
    }

    private static CdbType replace(final String function, final List<CdbType> args) {
        expect(function, args, 3);
        for (final CdbType t : args) {
            if (!t.isText()) {
                throw mismatch(function, args);
            }
        }
        return CdbType.VARCHAR;
    }

    /** {@code lag(x[, offset[, default]])} and the value functions: the type of {@code x}. */
    private static CdbType windowValue(final String function, final List<CdbType> args) {
        if (args.isEmpty() || args.size() > 3 || args.size() >= 2 && args.get(1) != CdbType.LONG) {
            throw mismatch(function, args);
        }
        return args.getFirst();
    }

    /** {@code out} null: the argument's own type. */
    private static CdbType unary(final String function, final List<CdbType> args, final CdbType out) {
        expect(function, args, 1);
        return out == null ? args.getFirst() : out;
    }

    private static CdbType arity(final String function, final List<CdbType> args, final int arity, final CdbType out) {
        expect(function, args, arity);
        return out;
    }

    /** Equal types, two numbers, two texts, or a TIMESTAMP and a text, which is read as a TIMESTAMP. */
    private static boolean comparable(final CdbType a, final CdbType b) {
        return a == b || isNumeric(a) && isNumeric(b) || a.isText() && b.isText()
                || a == CdbType.TIMESTAMP && b.isText() || a.isText() && b == CdbType.TIMESTAMP;
    }

    private static boolean isNumeric(final CdbType t) {
        return t == CdbType.LONG || t == CdbType.DOUBLE;
    }

    private static void expect(final String function, final List<CdbType> args, final int arity) {
        if (args.size() != arity) {
            throw mismatch(function, args);
        }
    }

    private static CdbException mismatch(final String function, final List<CdbType> args) {
        return new CdbException("cannot apply function to argument types [function=" + function + ", args=" + args
                + ']', -1);
    }
}
