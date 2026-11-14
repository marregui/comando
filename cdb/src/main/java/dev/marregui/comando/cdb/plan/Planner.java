package dev.marregui.comando.cdb.plan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.catalog.Catalog;
import dev.marregui.comando.cdb.exec.Field;
import dev.marregui.comando.cdb.sql.Ast;
import dev.marregui.comando.cdb.store.ColumnDef;
import dev.marregui.comando.cdb.store.TableDef;

/**
 * Turns a parsed statement into a {@link Plan}: names resolved against the store's tables (without regard to case),
 * types checked through {@link Signatures}, table functions' fields from
 * {@link dev.marregui.comando.cdb.catalog.Catalog#fields}, the designated-timestamp filter turned into the
 * scan's partition bounds, output names as QuestDB names them ({@code count()}, {@code sum(x)}, {@code x + 1} for an
 * unnamed expression, read live from QuestDB).
 */
public final class Planner {

    private final Function<String, TableDef> tables;
    private final long nowMicros;
    private final BiFunction<String, List<String>, List<Field>> sourceFields;

    private Planner(
            final Function<String, TableDef> tables,
            final long nowMicros,
            final BiFunction<String, List<String>, List<Field>> sourceFields
    ) {
        this.tables = tables;
        this.nowMicros = nowMicros;
        this.sourceFields = sourceFields;
    }

    /**
     * The plan of {@code statement}; {@code tables} gives a table's definition by name, null when there is none;
     * {@code nowMicros} is {@code now()} for the whole statement.
     */
    public static Plan plan(final Ast.Statement statement, final Function<String, TableDef> tables, final long nowMicros) {
        return plan(statement, tables, nowMicros, Catalog::fields);
    }

    /** {@link #plan(Ast.Statement, Function, long)} with the table functions' fields from {@code sourceFields}. */
    static Plan plan(
            final Ast.Statement statement,
            final Function<String, TableDef> tables,
            final long nowMicros,
            final BiFunction<String, List<String>, List<Field>> sourceFields
    ) {
        final Planner planner = new Planner(tables, nowMicros, sourceFields);
        return switch (statement) {
            case Ast.Query(final Ast.QueryBody body) -> planner.query(body, Map.of()).plan();
            case Ast.CreateTable create -> Ddl.createTable(create, tables);
            case Ast.Update update -> planner.update(update);
            case Ast.Show show -> planner.show(show);
        };
    }

    Rel query(final Ast.QueryBody body, final Map<String, Ast.QueryBody> ctes) {
        return switch (body) {
            case Ast.Select select -> new SelectPlanner(this, select, ctes).plan();
            case Ast.SetOp setOp -> setOp(setOp, ctes);
            case Ast.With(final List<Ast.Cte> list, final Ast.QueryBody inner) -> {
                final Map<String, Ast.QueryBody> scope = new HashMap<>(ctes);
                for (final Ast.Cte cte : list) {
                    scope.put(cte.name().toLowerCase(Locale.ROOT), cte.body());
                }
                yield query(inner, scope);
            }
        };
    }

    /** What a FROM clause reads, each column qualified by its table's name or alias. */
    Rel from(final Ast.From from, final Map<String, Ast.QueryBody> ctes) {
        return switch (from) {
            case Ast.TableName(final String name, final String alias, final int pos) -> {
                final Ast.QueryBody cte = ctes.get(name.toLowerCase(Locale.ROOT));
                if (cte != null) {
                    yield query(cte, ctes).qualified(alias == null ? name : alias);
                }
                final TableDef def = tables.apply(name);
                if (def == null) {
                    throw new CdbException("cannot find table [table=" + name + ']', pos);
                }
                yield Rel.of(scan(def), alias == null ? def.name() : alias, def.timestampIndex());
            }
            case Ast.TableFunction(final String name, final List<Ast.Expr> args, final String alias, final int pos) -> {
                final List<String> texts = new ArrayList<>(args.size());
                for (final Ast.Expr a : args) {
                    if (!(a instanceof Ast.Literal(final Object value, final int _)) || value == null) {
                        throw new CdbException("cannot call table function with non-constant argument [function="
                                + name + ']', pos);
                    }
                    texts.add(value.toString());
                }
                final List<Field> fields = sourceFields.apply(name, texts);
                yield Rel.of(new Plan.Source(name, List.copyOf(texts), fields), alias == null ? name : alias, -1);
            }
            case Ast.Subquery(final Ast.QueryBody body, final String alias, final int _) ->
                    query(body, ctes).qualified(alias);
            case Ast.Join(final Ast.From left, final Ast.From right, final String kind, final Ast.Expr on,
                          final int pos) -> join(from(left, ctes), from(right, ctes), kind, on, pos);
        };
    }

