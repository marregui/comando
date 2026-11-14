package dev.marregui.comando.cdb.exec;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.exec.agg.AggRows;
import dev.marregui.comando.cdb.plan.Bound;
import dev.marregui.comando.cdb.plan.Plan;

import static dev.marregui.comando.cdb.exec.agg.AggRows.field;
import static dev.marregui.comando.cdb.exec.agg.AggRows.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// [WindowCursor] adds one column per window call after the input columns and keeps the rows in input order.
/// Expected values marked QuestDB were read from the local QuestDB's `/exec` for the query in the comment, where the
/// partition and order expressions are input columns here: `x`, `x % 3`, `x % 2`.
class WindowCursorTest {

    private static final Bound X = new Bound.Column(0, CdbType.LONG);
    private static final Bound MOD3 = new Bound.Column(1, CdbType.LONG);
    private static final Bound MOD2 = new Bound.Column(2, CdbType.LONG);
    private static final List<Plan.SortKey> BY_X = List.of(new Plan.SortKey(0, false));
    private static final List<Plan.SortKey> BY_X_DESC = List.of(new Plan.SortKey(0, true));
    private static final List<Plan.SortKey> BY_MOD2 = List.of(new Plan.SortKey(2, false));

    /// QuestDB: `SELECT lag(x) OVER w, lag(x, 2) OVER w, row_number() OVER w, sum(x) OVER w FROM long_sequence(10)`
    /// with `w` = `(PARTITION BY x % 3 ORDER BY x)`, the input three rows a page.
    @Test
    void lagRowNumberAndRunningSumMatchQuestDb() {
        final List<List<Object>> out = run(sequence(10), 3, List.of(
                call("lag", CdbType.LONG, List.of(X), List.of(MOD3), BY_X),
                call("lag", CdbType.LONG, List.of(X, lng(2)), List.of(MOD3), BY_X),
                call("row_number", CdbType.LONG, List.of(), List.of(MOD3), BY_X),
                call("sum", CdbType.DOUBLE, List.of(X), List.of(MOD3), BY_X)));
        assertThat(column(out, 0)).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L);
        assertThat(column(out, 3)).containsExactly(null, null, null, 1L, 2L, 3L, 4L, 5L, 6L, 7L);
        assertThat(column(out, 4)).containsExactly(null, null, null, null, null, null, 1L, 2L, 3L, 4L);
        assertThat(column(out, 5)).containsExactly(1L, 1L, 1L, 2L, 2L, 2L, 3L, 3L, 3L, 4L);
        assertThat(column(out, 6)).containsExactly(1.0, 2.0, 3.0, 5.0, 7.0, 9.0, 12.0, 15.0, 18.0, 22.0);
    }

    /// QuestDB: `SELECT sum(x) OVER (PARTITION BY x % 3), count() OVER (PARTITION BY x % 3), avg(x) OVER
    /// (PARTITION BY x % 3 ORDER BY x) FROM long_sequence(7)`: without ORDER BY the value is the whole partition's.
    @Test
    void withoutOrderTheValueIsTheWholePartitions() {
        final List<List<Object>> out = run(sequence(7), Page.CAPACITY, List.of(
                call("sum", CdbType.DOUBLE, List.of(X), List.of(MOD3), List.of()),
                call("count", CdbType.LONG, List.of(), List.of(MOD3), List.of()),
                call("avg", CdbType.DOUBLE, List.of(X), List.of(MOD3), BY_X)));
        assertThat(column(out, 3)).containsExactly(12.0, 7.0, 9.0, 12.0, 7.0, 9.0, 12.0);
        assertThat(column(out, 4)).containsExactly(3L, 2L, 2L, 3L, 2L, 2L, 3L);
        assertThat(column(out, 5)).containsExactly(1.0, 2.0, 3.0, 2.5, 3.5, 4.5, 4.0);
    }

    /// QuestDB: `SELECT sum(x) OVER (ORDER BY k), row_number() OVER (ORDER BY k), lag(x, 1, -1) OVER (ORDER BY k),
    /// row_number() OVER () FROM (SELECT x, x % 2 k FROM long_sequence(6))`: rows that tie are taken one at a time, in
    /// input order.
    @Test
    void tiesAreTakenOneRowAtATimeMatchingQuestDb() {
        final List<List<Object>> out = run(sequence(6), 4, List.of(
                call("sum", CdbType.DOUBLE, List.of(X), List.of(), BY_MOD2),
                call("row_number", CdbType.LONG, List.of(), List.of(), BY_MOD2),
                call("lag", CdbType.LONG, List.of(X, lng(1), lng(-1)), List.of(), BY_MOD2),
                call("row_number", CdbType.LONG, List.of(), List.of(), List.of())));
        assertThat(column(out, 3)).containsExactly(13.0, 2.0, 16.0, 6.0, 21.0, 12.0);
        assertThat(column(out, 4)).containsExactly(4L, 1L, 5L, 2L, 6L, 3L);
        assertThat(column(out, 5)).containsExactly(6L, -1L, 1L, 2L, 3L, 4L);
        assertThat(column(out, 6)).containsExactly(1L, 2L, 3L, 4L, 5L, 6L);
    }

    /// QuestDB: `SELECT lag(x) OVER (ORDER BY x DESC), min(x) OVER (PARTITION BY x % 2 ORDER BY x DESC), max(x) OVER
    /// (ORDER BY x) FROM long_sequence(6)`.
    @Test
    void descendingOrderMatchesQuestDb() {
        final List<List<Object>> out = run(sequence(6), 4, List.of(
                call("lag", CdbType.LONG, List.of(X), List.of(), BY_X_DESC),
                call("min", CdbType.LONG, List.of(X), List.of(MOD2), BY_X_DESC),
                call("max", CdbType.LONG, List.of(X), List.of(), BY_X)));
        assertThat(column(out, 3)).containsExactly(2L, 3L, 4L, 5L, 6L, null);
        assertThat(column(out, 4)).containsExactly(1L, 2L, 3L, 4L, 5L, 6L);
        assertThat(column(out, 5)).containsExactly(1L, 2L, 3L, 4L, 5L, 6L);
    }

    /// A window function cdb does not run fails when the cursor first computes, and closing still closes the child.
    @Test
    void anUnknownFunctionFails() {
        final AggRows.Source source = AggRows.source(inputFields(), 4, sequence(3));
        final List<Bound.WindowCall> calls = List.of(call("lead", CdbType.LONG, List.of(X), List.of(), BY_X));
        final WindowCursor cursor = new WindowCursor(source, new Plan.Window(null, calls, outputFields(calls)), 0);
        assertThatThrownBy(cursor::next).isInstanceOf(CdbException.class);
        cursor.close();
        assertThat(source.closed()).isTrue();
    }

    private static List<List<Object>> sequence(final int n) {
        final List<List<Object>> rows = new ArrayList<>();
        for (long x = 1; x <= n; x++) {
            rows.add(row(x, x % 3, x % 2));
        }
        return rows;
    }

    private static List<Object> column(final List<List<Object>> rows, final int column) {
        final List<Object> values = new ArrayList<>();
        for (final List<Object> row : rows) {
            values.add(row.get(column));
        }
        return values;
    }

    private static List<Field> inputFields() {
        return List.of(field("x", CdbType.LONG), field("m3", CdbType.LONG), field("m2", CdbType.LONG));
    }

    private static List<Field> outputFields(final List<Bound.WindowCall> calls) {
        final List<Field> out = new ArrayList<>(inputFields());
        for (final Bound.WindowCall call : calls) {
            out.add(field(call.function(), call.type()));
        }
        return out;
    }

    private static List<List<Object>> run(
            final List<List<Object>> rows,
            final int pageSize,
            final List<Bound.WindowCall> calls
    ) {
        final Plan.Window spec = new Plan.Window(null, calls, outputFields(calls));
        return AggRows.drain(new WindowCursor(AggRows.source(inputFields(), pageSize, rows), spec, 0));
    }

    private static Bound.WindowCall call(
            final String function,
            final CdbType type,
            final List<Bound> args,
            final List<Bound> partitionBy,
            final List<Plan.SortKey> orderBy
    ) {
        return new Bound.WindowCall(function, args, partitionBy, orderBy, type);
    }

    private static Bound lng(final long value) {
        return new Bound.Constant(value, CdbType.LONG);
    }
}
