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

/// [GroupByCursor] groups by key tuple, one row per group, in first-seen order, and aggregates without boxing per
/// row. Expected values marked QuestDB were read from the local QuestDB's `/exec` for the query in the comment, where
/// the key expressions are input columns here. QuestDB answers groups in hash order, so those compare in any order.
class GroupByCursorTest {

    private static final Bound X = new Bound.Column(0, CdbType.LONG);
    private static final Bound K = new Bound.Column(1, CdbType.LONG);

    /// QuestDB: `SELECT x % 3 k, count(), sum(x), avg(x), min(x), max(x) FROM long_sequence(2500)`, over three
    /// pages of input.
    @Test
    void groupsMatchQuestDb() {
        final List<List<Object>> rows = new ArrayList<>();
        for (long x = 1; x <= 2500; x++) {
            rows.add(row(x, x % 3));
        }
        final List<List<Object>> out = run(rows, List.of(K), List.of(
                agg("count", CdbType.LONG),
                agg("sum", CdbType.LONG, X),
                agg("avg", CdbType.DOUBLE, X),
                agg("min", CdbType.LONG, X),
                agg("max", CdbType.LONG, X)));
        assertThat(out).containsExactlyInAnyOrder(
                row(1L, 834L, 1042917L, 1250.5, 1L, 2500L),
                row(2L, 833L, 1041250L, 1250.0, 2L, 2498L),
                row(0L, 833L, 1042083L, 1251.0, 3L, 2499L));
    }

    /// Groups come out in the order their key was first seen: `(x * 7) % 5` over 1..20 first sees 2, 4, 1, 3, 0
    /// (QuestDB's hash order is 2, 3, 4, 1, 0; the spec asks for first-seen).
    @Test
    void groupsComeOutInFirstSeenOrder() {
        final List<List<Object>> rows = new ArrayList<>();
        for (long x = 1; x <= 20; x++) {
            rows.add(row(x, x * 7 % 5));
        }
        final List<List<Object>> out = run(rows, List.of(K), List.of(agg("count", CdbType.LONG)));
        assertThat(out).containsExactly(row(2L, 4L), row(4L, 4L), row(1L, 4L), row(3L, 4L), row(0L, 4L));
    }

    /// QuestDB: `SELECT count(), count(x), sum(x), avg(x), min(x), max(x), first(x), last(x), count_distinct(x),
    /// approx_percentile(x, 0.5, 1) FROM long_sequence(10) WHERE x < 0`: one row, counts 0, the rest NULL.
    @Test
    void noKeysAndNoInputGiveOneRow() {
        final List<List<Object>> out = run(List.of(), List.of(), List.of(
                agg("count", CdbType.LONG),
                agg("count", CdbType.LONG, X),
                agg("sum", CdbType.LONG, X),
                agg("avg", CdbType.DOUBLE, X),
                agg("min", CdbType.LONG, X),
                agg("max", CdbType.LONG, X),
                agg("first", CdbType.LONG, X),
                agg("last", CdbType.LONG, X),
                agg("count_distinct", CdbType.LONG, X),
                agg("approx_percentile", CdbType.DOUBLE, X, dbl(0.5), lng(1))));
        assertThat(out).containsExactly(row(0L, 0L, null, null, null, null, null, null, 0L, null));
    }

    /// QuestDB: `SELECT x % 4 k, approx_percentile(v, 0.5, 1), approx_percentile(v, 0.9, 2),
    /// approx_percentile(v, 0.99), approx_median(v), percentile_disc(v, 0.5) FROM long_sequence(2500)` with
    /// `v = x * x * 37 % 100003`.
    @Test
    void percentilesMatchQuestDb() {
        final List<List<Object>> rows = new ArrayList<>();
        for (long x = 1; x <= 2500; x++) {
            rows.add(row(x * x * 37 % 100003, x % 4));
        }
        final List<List<Object>> out = run(rows, List.of(K), List.of(
                agg("approx_percentile", CdbType.DOUBLE, X, dbl(0.5), lng(1)),
                agg("approx_percentile", CdbType.DOUBLE, X, dbl(0.9), lng(2)),
                agg("approx_percentile", CdbType.DOUBLE, X, dbl(0.99)),
                agg("approx_median", CdbType.DOUBLE, X),
                agg("percentile_disc", CdbType.LONG, X, dbl(0.5))));
        assertThat(out).containsExactlyInAnyOrder(
                row(1L, 51199.0, 89087.0, 102399.0, 51199.0, 49206L),
                row(2L, 49151.0, 90111.0, 102399.0, 49151.0, 48782L),
                row(3L, 49151.0, 90111.0, 102399.0, 49151.0, 48065L),
                row(0L, 49151.0, 89599.0, 102399.0, 49151.0, 47504L));
    }

