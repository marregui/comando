package dev.marregui.comando.cdb.catalog;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.Cdb;
import dev.marregui.comando.cdb.CdbAppender;
import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// A SYMBOL's CREATE TABLE options end to end, SQL to store and back: what `SHOW COLUMNS` and `SHOW CREATE TABLE`
/// report, across a reopen, and the capacities QuestDB refuses.
class CatalogDdlTest {

    private static final long DAY_MICROS = 86_400_000_000L;
    private static final long NOV_10_MICROS = 1_762_732_800_000_000L;

    @TempDir
    private Path root;

    /// The options show as QuestDB rounds them, a power of two up, and survive a reopen; the other SYMBOL keeps the
    /// defaults, and its table size counts its distinct values across partitions, NULL once.
    @Test
    void symbolOptionsShowInShowColumnsAndSurviveAReopen() {
        try (final Cdb cdb = Cdb.open(root)) {
            cdb.run("CREATE TABLE t (ts TIMESTAMP, s SYMBOL CAPACITY 3 NOCACHE INDEX CAPACITY 300, d SYMBOL)"
                    + " TIMESTAMP(ts) PARTITION BY DAY");
            try (final CdbAppender appender = cdb.appender()) {
                appender.table("t").symbol("s", "a").symbol("d", "x").at(NOV_10_MICROS);
                appender.table("t").symbol("s", "b").at(NOV_10_MICROS + DAY_MICROS);
                appender.table("t").symbol("s", "a").symbol("d", "y").at(NOV_10_MICROS + 2 * DAY_MICROS);
                appender.flush();
            }
        }
        try (final Cdb cdb = Cdb.open(root)) {
            assertThat(rows(cdb.run("SHOW COLUMNS FROM t"))).containsExactly(
                    "ts|TIMESTAMP|false|0|false|0|0|true|false||",
                    "s|SYMBOL|true|512|false|4|2|false|false|BITMAP|",
                    "d|SYMBOL|false|256|true|256|3|false|false||"
            );
        }
    }

    /// `SHOW CREATE TABLE` gives the text QuestDB gives for the same definition, as one `ddl` row.
    @Test
    void showCreateTableGivesQuestDbsText() {
        try (final Cdb cdb = Cdb.open(root)) {
            cdb.run("CREATE TABLE kit_run (ts TIMESTAMP, kit SYMBOL CAPACITY 4096, run VARCHAR,"
                    + " phase SYMBOL NOCACHE INDEX, n LONG) TIMESTAMP(ts) PARTITION BY MONTH TTL 2 WEEKS"
                    + " DEDUP UPSERT KEYS(ts, run)");
            cdb.run("CREATE TABLE plain (ts TIMESTAMP, v DOUBLE)");
            final CdbResult kitRun = cdb.run("SHOW CREATE TABLE kit_run");
            assertThat(kitRun.columnName(0)).isEqualTo("ddl");
            assertThat(rows(kitRun)).containsExactly("CREATE TABLE 'kit_run' ( \n\tts TIMESTAMP,\n\tkit SYMBOL,"
                    + "\n\trun VARCHAR,\n\tphase SYMBOL NOCACHE INDEX CAPACITY 256,\n\tn LONG\n) timestamp(ts)"
                    + " PARTITION BY MONTH TTL 2 WEEKS\nDEDUP UPSERT KEYS(ts,run);");
            assertThat(rows(cdb.run("SHOW CREATE TABLE plain")))
                    .containsExactly("CREATE TABLE 'plain' ( \n\tts TIMESTAMP,\n\tv DOUBLE\n);");
        }
    }

    /// `SHOW CREATE TABLE` and `SHOW COLUMNS` of a table that does not exist are refused, naming it.
    @Test
    void showOfAMissingTableIsRefused() {
        try (final Cdb cdb = Cdb.open(root)) {
            assertThatThrownBy(() -> cdb.run("SHOW CREATE TABLE nope"))
                    .isInstanceOf(CdbException.class)
                    .hasMessage("cannot find table [table=nope]");
            assertThatThrownBy(() -> cdb.run("SHOW COLUMNS FROM nope"))
                    .isInstanceOf(CdbException.class)
                    .hasMessage("cannot find table [table=nope]");
        }
    }

    /// The capacities QuestDB refuses: a symbol capacity outside 2..2^30, a cached one over 2^25 once rounded, and an
    /// index block capacity outside 2..2^23; at the bounds they are taken.
    @Test
    void capacitiesOutOfRangeAreRefused() {
        try (final Cdb cdb = Cdb.open(root)) {
            assertRefused(cdb, "SYMBOL CAPACITY 1",
                    "symbol capacity out of range [column=s, capacity=1, min=2, max=1073741824]");
            assertRefused(cdb, "SYMBOL CAPACITY 1073741825 NOCACHE",
                    "symbol capacity out of range [column=s, capacity=1073741825, min=2, max=1073741824]");
            assertRefused(cdb, "SYMBOL CAPACITY 33554433",
                    "cached symbol capacity out of range [column=s, capacity=67108864, max=33554432]");
            assertRefused(cdb, "SYMBOL INDEX CAPACITY 1",
                    "index block capacity out of range [column=s, capacity=1, min=2, max=8388608]");
            assertRefused(cdb, "SYMBOL INDEX CAPACITY 8388609",
                    "index block capacity out of range [column=s, capacity=8388609, min=2, max=8388608]");
            cdb.run("CREATE TABLE a (ts TIMESTAMP, s SYMBOL CAPACITY 1073741824 NOCACHE INDEX CAPACITY 8388608)");
            cdb.run("CREATE TABLE b (ts TIMESTAMP, s SYMBOL CAPACITY 33554432 CACHE INDEX CAPACITY 2)");
            assertThat(rows(cdb.run("SHOW COLUMNS FROM a"))).last()
                    .isEqualTo("s|SYMBOL|true|8388608|false|1073741824|0|false|false|BITMAP|");
            assertThat(rows(cdb.run("SHOW COLUMNS FROM b"))).last()
                    .isEqualTo("s|SYMBOL|true|2|true|33554432|0|false|false|BITMAP|");
            assertThat(cdb.tables()).containsExactly("a", "b");
        }
    }

    private static void assertRefused(final Cdb cdb, final String column, final String message) {
        assertThatThrownBy(() -> cdb.run("CREATE TABLE r (ts TIMESTAMP, s " + column + ")"))
                .isInstanceOf(CdbException.class)
                .hasMessage(message);
    }

    /// Each row as its cells joined by `|`, a NULL as empty text.
    private static List<String> rows(final CdbResult result) {
        final List<String> rows = new ArrayList<>(result.rowCount());
        for (int row = 0; row < result.rowCount(); row++) {
            final StringBuilder sb = new StringBuilder();
            for (int column = 0; column < result.columnCount(); column++) {
                if (column > 0) {
                    sb.append('|');
                }
                if (!result.isNull(row, column)) {
                    sb.append(switch (result.type(column)) {
                        case BOOLEAN -> String.valueOf(result.getBoolean(row, column));
                        case LONG -> String.valueOf(result.getLong(row, column));
                        default -> result.getText(row, column);
                    });
                }
            }
            rows.add(sb.toString());
        }
        return rows;
    }
}
