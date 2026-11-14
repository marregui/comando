package dev.marregui.comando.cdb.plan;

import java.util.List;

import dev.marregui.comando.cdb.exec.Field;
import dev.marregui.comando.cdb.store.TableDef;

/**
 * A query as a tree of operators, every name resolved and every type checked; the executor builds one cursor per node.
 * Each node's {@link #fields()} is its output; a {@link Bound.Column} index in a node refers to its child's output
 * ({@link Join}: the left's fields, then the right's).
 */
public sealed interface Plan {

    List<Field> fields();

    /** A sort key: a column of the input, and its direction; NULLs sort first ascending, as QuestDB sorts them. */
    record SortKey(int column, boolean descending) {
    }

    /**
     * The rows of {@code table} in designated-timestamp order, the given columns of its definition; partitions wholly
     * outside {@code [fromMicros, toMicros)} are skipped (the bounds {@link Long#MIN_VALUE} and {@link Long#MAX_VALUE}
     * when there is no time filter; rows inside kept partitions are still filtered by a {@link Filter} above).
     */
    record Scan(String table, int[] columns, long fromMicros, long toMicros, List<Field> fields) implements Plan {
    }

    /**
     * A table function or a catalog listing: {@code tables}, {@code table_columns} (args: the table),
     * {@code show_tables}, {@code show_columns} (args: the table), {@code long_sequence} (args: the count, as text),
     * {@code one_row} (a single row with no column, for a SELECT without FROM).
     */
    record Source(String function, List<String> args, List<Field> fields) implements Plan {
    }

    record Filter(Plan child, Bound predicate) implements Plan {
        @Override
        public List<Field> fields() {
            return child.fields();
        }
    }

    record Project(Plan child, List<Bound> exprs, List<Field> fields) implements Plan {
    }

    /** Output: the keys, then the aggregates; one row with only aggregates when there are no keys, even with no input. */
    record Aggregate(Plan child, List<Bound> keys, List<Bound.Agg> aggs, List<Field> fields) implements Plan {
    }

    /**
     * {@code SAMPLE BY}: buckets of {@code strideMicros} (or calendar months and years for units {@code M} and
     * {@code y}) of column {@code timestampColumn}, aligned to calendar (UTC); output the bucket's start, the keys,
     * then the aggregates; {@code fill} as written, empty for none.
     */
    record SampleBy(
            Plan child,
            int timestampColumn,
            long count,
            String unit,
            List<Bound> keys,
            List<Bound.Agg> aggs,
            List<String> fill,
            List<Field> fields
    ) implements Plan {
    }

    /** For each distinct value of the partition columns, the row with the largest {@code timestampColumn}. */
    record LatestOn(Plan child, int timestampColumn, int[] partitionColumns) implements Plan {
        @Override
        public List<Field> fields() {
            return child.fields();
        }
    }

    record Distinct(Plan child) implements Plan {
        @Override
        public List<Field> fields() {
            return child.fields();
        }
    }

    record Sort(Plan child, List<SortKey> keys) implements Plan {
        @Override
        public List<Field> fields() {
            return child.fields();
        }
    }

    /** Rows {@code [lo, hi)} of the input; negative bounds count from the end, as QuestDB's {@code LIMIT -n}. */
    record Limit(Plan child, long lo, long hi) implements Plan {
        @Override
        public List<Field> fields() {
            return child.fields();
        }
    }

    /** {@code op}: {@code union}, {@code union all}, {@code except}, {@code intersect}; fields are the left's. */
    record SetOp(String op, Plan left, Plan right) implements Plan {
        @Override
        public List<Field> fields() {
            return left.fields();
        }
    }

    /** {@code kind}: {@code inner}, {@code left}, {@code cross}; {@code on} null for cross. */
    record Join(Plan left, Plan right, String kind, Bound on, List<Field> fields) implements Plan {
    }

    /** The input's columns, then one per window call. */
    record Window(Plan child, List<Bound.WindowCall> calls, List<Field> fields) implements Plan {
    }

    /** DDL: no output rows. */
    record CreateTable(TableDef def, boolean ifNotExists) implements Plan {
        @Override
        public List<Field> fields() {
            return List.of();
        }
    }

    /** {@code UPDATE}: {@code values[i]} for column {@code columns[i]} where {@code where} holds (null: every row). */
    record Update(String table, int[] columns, List<Bound> values, Bound where) implements Plan {
        @Override
        public List<Field> fields() {
            return List.of();
        }
    }
}
