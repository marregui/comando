package dev.marregui.comando.cdb.plan;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.exec.Field;
import dev.marregui.comando.cdb.store.ColumnDef;
import dev.marregui.comando.cdb.store.TableDef;

/**
 * A plan as an indented tree, one node per line, children two spaces in: what {@code EXPLAIN} shows and the tests
 * assert. Columns print as {@code $i}, TIMESTAMP constants as ISO instants, scan bounds {@code -inf} and {@code +inf}
 * when open; a scan's and a join's fields are left out, a projection's and an aggregate's shown beside each
 * expression.
 */
final class PlanText {

    private static final long MICROS_PER_SECOND = 1_000_000L;

    private PlanText() {
    }

    static String of(final Plan plan) {
        final StringBuilder sink = new StringBuilder();
        put(sink, plan, 0);
        return sink.toString();
    }

    static String of(final Bound b) {
        final StringBuilder sink = new StringBuilder();
        put(sink, b);
        return sink.toString();
    }

    private static void put(final StringBuilder sink, final Plan plan, final int depth) {
        sink.repeat(' ', depth * 2);
        switch (plan) {
            case Plan.Scan(final String table, final int[] columns, final long from, final long to,
                           final List<Field> _) -> {
                sink.append("Scan ").append(table).append(' ').append(Arrays.toString(columns));
                sink.append(" [").append(from == Long.MIN_VALUE ? "-inf" : instant(from)).append(", ");
                sink.append(to == Long.MAX_VALUE ? "+inf" : instant(to)).append(")\n");
            }
            case Plan.Source(final String function, final List<String> args, final List<Field> _) ->
                    sink.append("Source ").append(function).append(args).append('\n');
            case Plan.Filter(final Plan child, final Bound predicate) -> {
                sink.append("Filter ");
                put(sink, predicate);
                child(sink, child, depth);
            }
            case Plan.Project(final Plan child, final List<Bound> exprs, final List<Field> fields) -> {
                sink.append("Project ");
                named(sink, exprs, fields, 0);
                child(sink, child, depth);
            }
            case Plan.Aggregate(final Plan child, final List<Bound> keys, final List<Bound.Agg> aggs,
                                final List<Field> fields) -> {
                sink.append("Aggregate keys=");
                named(sink, keys, fields, 0);
                sink.append(" aggs=");
                named(sink, List.copyOf(aggs), fields, keys.size());
                child(sink, child, depth);
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
                child(sink, child, depth);
            }
            case Plan.LatestOn(final Plan child, final int ts, final int[] partition) -> {
                sink.append("LatestOn ts=$").append(ts).append(" partition=").append(Arrays.toString(partition));
                child(sink, child, depth);
            }
            case Plan.Distinct(final Plan child) -> {
                sink.append("Distinct");
                child(sink, child, depth);
            }
            case Plan.Sort(final Plan child, final List<Plan.SortKey> keys) -> {
                sink.append("Sort ");
                sortKeys(sink, keys);
                child(sink, child, depth);
            }
            case Plan.Limit(final Plan child, final long lo, final long hi) -> {
                sink.append("Limit ").append(lo).append(", ").append(hi == Long.MAX_VALUE ? "+inf" : hi);
                child(sink, child, depth);
            }
            case Plan.SetOp(final String op, final Plan left, final Plan right) -> {
                sink.append("SetOp ").append(op).append('\n');
                put(sink, left, depth + 1);
                put(sink, right, depth + 1);
            }
            case Plan.Join(final Plan left, final Plan right, final String kind, final Bound on,
                           final List<Field> _) -> {
                sink.append("Join ").append(kind);
                if (on != null) {
                    sink.append(" on ");
                    put(sink, on);
                }
                sink.append('\n');
                put(sink, left, depth + 1);
                put(sink, right, depth + 1);
            }
            case Plan.Window(final Plan child, final List<Bound.WindowCall> calls, final List<Field> fields) -> {
                sink.append("Window ");
                named(sink, List.copyOf(calls), fields, fields.size() - calls.size());
                child(sink, child, depth);
            }
            case Plan.CreateTable(final TableDef def, final boolean ifNotExists) -> {
                sink.append("CreateTable ").append(def.name()).append(ifNotExists ? " if not exists" : "").append(" (");
                for (int i = 0; i < def.columns().size(); i++) {
                    final ColumnDef c = def.columns().get(i);
                    sink.append(i > 0 ? ", " : "").append(c.name()).append(':').append(c.type());
                }
                sink.append(") timestamp=").append(def.timestampIndex()).append(" partition=")
                        .append(def.partitionBy()).append(" dedup=").append(def.dedupKeys())
                        .append(" ttlMicros=").append(def.ttlMicros()).append('\n');
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
                sink.append('\n');
            }
        }
    }

    private static void child(final StringBuilder sink, final Plan child, final int depth) {
        sink.append('\n');
        put(sink, child, depth + 1);
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
