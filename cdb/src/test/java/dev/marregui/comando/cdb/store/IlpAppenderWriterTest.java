package dev.marregui.comando.cdb.store;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.CdbAppender;
import dev.marregui.comando.cdb.CdbException;

import static dev.marregui.comando.cdb.store.StoreRows.micros;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/// The appender's wait for a table's writer another holds: once the store did not hand the writer over, the
/// appender's later rows of that table are refused at once with the same reason, as a group commit replays many
/// requests' rows into one appender and each would otherwise wait again.
class IlpAppenderWriterTest {

    /// The first row waits and is refused; the second is refused without waiting, its cause the first refusal; the
    /// rows of another table still go through.
    @Test
    void refusesLaterRowsOfATableWhoseWriterWasNotHandedOver(@TempDir final Path root) {
        final long t = micros("2026-11-01T10:00:00Z");
        try (Store store = Store.open(root)) {
            try (CdbAppender appender = store.appender()) {
                appender.table("held").longColumn("n", 1L).at(t);
                appender.table("free").longColumn("n", 1L).at(t);
            }
            try (TableWriter holder = store.writer("held", 0L);
                 CdbAppender appender = new IlpAppender(store, 20L)) {
                final CdbException first = catchThrowableOfType(CdbException.class,
                        () -> appender.table("held").longColumn("n", 2L).at(t + 1L));
                final long start = System.nanoTime();
                final CdbException second = catchThrowableOfType(CdbException.class,
                        () -> appender.table("HELD").longColumn("n", 3L).at(t + 2L));
                final long tookMillis = (System.nanoTime() - start) / 1_000_000L;

                assertThat(first).isNotNull();
                assertThat(second).isNotNull().hasCause(first);
                assertThat(tookMillis).isLessThan(20L);
                appender.table("free").longColumn("n", 2L).at(t + 1L);
                appender.flush();
                assertThat(holder.def().name()).isEqualTo("held");
            }
            try (TableReader reader = store.reader("free")) {
                assertThat(StoreRows.rows(reader)).hasSize(2);
            }
        }
    }
}