    /** {@code t}'s rows, every column. */
    static Plan.Scan scan(final TableDef def) {
        final int[] columns = new int[def.columns().size()];
        final List<Field> fields = new ArrayList<>(columns.length);
        for (int i = 0; i < columns.length; i++) {
            columns[i] = i;
            final ColumnDef c = def.columns().get(i);
            fields.add(new Field(c.name(), c.type()));
        }
        return new Plan.Scan(def.name(), columns, Long.MIN_VALUE, Long.MAX_VALUE, List.copyOf(fields));
    }

    long nowMicros() {
        return nowMicros;
    }

    private static Rel join(final Rel left, final Rel right, final String kind, final Ast.Expr on, final int pos) {
        final List<Rel.Col> cols = new ArrayList<>(left.cols());
        cols.addAll(right.cols());
        final List<String> names = new ArrayList<>(cols.size());
        for (final Rel.Col c : cols) {
            names.add(c.name());
        }
        final List<String> unique = Names.unique(names, new boolean[names.size()], pos);
        final List<Field> fields = new ArrayList<>(cols.size());
        for (int i = 0; i < cols.size(); i++) {
            fields.add(new Field(unique.get(i), cols.get(i).type()));
        }
        final Rel both = new Rel(left.plan(), cols, -1);
        final Bound predicate = on == null ? null : new Binder(both, null).bindValue(on);
        if (predicate != null && predicate.type() != CdbType.BOOLEAN) {
            throw new CdbException("cannot join on non-boolean condition [kind=" + kind + ']', pos);
        }
        final Plan.Join join = new Plan.Join(left.plan(), right.plan(), kind, predicate, List.copyOf(fields));
        return new Rel(join, cols, left.timestamp());
    }

    /** Both sides' columns widened to a common type, the right cast where it differs; then ORDER BY and LIMIT. */
    private Rel setOp(final Ast.SetOp setOp, final Map<String, Ast.QueryBody> ctes) {
        final Rel left = query(setOp.left(), ctes);
        final Rel right = query(setOp.right(), ctes);
        if (left.width() != right.width()) {
            throw new CdbException("cannot combine queries of different widths [left=" + left.width() + ", right="
                    + right.width() + ']', setOp.pos());
        }
        final List<CdbType> types = new ArrayList<>(left.width());
        for (int i = 0; i < left.width(); i++) {
            final CdbType l = left.cols().get(i).type();
            final CdbType r = right.cols().get(i).type();
            final CdbType t = Signatures.unify(l, r);
            if (t == null) {
                throw new CdbException("cannot combine column types [column=" + left.cols().get(i).name() + ", left="
                        + l + ", right=" + r + ']', setOp.pos());
            }
            types.add(t);
        }
        final List<String> names = new ArrayList<>(left.width());
        for (final Field f : left.plan().fields()) {
            names.add(f.name());
        }
        final Plan combined = new Plan.SetOp(setOp.op(), widen(left.plan(), types, names),
                widen(right.plan(), types, names));
        Plan out = combined;
        if (!setOp.orderBy().isEmpty()) {
            final List<Plan.SortKey> keys = new ArrayList<>();
            for (final Ast.OrderItem o : setOp.orderBy()) {
                keys.add(new Plan.SortKey(outputIndex(o.expr(), names), o.descending()));
            }
            out = new Plan.Sort(out, List.copyOf(keys));
        }
        out = limit(out, setOp.limit());
        return Rel.of(out, null, -1);
    }

    /** {@code plan} under a projection casting each column to {@code types} and naming it, when any differs. */
    private static Plan widen(final Plan plan, final List<CdbType> types, final List<String> names) {
        boolean same = true;
        for (int i = 0; i < types.size(); i++) {
            final Field f = plan.fields().get(i);
            same &= f.type() == types.get(i);
        }
        if (same) {
            return plan;
        }
        final List<Bound> exprs = new ArrayList<>(types.size());
        final List<Field> fields = new ArrayList<>(types.size());
        for (int i = 0; i < types.size(); i++) {
            final Field f = plan.fields().get(i);
            exprs.add(Binder.cast(new Bound.Column(i, f.type()), types.get(i), -1));
            fields.add(new Field(names.get(i), types.get(i)));
        }
        return new Plan.Project(plan, List.copyOf(exprs), List.copyOf(fields));
    }

