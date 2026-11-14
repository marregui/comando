package dev.marregui.comando.cdb.plan;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.sql.Ast;

/**
 * Output column names as QuestDB derives them from the expression text (read live from {@code /exec}, 2026-11-14):
 * {@code count()}, {@code sum(x)}, {@code x + 1}, {@code round(sum(x), 2)}, {@code x::DOUBLE}, {@code -(x)},
 * {@code not(x > 1)}, {@code concat('a', x, 'b')} for {@code ||}, {@code x = NULL} for {@code IS NULL},
 * {@code switch(x, 1, 'a', 'b')} for a CASE with an operand; a lone column keeps its name as written, without its
 * qualifier. Numbers print as Java prints them ({@code 1.6e6} as {@code 1600000.0}), where QuestDB keeps the text.
 */
final class Names {

    private Names() {
    }

    /** The name QuestDB gives an unaliased select item. */
    static String of(final Ast.Expr expr) {
        if (expr instanceof Ast.Ident(final List<String> parts, final int _)) {
            return parts.getLast();
        }
        return text(expr, false);
    }

    /** The expression's text, identifiers lower-cased when {@code canonical}: equal texts are the same expression. */
    static String text(final Ast.Expr expr, final boolean canonical) {
        final StringBuilder sink = new StringBuilder();
        put(sink, expr, canonical);
        return sink.toString();
    }

    /**
     * {@code names} made unique as QuestDB makes them: a repeated derived name gets {@code _2}, {@code _3}; a repeated
     * alias is an error. {@code aliased[i]} says whether name {@code i} was written as an alias.
     */
    static List<String> unique(final List<String> names, final boolean[] aliased, final int pos) {
        final Set<String> seen = new HashSet<>();
        final List<String> out = new ArrayList<>(names.size());
        for (int i = 0; i < names.size(); i++) {
            final String name = names.get(i);
            if (seen.add(name.toLowerCase(Locale.ROOT))) {
                out.add(name);
                continue;
            }
            if (aliased[i]) {
                throw new CdbException("cannot repeat column name [name=" + name + ']', pos);
            }
            int n = 2;
            while (!seen.add((name + '_' + n).toLowerCase(Locale.ROOT))) {
                n++;
            }
            out.add(name + '_' + n);
        }
        return out;
    }

    private static void put(final StringBuilder sink, final Ast.Expr expr, final boolean canonical) {
        switch (expr) {
            case Ast.Literal(final Object value, final int _) -> literal(sink, value);
            case Ast.Ident(final List<String> parts, final int _) -> {
                final String joined = String.join(".", parts);
                sink.append(canonical ? joined.toLowerCase(Locale.ROOT) : joined);
            }
            case Ast.Star(final String qualifier, final int _) ->
                    sink.append(qualifier == null ? "*" : qualifier + ".*");
            case Ast.Unary(final String op, final Ast.Expr operand, final int _) -> {
                sink.append(op).append('(');
                put(sink, operand, canonical);
                sink.append(')');
            }
            case Ast.Binary(final String op, final Ast.Expr left, final Ast.Expr right, final int _) ->
                    binary(sink, op, left, right, canonical);
            case Ast.Call(final String name, final List<Ast.Expr> args, final boolean distinct,
                          final boolean star, final Ast.Over _, final int _) -> {
                sink.append(distinct && "count".equals(name) ? "count_distinct" : name).append('(');
                if (!star) {
                    list(sink, args, canonical);
                }
                sink.append(')');
            }
            case Ast.Cast(final Ast.Expr operand, final String type, final int _) -> {
                operand(sink, operand, Integer.MAX_VALUE, canonical);
                sink.append("::").append(type);
            }
            case Ast.Case(final Ast.Expr operand, final List<Ast.When> whens, final Ast.Expr otherwise, final int _) ->
                    caseOf(sink, operand, whens, otherwise, canonical);
            case Ast.In(final Ast.Expr operand, final List<Ast.Expr> values, final Ast.QueryBody _,
                        final boolean not, final int _) -> {
                negated(sink, not);
                operand(sink, operand, precedence("="), canonical);
                sink.append(" in (");
                list(sink, values, canonical);
                sink.append(')');
                closeNegated(sink, not);
            }
            case Ast.Between(final Ast.Expr operand, final Ast.Expr low, final Ast.Expr high, final boolean not,
                             final int _) -> {
                negated(sink, not);
                operand(sink, operand, precedence("="), canonical);
                sink.append(" between (");
                put(sink, low, canonical);
                sink.append(", ");
                put(sink, high, canonical);
                sink.append(')');
                closeNegated(sink, not);
            }
            case Ast.IsNull(final Ast.Expr operand, final boolean not, final int _) -> {
                operand(sink, operand, precedence("="), canonical);
                sink.append(not ? " != NULL" : " = NULL");
            }
            case Ast.Like(final Ast.Expr operand, final Ast.Expr pattern, final boolean not,
                          final boolean caseInsensitive, final int _) -> {
                negated(sink, not);
                operand(sink, operand, precedence("="), canonical);
                sink.append(caseInsensitive ? " ilike " : " like ");
                operand(sink, pattern, precedence("="), canonical);
                closeNegated(sink, not);
            }
            case Ast.ScalarQuery _ -> sink.append("(select)");
        }
    }

