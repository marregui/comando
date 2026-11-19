package dev.marregui.comando.cdb.store;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.CdbAppender;
import dev.marregui.comando.cdb.CdbException;

import static dev.marregui.comando.cdb.store.StoreRows.micros;
import static dev.marregui.comando.cdb.store.StoreRows.rows;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The line-protocol appender's designated timestamp bounds and its casts, as QuestDB's ILP has them.
///
/// - a designated timestamp before the epoch, or in year 10000 or later, is refused
/// - an integer field goes into a TIMESTAMP column as its microseconds; a boolean into LONG or DOUBLE as 1 or 0
class IlpAppenderBoundsTest {

    /// 10000-01-01T00:00:00Z in microseconds since the epoch, QuestDB's first refused designated timestamp.
    private static final long YEAR_10000_MICROS = 253_402_300_800_000_000L;

    /// A negative designated timestamp, and one at year 10000, are refused and not written; 0 and the last
    /// microsecond of year 9999 are written.
    @Test
    void refusesADesignatedTimestampOutsideQuestDbsRange(@TempDir final Path root) {
        try (Store store = Store.open(root)) {
            try (CdbAppender appender = store.appender()) {
                appender.table("m").longColumn("n", 1L).at(0L);
                assertThatThrownBy(() -> appender.table("m").longColumn("n", 2L).at(-5L))
                        .isInstanceOf(CdbException.class);
                assertThatThrownBy(() -> appender.table("m").longColumn("n", 3L).at(YEAR_10000_MICROS))
                        .isInstanceOf(CdbException.class);
                appender.table("m").longColumn("n", 4L).at(YEAR_10000_MICROS - 1L);
            }
            try (TableReader r = store.reader("m")) {
                assertThat(rows(r)).containsExactly("1|0", "4|" + (YEAR_10000_MICROS - 1L));
            }
        }
    }

    /// An integer field into an existing TIMESTAMP column, and a boolean into LONG and DOUBLE columns, are written as
    /// QuestDB writes them; a string into a LONG column is still refused.
    @Test
    void castsIntegerToTimestampAndBooleanToNumbers(@TempDir final Path root) {
        final long t = micros("2026-11-01T10:00:00Z");
        try (Store store = Store.open(root)) {
            try (CdbAppender appender = store.appender()) {
                appender.table("m").timestampColumn("at", t).longColumn("n", 7L).doubleColumn("d", 0.5).at(t);
                appender.table("m").longColumn("at", t + 5L).booleanColumn("n", true).booleanColumn("d", true)
                        .at(t + 1L);
                appender.table("m").booleanColumn("n", false).booleanColumn("d", false).at(t + 2L);
                assertThatThrownBy(() -> appender.table("m").stringColumn("n", "x").at(t + 3L))
                        .isInstanceOf(CdbException.class);
            }
            try (TableReader r = store.reader("m")) {
                assertThat(rows(r)).containsExactly(
                        t + "|7|0.5|" + t,
                        (t + 5L) + "|1|1.0|" + (t + 1L),
                        "null|0|0.0|" + (t + 2L));
            }
        }
    }
}
