package dev.marregui.comando.cdb.store;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.CdbAppender;
import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;

import static dev.marregui.comando.cdb.store.StoreRows.micros;
import static dev.marregui.comando.cdb.store.StoreRows.rows;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The store's line-protocol appender creates tables and columns on first write, as QuestDB's ILP does.
///
/// - a new table: the fields in call order, then a designated `timestamp`, partitioned by day
/// - a later field adds its column; a field of an incompatible type is refused
/// - flush commits every touched table; close flushes and lets the writers go
class IlpAppenderTest {

    /// The first row creates the table with one column per field type; a second row adds a column, which reads NULL in
    /// the first row; nothing is visible before flush.
    @Test
    void createsTablesAndColumnsOnFirstWrite(@TempDir final Path root) {
        final long t = micros("2026-11-01T10:00:00Z");
        try (final Store store = Store.open(root)) {
            try (final CdbAppender appender = store.appender()) {
                appender.table("cpu").symbol("host", "h1").stringColumn("note", "x").longColumn("n", 3L)
                        .doubleColumn("load", 0.5).booleanColumn("up", true).at(t);
                appender.table("cpu").symbol("host", "h2").longColumn("load", 2L).longColumn("extra", 9L).at(t + 1L);
                assertThat(rowCount(store, "cpu")).isZero();
                appender.flush();
                assertThat(store.def("cpu")).isEqualTo(new TableDef("cpu", List.of(
                        new ColumnDef("host", CdbType.SYMBOL),
                        new ColumnDef("note", CdbType.VARCHAR),
                        new ColumnDef("n", CdbType.LONG),
                        new ColumnDef("load", CdbType.DOUBLE),
                        new ColumnDef("up", CdbType.BOOLEAN),
                        new ColumnDef("timestamp", CdbType.TIMESTAMP),
                        new ColumnDef("extra", CdbType.LONG)), 5, PartitionBy.DAY, List.of(), 0L));
                try (final TableReader r = store.reader("cpu")) {
                    assertThat(rows(r)).containsExactly(
                            "\"h1\"|\"x\"|3|0.5|true|" + t + "|null",
                            "\"h2\"|null|null|2.0|false|" + (t + 1L) + "|9");
                }
            }
            store.writer("cpu", 0L).close();
        }
    }

    /// A field whose type the existing column cannot take is refused, and the row is not written.
    @Test
    void refusesAFieldOfAnotherType(@TempDir final Path root) {
        final long t = micros("2026-11-01T10:00:00Z");
        try (final Store store = Store.open(root)) {
            try (final CdbAppender appender = store.appender()) {
                appender.table("cpu").longColumn("n", 1L).at(t);
                assertThatThrownBy(() -> appender.table("cpu").booleanColumn("n", true).at(t + 1L))
                        .isInstanceOf(CdbException.class);
                appender.table("cpu").stringColumn("s", "a").at(t + 2L);
                appender.table("cpu").symbol("s", "b").at(t + 3L);
            }
            try (final TableReader r = store.reader("cpu")) {
                assertThat(rows(r)).containsExactly(
                        "1|" + t + "|null",
                        "null|" + (t + 2L) + "|\"a\"",
                        "null|" + (t + 3L) + "|\"b\"");
            }
        }
    }

    /// Close commits what was not flushed and lets each table's writer go.
    @Test
    void closeFlushesAndReleasesTheWriters(@TempDir final Path root) {
        final long t = micros("2026-11-01T10:00:00Z");
        try (final Store store = Store.open(root)) {
            final CdbAppender appender = store.appender();
            appender.table("a").longColumn("n", 1L).at(t);
            appender.table("b").longColumn("n", 2L).at(t);
            appender.close();
            appender.close();
            assertThat(rowCount(store, "a")).isEqualTo(1L);
            assertThat(rowCount(store, "b")).isEqualTo(1L);
            store.writer("a", 0L).close();
            store.writer("b", 0L).close();
            assertThatThrownBy(() -> appender.table("a")).isInstanceOf(CdbException.class);
        }
    }

    private static long rowCount(final Store store, final String table) {
        try (final TableReader r = store.reader(table)) {
            return r.rowCount();
        }
    }
}