    /** The output column an ORDER BY item names: a 1-based position or an output name. */
    private static int outputIndex(final Ast.Expr expr, final List<String> names) {
        if (expr instanceof Ast.Literal(final Long n, final int pos)) {
            if (n < 1 || n > names.size()) {
                throw new CdbException("cannot order by position out of range [position=" + n + ']', pos);
            }
            return (int) (n - 1);
        }
        if (expr instanceof Ast.Ident(final List<String> parts, final int pos)) {
            for (int i = 0; i < names.size(); i++) {
                if (names.get(i).equalsIgnoreCase(parts.getLast())) {
                    return i;
                }
            }
            throw new CdbException("cannot find order column [column=" + String.join(".", parts) + ']', pos);
        }
        throw new CdbException("cannot order a set operation by an expression", -1);
    }

    /** {@code LIMIT n} rows {@code [0, n)}, {@code LIMIT -n} the last n, {@code LIMIT a, b} rows {@code [a, b)}. */
    static Plan limit(final Plan plan, final Ast.Limit limit) {
        if (limit == null) {
            return plan;
        }
        final long lo = constantLong(limit.lo());
        if (limit.hi() == null) {
            // the last n rows are (-n, 0): the bounds LimitCursor reads as counted from the end
            return lo < 0 ? new Plan.Limit(plan, lo, 0) : new Plan.Limit(plan, 0, lo);
        }
        return new Plan.Limit(plan, lo, constantLong(limit.hi()));
    }

    private static long constantLong(final Ast.Expr expr) {
        final Bound b = new Binder(new Rel(new Plan.Source("one_row", List.of(), List.of()), List.of(), -1), null)
                .bind(expr);
        if (b instanceof Bound.Constant(final Long n, final CdbType _)) {
            return n;
        }
        throw new CdbException("cannot limit by non-constant value", -1);
    }

    /** Each value cast to its column's type; the designated timestamp cannot be set. */
    private Plan update(final Ast.Update update) {
        final TableDef def = tables.apply(update.table());
        if (def == null) {
            throw new CdbException("cannot find table [table=" + update.table() + ']', update.pos());
        }
        final Rel rel = Rel.of(scan(def), def.name(), def.timestampIndex());
        final Binder binder = new Binder(rel, null);
        final int[] columns = new int[update.assignments().size()];
        final List<Bound> values = new ArrayList<>(columns.length);
        for (int i = 0; i < columns.length; i++) {
            final Ast.Assignment a = update.assignments().get(i);
            final int c = def.columnIndex(a.column());
            if (c < 0) {
                throw new CdbException("cannot find column [column=" + a.column() + ']', update.pos());
            }
            if (c == def.timestampIndex()) {
                throw new CdbException("cannot update designated timestamp [column=" + a.column() + ']',
                        update.pos());
            }
            final CdbType to = def.columns().get(c).type();
            final Bound value = Binder.cast(binder.bind(a.value()), to, update.pos());
            if (value instanceof Bound.Call(final String function, final List<Bound> args, final CdbType _)
                    && "cast".equals(function) && !castable(args.getFirst().type(), to)) {
                throw new CdbException("cannot assign value of other type [column=" + a.column() + ", type="
                        + args.getFirst().type() + ", target=" + to + ']', update.pos());
            }
            columns[i] = c;
            values.add(value);
        }
        final Bound where = update.where() == null ? null : binder.bindValue(update.where());
        return new Plan.Update(def.name(), columns, List.copyOf(values), where);
    }

    private static boolean castable(final CdbType from, final CdbType to) {
        return from.isText() && to.isText() || from == CdbType.LONG && to == CdbType.DOUBLE
                || from == CdbType.LONG && to == CdbType.TIMESTAMP;
    }

    /** {@code SHOW TABLES}, {@code SHOW COLUMNS FROM t} and {@code SHOW CREATE TABLE t}; others are not supported. */
    private Plan show(final Ast.Show show) {
        return switch (show.what().toLowerCase(Locale.ROOT)) {
            case "tables" -> new Plan.Source("show_tables", List.of(), sourceFields.apply("show_tables", List.of()));
            case "columns" -> tableListing(show, "show_columns");
            case "create table" -> tableListing(show, "show_create_table");
            default -> throw new CdbException("cannot show unsupported listing [what=" + show.what() + ']', show.pos());
        };
    }

    /** The listing {@code function} of the table {@code show} names, which must exist. */
    private Plan tableListing(final Ast.Show show, final String function) {
        final TableDef def = tables.apply(show.table());
        if (def == null) {
            throw new CdbException("cannot find table [table=" + show.table() + ']', show.pos());
        }
        final List<String> args = List.of(def.name());
        return new Plan.Source(function, args, sourceFields.apply(function, args));
    }
}
