package dev.marregui.comando.cdb.sql;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import dev.marregui.comando.cdb.CdbException;

/**
 * Parses one SQL statement into its {@link Ast}, by recursive descent over the tokens of {@link Lexer}.
 *
 * <p>Keywords match in any case; a double-quoted identifier is never a keyword. A word in {@code RESERVED} is not
 * taken as a column, table or alias unless it is double-quoted, and is taken as a function name only when a
 * parenthesis follows it. Operator precedence, loosest first: {@code OR}, {@code AND}, {@code NOT}, the comparisons
 * with {@code IN}, {@code BETWEEN}, {@code LIKE}, {@code ILIKE} and {@code IS}, {@code ||}, {@code + -},
 * {@code * / %}, unary {@code -}, {@code ::}. Where QuestDB and Postgres meet, the result follows them:
 * <ul>
 *   <li>An {@code ORDER BY} or {@code LIMIT} after the last operand of {@code UNION}, {@code EXCEPT} or
 *   {@code INTERSECT} orders or limits the whole set operation; an operand that needs its own is parenthesised.
 *   {@code INTERSECT} binds tighter than {@code UNION} and {@code EXCEPT}.</li>
 *   <li>A statement or set operand that is a table name or a table function alone ({@code t LIMIT 5},
 *   {@code tables()}) is {@code SELECT * FROM} it.</li>
 *   <li>{@code x IN 'text'} without parentheses is QuestDB's interval form: an {@link Ast.In} with that one value.</li>
 *   <li>A minus sign directly before a number is part of the literal ({@code -5} is {@code Literal(-5)}), unless a
 *   {@code ::} follows the number, which binds tighter.</li>
 *   <li>A single-quoted from item, {@code FROM 'trades'}, is a table name, as QuestDB reads it.</li>
 *   <li>A from item's designated-timestamp clause, {@code (SELECT …) timestamp(ts)}, is read and dropped: the tree
 *   has no place for it.</li>
 * </ul>
 * {@code SHOW} gives {@link Ast.Show#what()} {@code tables}, {@code columns} or {@code create table}. {@code CREATE
 * TABLE} gives column types and the partition unit upper case; {@code FILL} values are lower-case words or numbers
 * as written. A failure is a {@link CdbException} whose position is the offending token's offset, or the text's
 * length when the statement ends early.
 */
public final class Parser {

    private static final Set<String> COMPARISONS = Set.of("=", "!=", "<>", "<", "<=", ">", ">=");
    private static final Set<String> NEGATABLE = Set.of("in", "between", "like", "ilike");
    private static final Set<String> RESERVED = Set.of(
            "all", "and", "as", "between", "by", "case", "cross", "distinct", "else", "end", "except", "false",
            "from", "full", "group", "having", "ilike", "in", "inner", "intersect", "is", "join", "latest", "left",
            "like", "limit", "not", "null", "on", "or", "order", "outer", "over", "right", "sample", "select", "then",
            "true", "union", "when", "where", "with");
    private static final Set<String> SAMPLE_UNITS = Set.of("U", "T", "s", "m", "h", "d", "M", "y");

    private final String sql;
    private final List<Lexer.Token> tokens;
    private int index;

    private Parser(final String sql) {
        this.sql = sql;
        this.tokens = Lexer.tokenize(sql);
    }

    /**
     * The statement {@code sql} holds, with an optional trailing semicolon.
     *
     * @throws CdbException when the text is not one statement of the grammar
     */
    public static Ast.Statement parse(final String sql) {
        final Parser parser = new Parser(sql);
        final Ast.Statement statement = parser.statement();
        parser.acceptSymbol(";");
        if (parser.peek().kind() != Lexer.Kind.END) {
            throw parser.unexpected("end of statement");
        }
        return statement;
    }

    private static int start(final Ast.Expr expr) {
        return switch (expr) {
            case Ast.Literal(_, final int pos) -> pos;
            case Ast.Ident(_, final int pos) -> pos;
            case Ast.Star(_, final int pos) -> pos;
            case Ast.Unary(_, _, final int pos) -> pos;
            case Ast.Binary(_, _, _, final int pos) -> pos;
            case Ast.Call(_, _, _, _, _, final int pos) -> pos;
            case Ast.Cast(_, _, final int pos) -> pos;
            case Ast.Case(_, _, _, final int pos) -> pos;
            case Ast.In(_, _, _, _, final int pos) -> pos;
            case Ast.Between(_, _, _, _, final int pos) -> pos;
            case Ast.IsNull(_, _, final int pos) -> pos;
            case Ast.Like(_, _, _, _, final int pos) -> pos;
            case Ast.ScalarQuery(_, final int pos) -> pos;
        };
    }