    /// QuestDB: `SELECT x % 3 k, count_distinct(x % 7), first(x), last(x), count(CASE WHEN x % 2 = 0 THEN x END)
    /// FROM long_sequence(100)`: count(x) counts the non-NULL values only.
    @Test
    void countDistinctFirstLastAndCountOfValuesMatchQuestDb() {
        final List<List<Object>> rows = new ArrayList<>();
        for (long x = 1; x <= 100; x++) {
            rows.add(row(x, x % 3, x % 7, x % 2 == 0 ? x : null));
        }
        final List<List<Object>> out = run(rows, List.of(K), List.of(
                agg("count_distinct", CdbType.LONG, new Bound.Column(2, CdbType.LONG)),
                agg("first", CdbType.LONG, X),
                agg("last", CdbType.LONG, X),
                agg("count", CdbType.LONG, new Bound.Column(3, CdbType.LONG))));
        assertThat(out).containsExactlyInAnyOrder(
                row(1L, 7L, 1L, 100L, 17L),
                row(2L, 7L, 2L, 98L, 17L),
                row(0L, 7L, 3L, 99L, 16L));
    }

    /// QuestDB: `SELECT min(s), max(s), first(s), count_distinct(s), count(s) FROM (SELECT CASE WHEN x % 3 = 0
    /// THEN null ELSE 'v' || (x % 4) END s FROM long_sequence(9))`: text aggregates skip NULLs, first keeps the first.
    @Test
    void textAggregatesMatchQuestDb() {
        final List<List<Object>> rows = new ArrayList<>();
        for (long x = 1; x <= 9; x++) {
            rows.add(row(x % 3 == 0 ? null : "v" + x % 4));
        }
        final Bound s = new Bound.Column(0, CdbType.VARCHAR);
        final List<List<Object>> out = run(List.of(field("s", CdbType.VARCHAR)), rows, List.of(), List.of(
                agg("min", CdbType.VARCHAR, s),
                agg("max", CdbType.VARCHAR, s),
                agg("first", CdbType.VARCHAR, s),
                agg("count_distinct", CdbType.LONG, s),
                agg("count", CdbType.LONG, s)));
        assertThat(out).containsExactly(row("v0", "v3", "v1", 4L, 6L));
    }

    /// NULL keys form one group of their own; a DOUBLE key and a VARCHAR key group by value.
    @Test
    void nullKeysFormOneGroup() {
        final List<Field> fields = List.of(field("d", CdbType.DOUBLE), field("t", CdbType.VARCHAR));
        final List<List<Object>> rows = List.of(row(null, "a"), row(1.5, null), row(null, "a"), row(1.5, null),
                row(null, null));
        final List<List<Object>> out = run(fields, rows,
                List.of(new Bound.Column(0, CdbType.DOUBLE), new Bound.Column(1, CdbType.VARCHAR)),
                List.of(agg("count", CdbType.LONG)));
        assertThat(out).containsExactly(row(null, "a", 2L), row(1.5, null, 2L), row(null, null, 1L));
    }

    /// An aggregate the cursor cannot build fails its constructor, which closes the child.
    @Test
    void aFailedConstructorClosesTheChild() {
        final AggRows.Source source = AggRows.source(fields(), 4, List.of());
        final Plan.Aggregate spec = new Plan.Aggregate(null, List.of(), List.of(agg("median_of_nothing",
                CdbType.LONG, X)), List.of(field("m", CdbType.LONG)));
        assertThatThrownBy(() -> new GroupByCursor(source, spec, 0)).isInstanceOf(CdbException.class);
        assertThat(source.closed()).isTrue();
    }

    private static List<Field> fields() {
        return List.of(field("x", CdbType.LONG), field("k", CdbType.LONG), field("a", CdbType.LONG),
                field("b", CdbType.LONG));
    }

    private static List<List<Object>> run(
            final List<List<Object>> rows,
            final List<Bound> keys,
            final List<Bound.Agg> aggs
    ) {
        final List<Field> in = fields().subList(0, rows.isEmpty() ? 1 : rows.getFirst().size());
        return run(in, rows, keys, aggs);
    }

    private static List<List<Object>> run(
            final List<Field> in,
            final List<List<Object>> rows,
            final List<Bound> keys,
            final List<Bound.Agg> aggs
    ) {
        final List<Field> out = new ArrayList<>();
        for (final Bound key : keys) {
            out.add(field("k" + out.size(), key.type()));
        }
        for (final Bound.Agg agg : aggs) {
            out.add(field("a" + out.size(), agg.type()));
        }
        final Plan.Aggregate spec = new Plan.Aggregate(null, keys, aggs, out);
        return AggRows.drain(new GroupByCursor(AggRows.source(in, Page.CAPACITY, rows), spec, 0));
    }

    private static Bound.Agg agg(final String function, final CdbType type, final Bound... args) {
        return new Bound.Agg(function, List.of(args), false, type);
    }

    private static Bound dbl(final double value) {
        return new Bound.Constant(value, CdbType.DOUBLE);
    }

    private static Bound lng(final long value) {
        return new Bound.Constant(value, CdbType.LONG);
    }
}
