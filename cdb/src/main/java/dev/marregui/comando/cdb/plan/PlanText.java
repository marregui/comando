package dev.marregui.comando.cdb.plan;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.exec.Field;
import dev.marregui.comando.cdb.store.ColumnDef;
import dev.marregui.comando.cdb.store.TableDef;

/**
 * A plan as an indented tree, one node per line, children two spaces in: what {@code EXPLAIN} shows and the tests
 * assert. Columns print as {@code $i}, TIMESTAMP constants as ISO instants, scan bounds {@code -inf} and {@code +inf}
 * when open; a scan's and a join's fields are left out, a projection's and an aggregate's shown beside each
 * expression. An IN subquery prints as {@code subquery} in its expression, its plan under a {@code Subquery} line
 * below the node that holds it, before the node's child.
 */
public final class PlanText {

    private static final long MICROS_PER_SECOND = 1_000_000L;

    private PlanText() {
    }

    static String of(final Plan plan) {
        return of(plan, _ -> null);
    }

    /**
     * As {@link #of(Plan)}, each node's own line followed by {@code " -- "} and the note {@code notes} gives the node,
     * when it gives one (null for none): how the executor runs it, for {@code EXPLAIN}.
     */
    public static String of(final Plan plan, final Function<Plan, String> notes) {
        final StringBuilder sink = new StringBuilder();
        put(sink, plan, 0, notes);
        return sink.toString();
    }

    static String of(final Bound b) {
        final StringBuilder sink = new StringBuilder();
        put(sink, b);
        return sink.toString();
    }

    /** A scan's terms, {@code terms [$1 = 'a', $2 >= 5]}; nothing when it has none. */
    private static void terms(final StringBuilder sink, final List<Plan.ScanTerm> terms) {
        if (terms.isEmpty()) {
            return;
        }
        sink.append(" terms [");
        for (int i = 0; i < terms.size(); i++) {
            final Plan.ScanTerm t = terms.get(i);
            sink.append(i == 0 ? "" : ", ").append('$').append(t.column()).append(' ').append(switch (t.op()) {
                case EQ -> "=";
                case NE -> "!=";
                case LT -> "<";
                case LE -> "<=";
                case GT -> ">";
                case GE -> ">=";
            }).append(' ');
            if (t.textValue() != null) {
                sink.append('\'').append(t.textValue()).append('\'');
            } else if (t.longValue() != Long.MIN_VALUE) {
                sink.append(t.longValue());
            } else {
                sink.append(t.doubleValue());
            }
        }
        sink.append(']');
    }

