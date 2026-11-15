package dev.marregui.comando.cdb.store;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.CdbAppender;
import dev.marregui.comando.cdb.CdbException;

import static dev.marregui.comando.cdb.store.StoreRows.micros;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The appender's discard: every row since the last flush dropped, in every table, and the current row.
///
/// - the rows flushed before stay; the rows written after the discard are flushed as usual
/// - a column only the discarded rows added is dropped with them
/// - the appender keeps its writers, so no other writer takes the table in between
class AppenderDiscardTest {

    private static final long TS = micros("2026-11-01T10:00:00Z");

    /// Rows of two tables and a row begun but not ended are dropped; the rows flushed before them stay, and the
    /// appender writes and flushes again after the discard.
    @Test
    void dropsEveryRowSinceTheLastFlushInEveryTable(@TempDir final Path root) {
        try (final Store store = Store.open(root)) {
            try (final CdbAppender appender = store.appender()) {
                appender.table("a").longColumn("n", 1L).at(TS);
                appender.flush();
                appender.table("a").longColumn("n", 2L).at(TS + 1L);
                appender.table("b").longColumn("n", 3L).at(TS + 2L);
                appender.table("b").longColumn("n", 4L);
                appender.discard();
                appender.flush();
                assertThat(rows(store, "a")).containsExactly("1|" + TS);
                assertThat(rows(store, "b")).isEmpty();
                appender.table("b").longColumn("n", 5L).at(TS + 3L);
                appender.flush();
                assertThat(rows(store, "b")).containsExactly("5|" + (TS + 3L));
            }
        }
    }

    /// A column the discarded rows added is not in the table after the discard and a flush; a later row adds it.
    @Test
    void dropsAColumnOnlyTheDiscardedRowsAdded(@TempDir final Path root) {
        try (final Store store = Store.open(root)) {
            try (final CdbAppender appender = store.appender()) {
                appender.table("a").longColumn("n", 1L).at(TS);
                appender.flush();
                appender.table("a").longColumn("n", 2L).longColumn("extra", 9L).at(TS + 1L);
                appender.discard();
                appender.flush();
                assertThat(store.def("a").columnIndex("extra")).isNegative();
                appender.table("a").longColumn("n", 3L).longColumn("extra", 7L).at(TS + 2L);
            }
            assertThat(rows(store, "a")).containsExactly("1|" + TS + "|null", "3|" + (TS + 2L) + "|7");
        }
    }

    /// The writers stay with the appender across a discard: another writer of the table is still refused.
    @Test
    void keepsTheWritersItHolds(@TempDir final Path root) {
        try (final Store store = Store.open(root)) {
            try (final CdbAppender appender = store.appender()) {
                appender.table("a").longColumn("n", 1L).at(TS);
                appender.discard();
                assertThatThrownBy(() -> store.writer("a", 0L)).isInstanceOf(CdbException.class);
            }
            store.writer("a", 0L).close();
            assertThat(rows(store, "a")).isEmpty();
        }
    }

    /// A closed appender refuses a discard.
    @Test
    void aClosedAppenderRefusesADiscard(@TempDir final Path root) {
        try (final Store store = Store.open(root)) {
            final CdbAppender appender = store.appender();
            appender.close();
            assertThatThrownBy(appender::discard).isInstanceOf(CdbException.class);
        }
    }

    private static List<String> rows(final Store store, final String table) {
        try (final TableReader r = store.reader(table)) {
            return StoreRows.rows(r);
        }
    }
}