    /** {@code SELECT * FROM (body) ORDER BY … LIMIT …}, for a body that already has its own order or limit. */
    private static Ast.Select wrapped(
            final Ast.QueryBody body,
            final List<Ast.OrderItem> orderBy,
            final Ast.Limit limit,
            final int pos
    ) {
        final List<Ast.SelectItem> star = List.of(new Ast.SelectItem(new Ast.Star(null, pos), null));
        return new Ast.Select(false, star, new Ast.Subquery(body, null, pos), null, null, null, List.of(), orderBy,
                limit, pos);
    }

    private static String lower(final String text) {
        return text.toLowerCase(Locale.ROOT);
    }

    private static String upper(final String text) {
        return text.toUpperCase(Locale.ROOT);
    }

    /** {@code text} with every character outside printable ASCII written {@code U+XXXX}. */
    private static String printable(final String text) {
        final StringBuilder shown = new StringBuilder(text.length());
        for (int i = 0, n = text.length(); i < n; i++) {
            shown.append(Lexer.printable(text.charAt(i)));
        }
        return shown.toString();
    }

    private Ast.Statement statement() {
        if (isWord("create")) {
            return createTable();
        }
        if (isWord("update")) {
            return update();
        }
        if (isWord("show")) {
            return show();
        }
        return new Ast.Query(query());
    }

    private Ast.QueryBody query() {
        if (!acceptWord("with")) {
            return ordered();
        }
        final List<Ast.Cte> ctes = new ArrayList<>();
        do {
            final String name = name();
            expectWord("as");
            expectSymbol("(");
            final Ast.QueryBody body = query();
            expectSymbol(")");
            ctes.add(new Ast.Cte(name, body));
        } while (acceptSymbol(","));
        return new Ast.With(List.copyOf(ctes), ordered());
    }

    /** A set expression and the {@code ORDER BY} and {@code LIMIT} after it, which apply to the whole of it. */
    private Ast.QueryBody ordered() {
        final int pos = peek().start();
        final Ast.QueryBody body = union();
        final List<Ast.OrderItem> orderBy = acceptPair("order", "by") ? orderItems() : List.of();
        final Ast.Limit limit = acceptWord("limit") ? limit() : null;
        if (orderBy.isEmpty() && limit == null) {
            return body;
        }
        return switch (body) {
            case Ast.Select(
                    final boolean distinct,
                    final List<Ast.SelectItem> items,
                    final Ast.From from,
                    final Ast.Expr where,
                    final Ast.LatestOn latestOn,
                    final Ast.SampleBy sampleBy,
                    final List<Ast.Expr> groupBy,
                    final List<Ast.OrderItem> ownOrder,
                    final Ast.Limit ownLimit,
                    final int selectPos
            ) when ownOrder.isEmpty() && ownLimit == null ->
                    new Ast.Select(distinct, items, from, where, latestOn, sampleBy, groupBy, orderBy, limit,
                            selectPos);
            case Ast.SetOp(
                    final String op,
                    final Ast.QueryBody left,
                    final Ast.QueryBody right,
                    final List<Ast.OrderItem> ownOrder,
                    final Ast.Limit ownLimit,
                    final int setPos
            ) when ownOrder.isEmpty() && ownLimit == null -> new Ast.SetOp(op, left, right, orderBy, limit, setPos);
            case Ast.Select _, Ast.SetOp _, Ast.With _ -> wrapped(body, orderBy, limit, pos);
        };
    }

    private Ast.QueryBody union() {
        final int pos = peek().start();
        Ast.QueryBody left = intersect();
        while (isWord("union") || isWord("except")) {
            final String op = lower(next().text());
            final String kind = op.equals("union") && acceptWord("all") ? "union all" : op;
            left = new Ast.SetOp(kind, left, intersect(), List.of(), null, pos);
        }
        return left;
    }

    private Ast.QueryBody intersect() {
        final int pos = peek().start();
        Ast.QueryBody left = operand();
        while (acceptWord("intersect")) {
            left = new Ast.SetOp("intersect", left, operand(), List.of(), null, pos);
        }
        return left;
    }