    private static void literal(final StringBuilder sink, final Object value) {
        switch (value) {
            case null -> sink.append("NULL");
            case String s -> sink.append('\'').append(s.replace("'", "''")).append('\'');
            case Long l when l < 0 -> sink.append("-(").append(-l).append(')');
            case Double d when d < 0 -> sink.append("-(").append(-d).append(')');
            default -> sink.append(value);
        }
    }

    /** {@code ||} chains print as one {@code concat(…)}, as QuestDB prints them. */
    private static void binary(
            final StringBuilder sink,
            final String op,
            final Ast.Expr left,
            final Ast.Expr right,
            final boolean canonical
    ) {
        if ("||".equals(op)) {
            final List<Ast.Expr> parts = new ArrayList<>();
            flattenConcat(left, parts);
            flattenConcat(right, parts);
            sink.append("concat(");
            list(sink, parts, canonical);
            sink.append(')');
            return;
        }
        final int p = precedence(op);
        operand(sink, left, p, canonical);
        sink.append(' ').append(op).append(' ');
        operand(sink, right, p + 1, canonical);
    }

    private static void flattenConcat(final Ast.Expr expr, final List<Ast.Expr> parts) {
        if (expr instanceof Ast.Binary(final String op, final Ast.Expr l, final Ast.Expr r, final int _)
                && "||".equals(op)) {
            flattenConcat(l, parts);
            flattenConcat(r, parts);
        } else {
            parts.add(expr);
        }
    }

    /** {@code expr} in parentheses when it binds looser than {@code min}. */
    private static void operand(final StringBuilder sink, final Ast.Expr expr, final int min, final boolean canonical) {
        final boolean parens = expr instanceof Ast.Binary(final String op, final Ast.Expr _, final Ast.Expr _,
                final int _) && !"||".equals(op) && precedence(op) < min;
        if (parens) {
            sink.append('(');
        }
        put(sink, expr, canonical);
        if (parens) {
            sink.append(')');
        }
    }

    private static void caseOf(
            final StringBuilder sink,
            final Ast.Expr operand,
            final List<Ast.When> whens,
            final Ast.Expr otherwise,
            final boolean canonical
    ) {
        if (operand != null) {
            sink.append("switch(");
            put(sink, operand, canonical);
            for (final Ast.When w : whens) {
                sink.append(", ");
                put(sink, w.condition(), canonical);
                sink.append(", ");
                put(sink, w.value(), canonical);
            }
            if (otherwise != null) {
                sink.append(", ");
                put(sink, otherwise, canonical);
            }
            sink.append(')');
            return;
        }
        sink.append("case");
        for (final Ast.When w : whens) {
            sink.append(" when ");
            put(sink, w.condition(), canonical);
            sink.append(" then ");
            put(sink, w.value(), canonical);
        }
        if (otherwise != null) {
            sink.append(" else ");
            put(sink, otherwise, canonical);
        }
        sink.append(" end");
    }

    private static void list(final StringBuilder sink, final List<Ast.Expr> exprs, final boolean canonical) {
        for (int i = 0; i < exprs.size(); i++) {
            if (i > 0) {
                sink.append(", ");
            }
            put(sink, exprs.get(i), canonical);
        }
    }

    private static void negated(final StringBuilder sink, final boolean not) {
        if (not) {
            sink.append("not(");
        }
    }

    private static void closeNegated(final StringBuilder sink, final boolean not) {
        if (not) {
            sink.append(')');
        }
    }

    /** The parser's precedence, loosest first: OR, AND, comparison, {@code + -}, {@code * / %}. */
    private static int precedence(final String op) {
        return switch (op) {
            case "or" -> 1;
            case "and" -> 2;
            case "=", "!=", "<", "<=", ">", ">=" -> 4;
            case "+", "-" -> 6;
            case "*", "/", "%" -> 7;
            default -> 5;
        };
    }
}
