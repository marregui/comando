package dev.marregui.comando.cdb.catalog;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.Cdb;
import dev.marregui.comando.cdb.CdbAppender;
import dev.marregui.comando.cdb.CdbResult;

import static org.assertj.core.api.Assertions.assertThat;

/// `wal_tables()` and `wal_apply_stats()` over a real store: one row per table in name order, QuestDB's column names
/// for the first and the twenty components of the store's status for the second.
class WalListingsTest {

    private static final long NOV_10_MICROS = 1_762_732_800_000_000L;

    @TempDir
    private Path root;

    private static void write(final Cdb cdb, final String table, final int rows) {
        try (final CdbAppender appender = cdb.appender()) {
            for (int i = 0; i < rows; i++) {
                appender.table(table).symbol("s", "a").at(NOV_10_MICROS + i);
            }
            appender.flush();
        }
    }

    private static List<String> columnNames(final CdbResult result) {
        return IntStream.range(0, result.columnCount()).mapToObj(result::columnName).toList();
    }

    /// `select * from wal_tables()` gives QuestDB's eight columns in QuestDB's order and one row per table, sorted by
    /// name, a table with no failure not suspended, with empty error columns, and its last answered transaction not
    /// behind the last published one.
    @Test
    void walTablesListsEachTableWithQuestDbsColumns() {
        try (final Cdb cdb = Cdb.open(root)) {
            write(cdb, "b", 3);
            write(cdb, "a", 1);
            write(cdb, "a", 2);
            final CdbResult result = cdb.run("select * from wal_tables()");
            assertThat(columnNames(result)).containsExactly("name", "suspended", "writerTxn", "bufferedTxnSize",
                    "sequencerTxn", "errorTag", "errorMessage", "memoryPressure");
            assertThat(result.rowCount()).isEqualTo(2);
            assertThat(result.getText(0, 0)).isEqualTo("a");
            assertThat(result.getText(1, 0)).isEqualTo("b");
            for (int row = 0; row < result.rowCount(); row++) {
                assertThat(result.getBoolean(row, 1)).isFalse();
                assertThat(result.getLong(row, 4)).isGreaterThanOrEqualTo(result.getLong(row, 2)).isPositive();
                assertThat(result.getText(row, 5)).isEmpty();
                assertThat(result.getText(row, 6)).isEmpty();
                assertThat(result.getLong(row, 7)).isZero();
            }
        }
    }

    /// `select * from wal_apply_stats()` gives the twenty columns, one row per table in name order, a table with no
    /// failure having a NULL `last_error`.
    @Test
    void walApplyStatsListsEachTableWithEveryFigure() {
        try (final Cdb cdb = Cdb.open(root)) {
            write(cdb, "b", 3);
            write(cdb, "a", 1);
            final CdbResult result = cdb.run("select * from wal_apply_stats()");
            assertThat(columnNames(result)).containsExactly("table_name", "suspended", "applied_txn", "logged_txn",
                    "pending_txns", "pending_rows", "pending_bytes", "lag_ms", "interval_ms", "applies",
                    "demanded_applies", "applied_txns", "applied_rows", "last_apply_us", "apply_us", "checkpoints",
                    "checkpoint_txn", "log_bytes", "memory_pressure", "last_error");
            assertThat(result.columnCount()).isEqualTo(20);
            assertThat(result.rowCount()).isEqualTo(2);
            assertThat(result.getText(0, 0)).isEqualTo("a");
            assertThat(result.getText(1, 0)).isEqualTo("b");
            for (int row = 0; row < result.rowCount(); row++) {
                assertThat(result.getBoolean(row, 1)).isFalse();
                assertThat(result.getLong(row, 3)).isGreaterThanOrEqualTo(result.getLong(row, 2)).isPositive();
                assertThat(result.isNull(row, 19)).isTrue();
            }
        }
    }

    /// Both listings are empty for a store with no table, and take part in a query like any source.
    @Test
    void listingsOfAnEmptyStoreHaveNoRows() {
        try (final Cdb cdb = Cdb.open(root)) {
            assertThat(cdb.run("select * from wal_tables()").rowCount()).isZero();
            assertThat(cdb.run("select table_name from wal_apply_stats() where suspended").rowCount()).isZero();
        }
    }
}