    private Ast.QueryBody operand() {
        if (acceptSymbol("(")) {
            final Ast.QueryBody body = query();
            expectSymbol(")");
            return body;
        }
        if (isWord("select")) {
            return select();
        }
        if (isName()) {
            final int pos = peek().start();
            final List<Ast.SelectItem> star = List.of(new Ast.SelectItem(new Ast.Star(null, pos), null));
            return selectTail(false, star, from(), pos);
        }
        throw unexpected("SELECT");
    }

    private Ast.Select select() {
        final int pos = expectWord("select").start();
        final boolean distinct = acceptWord("distinct");
        final List<Ast.SelectItem> items = new ArrayList<>();
        do {
            final Ast.Expr expr = expression();
            items.add(new Ast.SelectItem(expr, alias()));
        } while (acceptSymbol(","));
        final Ast.From from = acceptWord("from") ? from() : null;
        return selectTail(distinct, List.copyOf(items), from, pos);
    }

    /** The clauses of a select after its {@code FROM}, up to its {@code GROUP BY}. */
    private Ast.Select selectTail(
            final boolean distinct,
            final List<Ast.SelectItem> items,
            final Ast.From from,
            final int pos
    ) {
        final Ast.Expr where = acceptWord("where") ? expression() : null;
        final Ast.LatestOn latestOn = acceptWord("latest") ? latestOn() : null;
        final Ast.SampleBy sampleBy = acceptWord("sample") ? sampleBy() : null;
        final List<Ast.Expr> groupBy = acceptPair("group", "by") ? expressions() : List.of();
        return new Ast.Select(distinct, items, from, where, latestOn, sampleBy, groupBy, List.of(), null, pos);
    }

    private Ast.From from() {
        final int pos = peek().start();
        Ast.From left = fromItem();
        String kind = joinKind();
        while (kind != null) {
            final Ast.From right = fromItem();
            final Ast.Expr on = kind.equals("cross") ? null : joinCondition();
            left = new Ast.Join(left, right, kind, on, pos);
            kind = joinKind();
        }
        return left;
    }

    private String joinKind() {
        if (acceptWord("join")) {
            return "inner";
        }
        if (acceptWord("inner")) {
            expectWord("join");
            return "inner";
        }
        if (acceptWord("left")) {
            acceptWord("outer");
            expectWord("join");
            return "left";
        }
        if (acceptWord("cross")) {
            expectWord("join");
            return "cross";
        }
        return null;
    }

    private Ast.Expr joinCondition() {
        expectWord("on");
        return expression();
    }

    private Ast.From fromItem() {
        final Lexer.Token first = peek();
        if (acceptSymbol("(")) {
            final Ast.QueryBody body = query();
            expectSymbol(")");
            return new Ast.Subquery(body, fromSuffix(), first.start());
        }
        if (first.kind() == Lexer.Kind.STRING) {
            return new Ast.TableName(next().text(), fromSuffix(), first.start());
        }
        final String name = name();
        if (first.kind() == Lexer.Kind.WORD && acceptSymbol("(")) {
            final List<Ast.Expr> args = isSymbol(")") ? List.of() : expressions();
            expectSymbol(")");
            return new Ast.TableFunction(lower(name), args, fromSuffix(), first.start());
        }
        return new Ast.TableName(name, fromSuffix(), first.start());
    }

    /** A from item's alias, with QuestDB's designated-timestamp clause before or after it read and dropped. */
    private String fromSuffix() {
        skipDesignatedTimestamp();
        final String alias = alias();
        skipDesignatedTimestamp();
        return alias;
    }

    private void skipDesignatedTimestamp() {
        if (isWord("timestamp") && isSymbolAt(1, "(")) {
            next();
            parenthesisedName();
        }
    }

    private String alias() {
        if (acceptWord("as")) {
            return anyName();
        }
        return isName() ? next().text() : null;
    }

    private Ast.LatestOn latestOn() {
        expectWord("on");
        final String timestamp = name();
        expectWord("partition");
        expectWord("by");
        return new Ast.LatestOn(timestamp, names());
    }

