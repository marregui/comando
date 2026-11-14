package dev.marregui.comando.cdb.exec;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.exec.agg.AggRows;

import static dev.marregui.comando.cdb.exec.agg.AggRows.field;
import static dev.marregui.comando.cdb.exec.agg.AggRows.row;
import static org.assertj.core.api.Assertions.assertThat;

/// [DistinctCursor] streams each distinct row once, in first-seen order.
class DistinctCursorTest {

    /// QuestDB: `SELECT DISTINCT (x * 7) % 5 k, x % 2 j FROM long_sequence(20)`, read three rows a page so a page
    /// with no new row is skipped.
    @Test
    void distinctRowsMatchQuestDb() {
        final List<List<Object>> rows = new ArrayList<>();
        for (long x = 1; x <= 20; x++) {
            rows.add(row(x * 7 % 5, x % 2));
        }
        final List<Field> fields = List.of(field("k", CdbType.LONG), field("j", CdbType.LONG));
        final List<List<Object>> out = AggRows.drain(new DistinctCursor(AggRows.source(fields, 3, rows)));
        assertThat(out).containsExactly(row(2L, 1L), row(4L, 0L), row(1L, 1L), row(3L, 0L), row(0L, 1L),
                row(2L, 0L), row(4L, 1L), row(1L, 0L), row(3L, 1L), row(0L, 0L));
    }

    /// NULLs are equal for DISTINCT, of every type.
    @Test
    void nullsAreOneValue() {
        final List<Field> fields = List.of(field("d", CdbType.DOUBLE), field("s", CdbType.SYMBOL),
                field("b", CdbType.BOOLEAN));
        final List<List<Object>> rows = List.of(row(null, null, true), row(null, null, true), row(1.0, "a", false),
                row(null, null, false), row(1.0, "a", false));
        final List<List<Object>> out = AggRows.drain(new DistinctCursor(AggRows.source(fields, 2, rows)));
        assertThat(out).containsExactly(row(null, null, true), row(1.0, "a", false), row(null, null, false));
    }

    /// Closing the cursor closes its child, once.
    @Test
    void closeClosesTheChild() {
        final AggRows.Source source = AggRows.source(List.of(field("x", CdbType.LONG)), 2, List.of());
        final DistinctCursor cursor = new DistinctCursor(source);
        cursor.close();
        cursor.close();
        assertThat(source.closed()).isTrue();
    }
}
