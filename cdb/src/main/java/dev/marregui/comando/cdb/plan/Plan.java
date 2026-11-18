package dev.marregui.comando.cdb.plan;

import java.util.List;

import dev.marregui.comando.cdb.exec.Field;
import dev.marregui.comando.cdb.store.ColumnDef;
import dev.marregui.comando.cdb.store.Compare;
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
     * The rows of {@code table} in designated-timestamp order, the given columns of its definition, with a timestamp in
     * {@code [fromMicros, toMicros)} (the bounds {@link Long#MIN_VALUE} and {@link Long#MAX_VALUE} when there is no time
     * filter; a {@link Filter} above still filters). The scan applies {@code terms}: a numeric one also keeps NULL
     * and NaN rows, for the Filter above to decide; a text one is exact, and a filter made only of text terms is not
     * planned. {@code newestFirst} reads the rows in descending timestamp order, the newest partition first.
     */
    record Scan(
            String table,
            int[] columns,
            long fromMicros,
            long toMicros,
            List<ScanTerm> terms,
            boolean newestFirst,
            List<Field> fields
    ) implements Plan {
    }

    /**
     * A top-level AND term of a scan's filter: output column {@code column} (an index into the scan's fields) compared
     * by {@code op} with a constant of its type: {@code longValue} for LONG and TIMESTAMP, {@code doubleValue} for
     * DOUBLE, {@code textValue} for SYMBOL and VARCHAR ({@link Compare#EQ} and {@link Compare#NE} only).
     */
    record ScanTerm(int column, Compare op, long longValue, double doubleValue, String textValue) {
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

    /**
     * For each distinct value of the partition columns, the row with the largest {@code timestampColumn}. {@code
     * newestFirst}: the child yields rows in descending timestamp order (a {@link Scan} with {@code newestFirst}, maybe
     * under a {@link Filter}), so the first row of each key is its latest.
     */
    record LatestOn(Plan child, int timestampColumn, int[] partitionColumns, boolean newestFirst) implements Plan {
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

    /** Rows ordered by {@code keys}, stable; {@code limit} at least 0: only the first {@code limit} rows are needed. */
    record Sort(Plan child, List<SortKey> keys, long limit) implements Plan {
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

    /**
     * {@code EXPLAIN}: one VARCHAR row per line of {@code plan}'s text, the executor's choices included; {@code fields}
     * is the one column {@code QUERY PLAN}.
     */
    record Explain(Plan plan, List<Field> fields) implements Plan {
    }

    /** A statement that changes the store and returns no rows: DDL and DML. */
    sealed interface Change extends Plan {
        @Override
        default List<Field> fields() {
            return List.of();
        }
    }

    /** DDL: no output rows. */
    record CreateTable(TableDef def, boolean ifNotExists) implements Change {
    }

    /** {@code UPDATE}: {@code values[i]} for column {@code columns[i]} where {@code where} holds (null: every row). */
    record Update(String table, int[] columns, List<Bound> values, Bound where) implements Change {
    }

    /**
     * {@code INSERT}: every row of each source, in order, its field {@code i} written to column {@code columns[i]};
     * each field already has its column's type. A VALUES list is one source per row, over {@code one_row}.
     */
    record Insert(String table, int[] columns, List<Plan> sources) implements Change {
    }

    /** {@code ALTER TABLE ADD COLUMN}: the columns the table lacks, added last in order. */
    record AddColumns(String table, List<ColumnDef> columns) implements Change {
    }

    /** {@code ALTER TABLE SET TTL}: {@code ttlMicros} 0 for none. */
    record SetTtl(String table, long ttlMicros) implements Change {
    }

    /** {@code DROP TABLE}: {@code ifExists} makes an absent table no error. */
    record DropTable(String table, boolean ifExists) implements Change {
    }

    /** {@code TRUNCATE TABLE}: every row dropped in one transaction; the definition stays. */
    record Truncate(String table) implements Change {
    }

    /**
     * {@code ALTER TABLE DROP PARTITION}: drops, in one transaction, every partition whose whole time range lies in one
     * of the ranges {@code [fromMicros[i], toMicros[i])}.
     */
    record DropPartitions(String table, long[] fromMicros, long[] toMicros) implements Change {
    }

    /** {@code DELETE}: the rows where {@code where} holds (null: every row), removed in one transaction. */
    record Delete(String table, Bound where) implements Change {
    }
}