    private Ast.SampleBy sampleBy() {
        expectWord("by");
        final long count = integer(expect(Lexer.Kind.NUMBER, "sample count"));
        final Lexer.Token unit = peek();
        if (unit.kind() != Lexer.Kind.WORD || !SAMPLE_UNITS.contains(unit.text())) {
            throw unexpected("sample unit");
        }
        next();
        final List<String> fill = new ArrayList<>();
        if (acceptWord("fill")) {
            expectSymbol("(");
            do {
                fill.add(fillValue());
            } while (acceptSymbol(","));
            expectSymbol(")");
        }
        final boolean alignToCalendar = acceptWord("align") && alignment();
        return new Ast.SampleBy(count, unit.text(), List.copyOf(fill), alignToCalendar);
    }

    private String fillValue() {
        final Lexer.Token token = peek();
        if (token.kind() == Lexer.Kind.WORD) {
            return lower(next().text());
        }
        if (token.kind() == Lexer.Kind.NUMBER) {
            return next().text();
        }
        if (isSymbol("-") && peekAt(1).kind() == Lexer.Kind.NUMBER) {
            next();
            return "-" + next().text();
        }
        throw unexpected("fill value");
    }

    /** {@code TO CALENDAR} is true, {@code TO FIRST OBSERVATION} false. */
    private boolean alignment() {
        expectWord("to");
        if (acceptWord("calendar")) {
            return true;
        }
        expectWord("first");
        expectWord("observation");
        return false;
    }

    private List<Ast.OrderItem> orderItems() {
        final List<Ast.OrderItem> items = new ArrayList<>();
        do {
            final Ast.Expr expr = expression();
            final boolean descending = acceptWord("desc");
            if (!descending) {
                acceptWord("asc");
            }
            items.add(new Ast.OrderItem(expr, descending));
        } while (acceptSymbol(","));
        return List.copyOf(items);
    }

    private Ast.Limit limit() {
        final Ast.Expr lo = expression();
        final Ast.Expr hi = acceptSymbol(",") ? expression() : null;
        return new Ast.Limit(lo, hi);
    }

    private Ast.CreateTable createTable() {
        final int pos = expectWord("create").start();
        expectWord("table");
        final boolean ifNotExists = acceptWord("if");
        if (ifNotExists) {
            expectWord("not");
            expectWord("exists");
        }
        final String name = name();
        expectSymbol("(");
        final List<Ast.ColumnSpec> columns = new ArrayList<>();
        do {
            columns.add(columnSpec());
        } while (acceptSymbol(","));
        expectSymbol(")");
        final String timestamp = acceptWord("timestamp") ? parenthesisedName() : null;
        final String partitionBy = acceptPair("partition", "by") ? upper(expect(Lexer.Kind.WORD, "partition unit")
                .text()) : null;
        final String ttl = acceptWord("ttl") ? ttl() : null;
        if (acceptWord("bypass")) {
            expectWord("wal");
        } else {
            acceptWord("wal");
        }
        final List<String> dedupKeys = acceptWord("dedup") ? dedupKeys() : List.of();
        return new Ast.CreateTable(name, ifNotExists, List.copyOf(columns), timestamp, partitionBy, ttl, dedupKeys,
                pos);
    }

    /** A column's name and type; {@code CAPACITY n}, {@code CACHE}, {@code NOCACHE} and {@code INDEX} are dropped. */
    private Ast.ColumnSpec columnSpec() {
        final String name = name();
        final String type = upper(expect(Lexer.Kind.WORD, "column type").text());
        boolean reading = true;
        while (reading) {
            if (acceptWord("capacity")) {
                expect(Lexer.Kind.NUMBER, "capacity");
            } else if (acceptWord("index")) {
                if (acceptWord("capacity")) {
                    expect(Lexer.Kind.NUMBER, "capacity");
                }
            } else {
                reading = acceptWord("cache") || acceptWord("nocache");
            }
        }
        return new Ast.ColumnSpec(name, type);
    }

    /** The {@code TTL} as written, from its number to the end of its unit ({@code 30 DAYS}, {@code 2w}). */
    private String ttl() {
        final Lexer.Token count = expect(Lexer.Kind.NUMBER, "ttl count");
        final Lexer.Token unit = expect(Lexer.Kind.WORD, "ttl unit");
        return sql.substring(count.start(), unit.end());
    }

    private List<String> dedupKeys() {
        expectWord("upsert");
        expectWord("keys");
        expectSymbol("(");
        final List<String> keys = names();
        expectSymbol(")");
        return keys;
    }

