package dev.marregui.comando.cdb.exec;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.plan.Plan;
import dev.marregui.comando.cdb.store.PartitionReader;

import static dev.marregui.comando.cdb.exec.RowsFakes.SIZES;
import static dev.marregui.comando.cdb.exec.RowsFakes.drain;
import static dev.marregui.comando.cdb.exec.RowsFakes.field;
import static dev.marregui.comando.cdb.exec.RowsFakes.partition;
import static dev.marregui.comando.cdb.exec.RowsFakes.range;
import static dev.marregui.comando.cdb.exec.RowsFakes.rows;
import static dev.marregui.comando.cdb.exec.RowsFakes.table;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// [ScanCursor] reads a table reader's partitions that meet the time range, the listed columns, in full pages, and
/// closes the reader after the last page or on close.
class ScanCursorTest {

    private static final List<Field> X = List.of(field("x", CdbType.LONG));

    private static Plan.Scan scan(
            final int[] columns,
            final long fromMicros,
            final long toMicros,
            final List<Field> fields
    ) {
        return new Plan.Scan("t", columns, fromMicros, toMicros, fields);
    }

    /// One partition of each size comes out whole, in order, in full pages but the last, and the reader is closed.
    @Test
    void readsOnePartitionInFullPagesAtEverySize() {
        for (final int n : SIZES) {
            final RowsFakes.FakeReader reader = new RowsFakes.FakeReader(table(),
                    n == 0 ? List.of() : List.of(partition(0, n)));
            final RowsFakes.Drained out = drain(new ScanCursor(scan(new int[] {1}, Long.MIN_VALUE, Long.MAX_VALUE, X),
                    reader));
            assertThat(out.longs()).as("n=%d", n).isEqualTo(range(0, n));
            assertThat(out.packed()).as("n=%d", n).isTrue();
            assertThat(out.pages()).hasSize((n + Page.CAPACITY - 1) / Page.CAPACITY);
            assertThat(reader.closes()).as("n=%d", n).isEqualTo(1);
        }
    }

    /// Pages are full across partition ends, an empty partition included.
    @Test
    void packsRowsAcrossPartitions() {
        final List<PartitionReader> parts = List.of(partition(0, 700), partition(700, 0), partition(700, 1500),
                partition(2200, 800));
        final RowsFakes.Drained out = drain(new ScanCursor(scan(new int[] {1}, Long.MIN_VALUE, Long.MAX_VALUE, X),
                new RowsFakes.FakeReader(table(), parts)));
        assertThat(out.sizes()).containsExactly(1024, 1024, 952);
        assertThat(out.longs()).isEqualTo(range(0, 3000));
    }

    /// A partition is kept when its rows' range meets `[from, to)`: the upper bound is exclusive, the lower inclusive.
    @Test
    void keepsThePartitionsThatMeetTheTimeRange() {
        final List<PartitionReader> parts = List.of(partition(0, 10), partition(10, 10), partition(20, 10));
        assertThat(drain(new ScanCursor(scan(new int[] {1}, 10, 20, X), new RowsFakes.FakeReader(table(), parts)))
                .longs()).isEqualTo(range(10, 20));
        assertThat(drain(new ScanCursor(scan(new int[] {1}, 19, 21, X), new RowsFakes.FakeReader(table(), parts)))
                .longs()).isEqualTo(range(10, 30));
        assertThat(drain(new ScanCursor(scan(new int[] {1}, 30, 40, X), new RowsFakes.FakeReader(table(), parts)))
                .pages()).isEmpty();
    }

    /// The listed columns come out in the listed order, NULLs of every type as NULL.
    @Test
    void readsTheListedColumnsWithTheirNulls() {
        final List<Field> fields = List.of(field("s", CdbType.SYMBOL), field("d", CdbType.DOUBLE),
                field("ts", CdbType.TIMESTAMP));
        final List<List<Object>> rows = drain(new ScanCursor(scan(new int[] {3, 2, 0}, Long.MIN_VALUE, Long.MAX_VALUE,
                fields), new RowsFakes.FakeReader(table(), List.of(partition(6, 3))))).rows();
        assertThat(rows).isEqualTo(rows(new Object[] {"s6", 3.0, 6L}, new Object[] {null, null, 7L},
                new Object[] {"s8", 4.0, 8L}));
    }

    /// Closing before the end closes the reader once, however often close is called.
    @Test
    void closeBeforeTheEndClosesTheReaderOnce() {
        final RowsFakes.FakeReader reader = new RowsFakes.FakeReader(table(), List.of(partition(0, 3000)));
        final ScanCursor cursor = new ScanCursor(scan(new int[] {1}, Long.MIN_VALUE, Long.MAX_VALUE, X), reader);
        assertThat(cursor.next().size).isEqualTo(Page.CAPACITY);
        cursor.close();
        cursor.close();
        assertThat(reader.closes()).isEqualTo(1);
        assertThat(cursor.next()).isNull();
    }

    /// A reader that fails while the cursor is built is closed before the failure reaches the caller.
    @Test
    void aFailedConstructionClosesTheReader() {
        final RowsFakes.FakeReader reader = new RowsFakes.FakeReader(table(), null);
        assertThatThrownBy(() -> new ScanCursor(scan(new int[] {1}, 0, 1, X), reader))
                .isInstanceOf(NullPointerException.class);
        assertThat(reader.closes()).isEqualTo(1);
    }
}
