package dev.marregui.comando.cdb.exec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.plan.Bound;
import dev.marregui.comando.cdb.plan.Plan;
import dev.marregui.comando.cdb.store.ColumnDef;
import dev.marregui.comando.cdb.store.PartitionReader;
import dev.marregui.comando.cdb.store.TableDef;

import static dev.marregui.comando.cdb.exec.RowsFakes.SIZES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// [UpdateRunner] rewrites a table through its writer in one transaction: truncate, every row appended again with
/// the assigned columns replaced where the filter holds, then a commit, or a rollback when no row matched.
class RowsUpdateTest {

    private static final TableDef TABLE = new TableDef("t", List.of(new ColumnDef("ts", CdbType.TIMESTAMP),
            new ColumnDef("x", CdbType.LONG), new ColumnDef("d", CdbType.DOUBLE), new ColumnDef("s", CdbType.SYMBOL),
            new ColumnDef("f", CdbType.BOOLEAN)), 0, null, List.of(), 0);

    /// Rows `(ts = i, x = i, d = i / 2, s = "s" + i % 3, f = i % 2 == 0)` from `first`; NULL `d` and `s` for
    /// `i % 7 == 0`.
    private static PartitionReader partition(final long first, final int n) {
        final long[] ts = new long[n];
        final long[] x = new long[n];
        final double[] d = new double[n];
        final String[] s = new String[n];
        final boolean[] f = new boolean[n];
        for (int i = 0; i < n; i++) {
            final long v = first + i;
            ts[i] = v;
            x[i] = v;
            d[i] = v % 7 == 0 ? Double.NaN : v / 2.0;
            s[i] = v % 7 == 0 ? null : "s" + v % 3;
            f[i] = v % 2 == 0;
        }
        return new RowsFakes.FakePartition(first, first + n - 1, n, new Object[] {ts, x, d, s, f});
    }

    private static List<Object> row(final long v) {
        return Arrays.asList(v, v, v % 7 == 0 ? null : v / 2.0, v % 7 == 0 ? null : "s" + v % 3, v % 2 == 0);
    }

    private static Bound constant(final Object value, final CdbType type) {
        return new Bound.Constant(value, type);
    }

    private static List<PartitionReader> table(final int n) {
        final List<PartitionReader> parts = new ArrayList<>();
        if (n > 0) {
            parts.add(partition(0, n / 2));
            parts.add(partition(n / 2, n - n / 2));
        }
        return parts;
    }

    /// `SET x = -1, s = 'z' WHERE f` changes the even rows only, keeps every other value, and commits once, at every
    /// size; with no row matched it rolls back.
    @Test
    void rewritesTheMatchedRowsAtEverySize() {
        for (final int n : SIZES) {
            final RowsFakes.FakeReader reader = new RowsFakes.FakeReader(TABLE, table(n));
            final RowsFakes.FakeWriter writer = new RowsFakes.FakeWriter(TABLE);
            final Plan.Update update = new Plan.Update("t", new int[] {1, 3},
                    List.of(constant(-1L, CdbType.LONG), constant("z", CdbType.SYMBOL)),
                    new Bound.Column(4, CdbType.BOOLEAN));
            final long changed = UpdateRunner.rewrite(update, reader, writer, 0);
            final List<List<Object>> expected = new ArrayList<>();
            for (long v = 0; v < n; v++) {
                final List<Object> row = new ArrayList<>(row(v));
                if (v % 2 == 0) {
                    row.set(1, -1L);
                    row.set(3, "z");
                }
                expected.add(row);
            }
            assertThat(changed).as("n=%d", n).isEqualTo((n + 1) / 2);
            assertThat(writer.truncated()).as("n=%d", n).isTrue();
            assertThat(writer.rows()).as("n=%d", n).isEqualTo(expected);
            assertThat(writer.commits()).as("n=%d", n).isEqualTo(n == 0 ? 0 : 1);
            assertThat(writer.rollbacks()).as("n=%d", n).isEqualTo(n == 0 ? 1 : 0);
            assertThat(reader.closes()).as("n=%d", n).isEqualTo(1);
        }
    }