    private String parenthesisedName() {
        expectSymbol("(");
        final String name = name();
        expectSymbol(")");
        return name;
    }

    private Ast.Update update() {
        final int pos = expectWord("update").start();
        final String table = name();
        expectWord("set");
        final List<Ast.Assignment> assignments = new ArrayList<>();
        do {
            final String column = name();
            expectSymbol("=");
            assignments.add(new Ast.Assignment(column, expression()));
        } while (acceptSymbol(","));
        final Ast.Expr where = acceptWord("where") ? expression() : null;
        return new Ast.Update(table, List.copyOf(assignments), where, pos);
    }

    private Ast.Show show() {
        final int pos = expectWord("show").start();
        if (acceptWord("tables")) {
            return new Ast.Show("tables", null, pos);
        }
        if (acceptWord("columns")) {
            expectWord("from");
            return new Ast.Show("columns", name(), pos);
        }
        if (acceptWord("create")) {
            expectWord("table");
            return new Ast.Show("create table", name(), pos);
        }
        throw unexpected("TABLES, COLUMNS or CREATE");
    }

    private Ast.Expr expression() {
        Ast.Expr left = and();
        while (acceptWord("or")) {
            left = new Ast.Binary("or", left, and(), start(left));
        }
        return left;
    }

    private Ast.Expr and() {
        Ast.Expr left = not();
        while (acceptWord("and")) {
            left = new Ast.Binary("and", left, not(), start(left));
        }
        return left;
    }

    private Ast.Expr not() {
        if (isWord("not")) {
            final int pos = next().start();
            return new Ast.Unary("not", not(), pos);
        }
        return predicate();
    }

    /** Comparisons, {@code IS [NOT] NULL}, {@code [NOT] IN}, {@code [NOT] BETWEEN}, {@code [NOT] LIKE/ILIKE}. */
    private Ast.Expr predicate() {
        Ast.Expr left = concat();
        while (true) {
            final int pos = start(left);
            final Lexer.Token token = peek();
            if (token.kind() == Lexer.Kind.SYMBOL && COMPARISONS.contains(token.text())) {
                next();
                final String op = token.text().equals("<>") ? "!=" : token.text();
                left = new Ast.Binary(op, left, concat(), pos);
            } else if (acceptWord("is")) {
                final boolean not = acceptWord("not");
                expectWord("null");
                left = new Ast.IsNull(left, not, pos);
            } else {
                final boolean not = isWord("not") && isNegatable(1);
                if (!not && !isNegatable(0)) {
                    return left;
                }
                if (not) {
                    next();
                }
                left = negatable(left, not, pos);
            }
        }
    }

    private boolean isNegatable(final int ahead) {
        final Lexer.Token token = peekAt(ahead);
        return token.kind() == Lexer.Kind.WORD && NEGATABLE.contains(lower(token.text()));
    }

    private Ast.Expr negatable(final Ast.Expr operand, final boolean not, final int pos) {
        final String word = lower(next().text());
        if (word.equals("in")) {
            return in(operand, not, pos);
        }
        if (word.equals("between")) {
            final Ast.Expr low = concat();
            expectWord("and");
            return new Ast.Between(operand, low, concat(), not, pos);
        }
        return new Ast.Like(operand, concat(), not, word.equals("ilike"), pos);
    }

    private Ast.In in(final Ast.Expr operand, final boolean not, final int pos) {
        if (!acceptSymbol("(")) {
            return new Ast.In(operand, List.of(concat()), null, not, pos);
        }
        if (isWord("select") || isWord("with")) {
            final Ast.QueryBody query = query();
            expectSymbol(")");
            return new Ast.In(operand, List.of(), query, not, pos);
        }
        final List<Ast.Expr> values = expressions();
        expectSymbol(")");
        return new Ast.In(operand, values, null, not, pos);
    }

    private Ast.Expr concat() {
        Ast.Expr left = additive();
        while (acceptSymbol("||")) {
            left = new Ast.Binary("||", left, additive(), start(left));
        }
        return left;
    }

    private Ast.Expr additive() {
        Ast.Expr left = multiplicative();
        while (isSymbol("+") || isSymbol("-")) {
            final String op = next().text();
            left = new Ast.Binary(op, left, multiplicative(), start(left));
        }
        return left;
    }

    private Ast.Expr multiplicative() {
        Ast.Expr left = unary();
        while (isSymbol("*") || isSymbol("/") || isSymbol("%")) {
            final String op = next().text();
            left = new Ast.Binary(op, left, unary(), start(left));
        }
        return left;
    }