    private static void put(
            final StringBuilder sink,
            final Plan plan,
            final int depth,
            final Function<Plan, String> notes
    ) {
        sink.repeat(' ', depth * 2);
        switch (plan) {
            case Plan.Scan(final String table, final int[] columns, final long from, final long to,
                           final List<Plan.ScanTerm> terms, final boolean newestFirst, final List<Field> _) -> {
                sink.append("Scan ").append(table).append(' ').append(Arrays.toString(columns));
                sink.append(' ');
                range(sink, from, to);
                terms(sink, terms);
                if (newestFirst) {
                    sink.append(" newest first");
                }
                end(sink, plan, notes);
            }
            case Plan.Source(final String function, final List<String> args, final List<Field> _) -> {
                sink.append("Source ").append(function).append(args);
                end(sink, plan, notes);
            }
            case Plan.Filter(final Plan child, final Bound predicate) -> {
                sink.append("Filter ");
                put(sink, predicate);
                end(sink, plan, notes);
                subqueries(sink, predicate, depth + 1, notes);
                put(sink, child, depth + 1, notes);
            }
            case Plan.Project(final Plan child, final List<Bound> exprs, final List<Field> fields) -> {
                sink.append("Project ");
                named(sink, exprs, fields, 0);
                child(sink, plan, child, depth, notes);
            }
            case Plan.Aggregate(final Plan child, final List<Bound> keys, final List<Bound.Agg> aggs,
                                final List<Field> fields) -> {
                sink.append("Aggregate keys=");
                named(sink, keys, fields, 0);
                sink.append(" aggs=");
                named(sink, List.copyOf(aggs), fields, keys.size());
                child(sink, plan, child, depth, notes);
            }
            case Plan.SampleBy(final Plan child, final int ts, final long count, final String unit,
                               final List<Bound> keys, final List<Bound.Agg> aggs, final List<String> fill,
                               final List<Field> fields) -> {
                sink.append("SampleBy ").append(count).append(unit).append(" ts=$").append(ts).append(" keys=");
                named(sink, keys, fields, 1);
                sink.append(" aggs=");
                named(sink, List.copyOf(aggs), fields, 1 + keys.size());
                if (!fill.isEmpty()) {
                    sink.append(" fill=").append(fill);
                }
                child(sink, plan, child, depth, notes);
            }
            case Plan.LatestOn(final Plan child, final int ts, final int[] partition, final boolean _) -> {
                sink.append("LatestOn ts=$").append(ts).append(" partition=").append(Arrays.toString(partition));
                child(sink, plan, child, depth, notes);
            }
            case Plan.Distinct(final Plan child) -> {
                sink.append("Distinct");
                child(sink, plan, child, depth, notes);
            }
            case Plan.Sort(final Plan child, final List<Plan.SortKey> keys, final long _) -> {
                sink.append("Sort ");
                sortKeys(sink, keys);
                child(sink, plan, child, depth, notes);
            }
            case Plan.Limit(final Plan child, final long lo, final long hi) -> {
                sink.append("Limit ").append(lo).append(", ").append(hi == Long.MAX_VALUE ? "+inf" : hi);
                child(sink, plan, child, depth, notes);
            }
            case Plan.SetOp(final String op, final Plan left, final Plan right) -> {
                sink.append("SetOp ").append(op);
                end(sink, plan, notes);
                put(sink, left, depth + 1, notes);
                put(sink, right, depth + 1, notes);
            }
            case Plan.Join(final Plan left, final Plan right, final String kind, final Bound on,
                           final List<Field> _) -> {
                sink.append("Join ").append(kind);
                if (on != null) {
                    sink.append(" on ");
                    put(sink, on);
                }
                end(sink, plan, notes);
                put(sink, left, depth + 1, notes);
                put(sink, right, depth + 1, notes);
            }
            case Plan.Window(final Plan child, final List<Bound.WindowCall> calls, final List<Field> fields) -> {
                sink.append("Window ");
                named(sink, List.copyOf(calls), fields, fields.size() - calls.size());
                child(sink, plan, child, depth, notes);
            }
            case Plan.CreateTable(final TableDef def, final boolean ifNotExists) -> {
                sink.append("CreateTable ").append(def.name()).append(ifNotExists ? " if not exists" : "").append(" (");
                for (int i = 0; i < def.columns().size(); i++) {
                    final ColumnDef c = def.columns().get(i);
                    sink.append(i > 0 ? ", " : "").append(c.name()).append(':').append(c.type());
                }
                sink.append(") timestamp=").append(def.timestampIndex()).append(" partition=")
                        .append(def.partitionBy()).append(" dedup=").append(def.dedupKeys())
                        .append(def.ttlMicrosOrMonths() < 0 ? " ttlMonths=" : " ttlMicros=")
                        .append(Math.abs(def.ttlMicrosOrMonths()));
                end(sink, plan, notes);
            }
            case Plan.Update(final String table, final int[] columns, final List<Bound> values, final Bound where) -> {
                sink.append("Update ").append(table).append(" set ");
                for (int i = 0; i < columns.length; i++) {
                    sink.append(i > 0 ? ", " : "").append('$').append(columns[i]).append('=');
                    put(sink, values.get(i));
                }
                if (where != null) {
                    sink.append(" where ");
                    put(sink, where);
                }
                end(sink, plan, notes);
                for (final Bound v : values) {
                    subqueries(sink, v, depth + 1, notes);
                }
                if (where != null) {
                    subqueries(sink, where, depth + 1, notes);
                }
            }
            case Plan.Insert(final String table, final int[] columns, final List<Plan> sources) -> {
                sink.append("Insert ").append(table).append(' ').append(Arrays.toString(columns));
                end(sink, plan, notes);
                for (final Plan source : sources) {
                    put(sink, source, depth + 1, notes);
                }
            }
            case Plan.AddColumns(final String table, final List<ColumnDef> columns) -> {
                sink.append("AddColumns ").append(table).append(" (");
                for (int i = 0; i < columns.size(); i++) {
                    final ColumnDef c = columns.get(i);
                    sink.append(i > 0 ? ", " : "").append(c.name()).append(':').append(c.type());
                }
                sink.append(')');
                end(sink, plan, notes);
            }
            case Plan.SetTtl(final String table, final long ttlMicrosOrMonths) -> {
                sink.append("SetTtl ").append(table).append(ttlMicrosOrMonths < 0 ? " ttlMonths=" : " ttlMicros=")
                        .append(Math.abs(ttlMicrosOrMonths));
                end(sink, plan, notes);
            }
            case Plan.DropTable(final String table, final boolean ifExists) -> {
                sink.append("DropTable ").append(table).append(ifExists ? " ifExists" : "");
                end(sink, plan, notes);
            }
            case Plan.Truncate(final String table) -> {
                sink.append("Truncate ").append(table);
                end(sink, plan, notes);
            }
            case Plan.DropColumn(final String table, final String column) -> {
                sink.append("DropColumn ").append(table).append(' ').append(column);
                end(sink, plan, notes);
            }
            case Plan.RenameColumn(final String table, final String from, final String to) -> {
                sink.append("RenameColumn ").append(table).append(' ').append(from).append(" to ").append(to);
                end(sink, plan, notes);
            }
            case Plan.Backup(final String folder) -> {
                sink.append("Backup to ").append(folder);
                end(sink, plan, notes);
            }
            case Plan.DropPartitions(final String table, final long[] fromMicros, final long[] toMicros) -> {
                sink.append("DropPartitions ").append(table);
                for (int i = 0; i < fromMicros.length; i++) {
                    sink.append(i > 0 ? ", " : " ");
                    range(sink, fromMicros[i], toMicros[i]);
                }
                end(sink, plan, notes);
            }
            case Plan.Delete(final String table, final Bound where) -> {
                sink.append("Delete ").append(table);
                if (where != null) {
                    sink.append(" where ");
                    put(sink, where);
                }
                end(sink, plan, notes);
                if (where != null) {
                    subqueries(sink, where, depth + 1, notes);
                }
            }
            case Plan.Explain(final Plan inner, final List<Field> _) -> {
                sink.append("Explain");
                end(sink, plan, notes);
                put(sink, inner, depth + 1, notes);
            }
        }
    }