    /// Without a filter every row changes; NULL is assigned as NULL, and numbers convert between LONG and DOUBLE.
    @Test
    void assignsNullsAndConvertsNumbers() {
        final RowsFakes.FakeReader reader = new RowsFakes.FakeReader(TABLE, table(3000));
        final RowsFakes.FakeWriter writer = new RowsFakes.FakeWriter(TABLE);
        final Plan.Update update = new Plan.Update("t", new int[] {1, 2, 3},
                List.of(constant(2.75, CdbType.DOUBLE), constant(3L, CdbType.LONG), constant(null, CdbType.SYMBOL)),
                null);
        assertThat(UpdateRunner.rewrite(update, reader, writer, 0)).isEqualTo(3000);
        assertThat(writer.rows()).hasSize(3000).allMatch(r -> Arrays.asList(r.get(0), 2L, 3.0, null, r.get(4))
                .equals(r));
        assertThat(writer.commits()).isEqualTo(1);
    }

    /// A filter that holds for no row leaves the table as it was: the writer rolls back.
    @Test
    void rollsBackWhenNoRowMatches() {
        final RowsFakes.FakeWriter writer = new RowsFakes.FakeWriter(TABLE);
        final Plan.Update update = new Plan.Update("t", new int[] {1}, List.of(constant(0L, CdbType.LONG)),
                constant(false, CdbType.BOOLEAN));
        assertThat(UpdateRunner.rewrite(update, new RowsFakes.FakeReader(TABLE, table(1025)), writer, 0)).isZero();
        assertThat(writer.commits()).isZero();
        assertThat(writer.rollbacks()).isEqualTo(1);
    }

    /// A table without a designated timestamp starts each row with the NULL timestamp.
    @Test
    void writesNullTimestampsForATableWithoutOne() {
        final TableDef plain = new TableDef("p", List.of(new ColumnDef("x", CdbType.LONG)), -1, null, List.of(), 0);
        final PartitionReader part = new RowsFakes.FakePartition(Long.MIN_VALUE, Long.MIN_VALUE, 2,
                new Object[] {new long[] {5, 6}});
        final RowsFakes.FakeWriter writer = new RowsFakes.FakeWriter(plain);
        final Plan.Update update = new Plan.Update("p", new int[] {0}, List.of(constant(1L, CdbType.LONG)), null);
        assertThat(UpdateRunner.rewrite(update, new RowsFakes.FakeReader(plain, List.of(part)), writer, 0))
                .isEqualTo(2);
        assertThat(writer.timestamps()).containsExactly(Vector.NULL_LONG, Vector.NULL_LONG);
        assertThat(writer.rows()).isEqualTo(RowsFakes.rows(new Object[] {1L}, new Object[] {1L}));
    }

    /// The designated timestamp, a column twice, a column the table lacks and a value of another type are refused
    /// before anything is written, and the reader is closed.
    @Test
    void refusesWhatCannotBeAssigned() {
        final List<Plan.Update> refused = List.of(
                new Plan.Update("t", new int[] {0}, List.of(constant(1L, CdbType.TIMESTAMP)), null),
                new Plan.Update("t", new int[] {1, 1}, List.of(constant(1L, CdbType.LONG),
                        constant(2L, CdbType.LONG)), null),
                new Plan.Update("t", new int[] {9}, List.of(constant(1L, CdbType.LONG)), null),
                new Plan.Update("t", new int[] {3}, List.of(constant(1L, CdbType.LONG)), null),
                new Plan.Update("t", new int[] {1}, List.of(), null));
        final List<String> reasons = List.of("designated timestamp", "twice", "column=9", "value=LONG", "values=0");
        for (int i = 0; i < refused.size(); i++) {
            final RowsFakes.FakeReader reader = new RowsFakes.FakeReader(TABLE, table(10));
            final RowsFakes.FakeWriter writer = new RowsFakes.FakeWriter(TABLE);
            final Plan.Update update = refused.get(i);
            assertThatThrownBy(() -> UpdateRunner.rewrite(update, reader, writer, 0))
                    .isInstanceOf(CdbException.class).hasMessageContaining(reasons.get(i));
            assertThat(writer.truncated()).isFalse();
            assertThat(reader.closes()).isEqualTo(1);
        }
    }
}