    private Ast.Expr unary() {
        if (!isSymbol("-")) {
            return cast();
        }
        final int pos = next().start();
        if (peek().kind() == Lexer.Kind.NUMBER && !isSymbolAt(1, "::")) {
            return number(next(), true, pos);
        }
        return new Ast.Unary("-", unary(), pos);
    }

    private Ast.Expr cast() {
        Ast.Expr expr = primary();
        while (acceptSymbol("::")) {
            expr = new Ast.Cast(expr, typeName(), start(expr));
        }
        return expr;
    }

    private Ast.Expr primary() {
        final Lexer.Token token = peek();
        return switch (token.kind()) {
            case NUMBER -> number(next(), false, token.start());
            case STRING -> new Ast.Literal(next().text(), token.start());
            case QUOTED -> identifier();
            case WORD -> word(token);
            case SYMBOL -> parenthesisedOrStar(token);
            case END -> throw unexpected("expression");
        };
    }

    private Ast.Expr parenthesisedOrStar(final Lexer.Token token) {
        if (acceptSymbol("*")) {
            return new Ast.Star(null, token.start());
        }
        if (!acceptSymbol("(")) {
            throw unexpected("expression");
        }
        if (isWord("select") || isWord("with")) {
            final Ast.QueryBody body = query();
            expectSymbol(")");
            return new Ast.ScalarQuery(body, token.start());
        }
        final Ast.Expr inner = expression();
        expectSymbol(")");
        return inner;
    }

    private Ast.Expr word(final Lexer.Token token) {
        final String word = lower(token.text());
        if (word.equals("null")) {
            next();
            return new Ast.Literal(null, token.start());
        }
        if (word.equals("true") || word.equals("false")) {
            next();
            return new Ast.Literal(word.equals("true"), token.start());
        }
        if (word.equals("case")) {
            return caseExpression();
        }
        if (isSymbolAt(1, "(")) {
            return word.equals("cast") ? castCall() : call();
        }
        if (RESERVED.contains(word)) {
            throw unexpected("expression");
        }
        return identifier();
    }

    private Ast.Expr identifier() {
        final int pos = peek().start();
        final List<String> parts = new ArrayList<>();
        parts.add(name());
        while (acceptSymbol(".")) {
            if (acceptSymbol("*")) {
                return new Ast.Star(String.join(".", parts), pos);
            }
            parts.add(anyName());
        }
        return new Ast.Ident(List.copyOf(parts), pos);
    }

    private Ast.Call call() {
        final Lexer.Token name = next();
        expectSymbol("(");
        final boolean star = acceptSymbol("*");
        final boolean distinct = !star && acceptWord("distinct");
        final List<Ast.Expr> args = star || isSymbol(")") ? List.of() : expressions();
        expectSymbol(")");
        final Ast.Over over = acceptWord("over") ? over() : null;
        return new Ast.Call(lower(name.text()), args, distinct, star, over, name.start());
    }

    private Ast.Over over() {
        expectSymbol("(");
        final List<Ast.Expr> partitionBy = acceptPair("partition", "by") ? expressions() : List.of();
        final List<Ast.OrderItem> orderBy = acceptPair("order", "by") ? orderItems() : List.of();
        expectSymbol(")");
        return new Ast.Over(partitionBy, orderBy);
    }

    private Ast.Cast castCall() {
        final int pos = next().start();
        expectSymbol("(");
        final Ast.Expr operand = expression();
        expectWord("as");
        final String type = typeName();
        expectSymbol(")");
        return new Ast.Cast(operand, type, pos);
    }

    private Ast.Case caseExpression() {
        final int pos = next().start();
        final Ast.Expr operand = isWord("when") ? null : expression();
        final List<Ast.When> whens = new ArrayList<>();
        while (acceptWord("when")) {
            final Ast.Expr condition = expression();
            expectWord("then");
            whens.add(new Ast.When(condition, expression()));
        }
        if (whens.isEmpty()) {
            throw unexpected("WHEN");
        }
        final Ast.Expr otherwise = acceptWord("else") ? expression() : null;
        expectWord("end");
        return new Ast.Case(operand, List.copyOf(whens), otherwise, pos);
    }

    private String typeName() {
        return upper(expect(Lexer.Kind.WORD, "type name").text());
    }