    /** Ends {@code plan}'s own line, its note first when {@code notes} has one. */
    private static void end(final StringBuilder sink, final Plan plan, final Function<Plan, String> notes) {
        final String note = notes.apply(plan);
        if (note != null) {
            sink.append(" -- ").append(note);
        }
        sink.append('\n');
    }

    /** Each IN subquery of {@code b}: a {@code Subquery} line at {@code depth}, its plan one level in. */
    private static void subqueries(
            final StringBuilder sink,
            final Bound b,
            final int depth,
            final Function<Plan, String> notes
    ) {
        for (final Bound.Subquery s : Bound.Subquery.of(b)) {
            sink.repeat(' ', depth * 2).append("Subquery\n");
            put(sink, s.plan(), depth + 1, notes);
        }
    }

    /** {@code [from, to)} as instants, the open bounds {@code -inf} and {@code +inf}. */
    private static void range(final StringBuilder sink, final long fromMicros, final long toMicros) {
        sink.append('[').append(fromMicros == Long.MIN_VALUE ? "-inf" : instant(fromMicros)).append(", ");
        sink.append(toMicros == Long.MAX_VALUE ? "+inf" : instant(toMicros)).append(')');
    }

    private static void child(
            final StringBuilder sink,
            final Plan parent,
            final Plan child,
            final int depth,
            final Function<Plan, String> notes
    ) {
        end(sink, parent, notes);
        put(sink, child, depth + 1, notes);
    }

    private static void named(
            final StringBuilder sink,
            final List<Bound> exprs,
            final List<Field> fields,
            final int at
    ) {
        sink.append('[');
        for (int i = 0; i < exprs.size(); i++) {
            final Field f = fields.get(at + i);
            sink.append(i > 0 ? ", " : "").append(f.name()).append(':').append(f.type()).append('=');
            put(sink, exprs.get(i));
        }
        sink.append(']');
    }

    private static void sortKeys(final StringBuilder sink, final List<Plan.SortKey> keys) {
        sink.append('[');
        for (int i = 0; i < keys.size(); i++) {
            sink.append(i > 0 ? ", " : "").append('$').append(keys.get(i).column());
            sink.append(keys.get(i).descending() ? " desc" : "");
        }
        sink.append(']');
    }

    private static void put(final StringBuilder sink, final Bound b) {
        switch (b) {
            case Bound.Column(final int index, final CdbType _) -> sink.append('$').append(index);
            case Bound.Constant(final Object value, final CdbType type) -> constant(sink, value, type);
            case Bound.Call(final String function, final List<Bound> args, final CdbType type) -> {
                sink.append(function).append('(');
                list(sink, args);
                sink.append(')');
                if ("cast".equals(function)) {
                    sink.append("::").append(type);
                }
            }
            case Bound.Agg(final String function, final List<Bound> args, final boolean distinct,
                           final CdbType _) -> {
                sink.append(function).append('(').append(distinct ? "distinct " : "");
                list(sink, args);
                sink.append(')');
            }
            case Bound.WindowCall(final String function, final List<Bound> args, final List<Bound> partitionBy,
                                  final List<Plan.SortKey> orderBy, final CdbType _) -> {
                sink.append(function).append('(');
                list(sink, args);
                sink.append(") over(partition=[");
                list(sink, partitionBy);
                sink.append("] order=");
                sortKeys(sink, orderBy);
                sink.append(')');
            }
        }
    }

    private static void constant(final StringBuilder sink, final Object value, final CdbType type) {
        if (value == null) {
            sink.append("NULL:").append(type);
        } else if (value instanceof Bound.Subquery) {
            sink.append("subquery");
        } else if (type == CdbType.TIMESTAMP && value instanceof final Long micros) {
            sink.append(instant(micros));
        } else if (value instanceof final String s) {
            sink.append('\'').append(s).append('\'');
        } else {
            sink.append(value);
        }
    }

    private static void list(final StringBuilder sink, final List<Bound> args) {
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) {
                sink.append(", ");
            }
            put(sink, args.get(i));
        }
    }

    private static String instant(final long micros) {
        return Instant.ofEpochSecond(Math.floorDiv(micros, MICROS_PER_SECOND),
                Math.floorMod(micros, MICROS_PER_SECOND) * 1_000L).toString();
    }
}
