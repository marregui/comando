package dev.marregui.comando.cdb.exec;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.exec.agg.AggRows;
import dev.marregui.comando.cdb.plan.Plan;

import static dev.marregui.comando.cdb.exec.agg.AggRows.field;
import static dev.marregui.comando.cdb.exec.agg.AggRows.row;
import static org.assertj.core.api.Assertions.assertThat;

/// [LatestOnCursor] keeps, per partition key, the row with the largest timestamp, and answers them in timestamp
/// order.
class LatestOnCursorTest {

    private static final List<Field> FIELDS = List.of(field("ts", CdbType.TIMESTAMP), field("sym", CdbType.SYMBOL),
            field("v", CdbType.DOUBLE));

    /// The latest row per symbol, in timestamp order, the input spread over pages of two rows.
    @Test
    void latestRowPerKeyInTimestampOrder() {
        final List<List<Object>> rows = List.of(row(10L, "a", 1.0), row(20L, "b", 2.0), row(30L, "a", 3.0),
                row(15L, "c", 4.0), row(25L, "b", 5.0), row(5L, "c", 6.0), row(40L, null, 7.0));
        assertThat(run(rows, new int[] {1})).containsExactly(row(15L, "c", 4.0), row(25L, "b", 5.0),
                row(30L, "a", 3.0), row(40L, null, 7.0));
    }

    /// Of two rows of one key with the same timestamp, the later row wins.
    @Test
    void theLaterRowWinsATie() {
        final List<List<Object>> rows = List.of(row(10L, "a", 1.0), row(10L, "a", 2.0));
        assertThat(run(rows, new int[] {1})).containsExactly(row(10L, "a", 2.0));
    }

    /// Without partition columns the answer is the single latest row; with no input, nothing.
    @Test
    void noPartitionColumnsKeepTheLatestRow() {
        final List<List<Object>> rows = List.of(row(10L, "a", 1.0), row(30L, "b", 2.0), row(20L, "c", 3.0));
        assertThat(run(rows, new int[0])).containsExactly(row(30L, "b", 2.0));
        assertThat(run(List.of(), new int[0])).isEmpty();
    }

    private static List<List<Object>> run(final List<List<Object>> rows, final int[] partitionColumns) {
        final Plan.LatestOn spec = new Plan.LatestOn(new Plan.Distinct(null), 0, partitionColumns);
        return AggRows.drain(new LatestOnCursor(AggRows.source(FIELDS, 2, rows), spec));
    }
}