    private Ast.Literal number(final Lexer.Token token, final boolean negative, final int pos) {
        final String text = negative ? "-" + token.text() : token.text();
        if (token.text().indexOf('.') >= 0 || token.text().indexOf('e') >= 0 || token.text().indexOf('E') >= 0) {
            return new Ast.Literal(Double.parseDouble(text), pos);
        }
        try {
            return new Ast.Literal(Long.parseLong(text), pos);
        } catch (final NumberFormatException _) {
            throw new CdbException("number out of range [number=" + text + ", position=" + pos + "]", pos);
        }
    }

    private long integer(final Lexer.Token token) {
        if (!(number(token, false, token.start()).value() instanceof final Long value)) {
            throw new CdbException("expected an integer [found=" + token.text() + ", position=" + token.start()
                    + "]", token.start());
        }
        return value;
    }

    private List<Ast.Expr> expressions() {
        final List<Ast.Expr> list = new ArrayList<>();
        do {
            list.add(expression());
        } while (acceptSymbol(","));
        return List.copyOf(list);
    }

    private List<String> names() {
        final List<String> list = new ArrayList<>();
        do {
            list.add(name());
        } while (acceptSymbol(","));
        return List.copyOf(list);
    }

    /** True when the next token can be a column, table or alias name: quoted, or a word that is not reserved. */
    private boolean isName() {
        final Lexer.Token token = peek();
        return token.kind() == Lexer.Kind.QUOTED
                || token.kind() == Lexer.Kind.WORD && !RESERVED.contains(lower(token.text()));
    }

    private String name() {
        if (!isName()) {
            throw unexpected("name");
        }
        return next().text();
    }

    /** A name after {@code AS} or a dot, where a reserved word is a name too. */
    private String anyName() {
        final Lexer.Kind kind = peek().kind();
        if (kind != Lexer.Kind.WORD && kind != Lexer.Kind.QUOTED) {
            throw unexpected("name");
        }
        return next().text();
    }

    private Lexer.Token peek() {
        return tokens.get(index);
    }

    private Lexer.Token peekAt(final int ahead) {
        return tokens.get(Math.min(index + ahead, tokens.size() - 1));
    }

    private Lexer.Token next() {
        final Lexer.Token token = peek();
        if (token.kind() != Lexer.Kind.END) {
            index++;
        }
        return token;
    }

    private boolean isWord(final String word) {
        return isWordAt(0, word);
    }

    private boolean isWordAt(final int ahead, final String word) {
        final Lexer.Token token = peekAt(ahead);
        return token.kind() == Lexer.Kind.WORD && token.text().equalsIgnoreCase(word);
    }

    private boolean isSymbol(final String symbol) {
        return isSymbolAt(0, symbol);
    }

    private boolean isSymbolAt(final int ahead, final String symbol) {
        final Lexer.Token token = peekAt(ahead);
        return token.kind() == Lexer.Kind.SYMBOL && token.text().equals(symbol);
    }

    private boolean acceptWord(final String word) {
        if (!isWord(word)) {
            return false;
        }
        next();
        return true;
    }

    private boolean acceptSymbol(final String symbol) {
        if (!isSymbol(symbol)) {
            return false;
        }
        next();
        return true;
    }

    /** Reads {@code first second} when {@code first} is next; {@code first} alone is an error. */
    private boolean acceptPair(final String first, final String second) {
        if (!acceptWord(first)) {
            return false;
        }
        expectWord(second);
        return true;
    }

    private Lexer.Token expectWord(final String word) {
        if (!isWord(word)) {
            throw unexpected(upper(word));
        }
        return next();
    }

    private void expectSymbol(final String symbol) {
        if (!acceptSymbol(symbol)) {
            throw unexpected("'" + symbol + "'");
        }
    }

    private Lexer.Token expect(final Lexer.Kind kind, final String what) {
        if (peek().kind() != kind) {
            throw unexpected(what);
        }
        return next();
    }

    private CdbException unexpected(final String expected) {
        final Lexer.Token token = peek();
        if (token.kind() == Lexer.Kind.END) {
            return new CdbException("unexpected end of statement [expected=" + expected + ", position="
                    + token.start() + "]", token.start());
        }
        return new CdbException("unexpected token [expected=" + expected + ", found=" + printable(token.text())
                + ", position=" + token.start() + "]", token.start());
    }
}
