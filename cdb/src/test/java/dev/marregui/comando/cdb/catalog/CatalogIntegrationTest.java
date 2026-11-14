package dev.marregui.comando.cdb.catalog;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.exec.Cursor;
import dev.marregui.comando.cdb.exec.Vector;
import dev.marregui.comando.cdb.plan.Plan;
import dev.marregui.comando.cdb.store.ColumnDef;
import dev.marregui.comando.cdb.store.PartitionBy;
import dev.marregui.comando.cdb.store.Store;
import dev.marregui.comando.cdb.store.TableDef;
import dev.marregui.comando.cdb.store.TableWriter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The listings over a real store: `tables()`, `table_columns`, `SHOW COLUMNS`, `SHOW CREATE TABLE`, `SHOW TABLES`,
/// with the values the store holds. Needs the store built.
class CatalogIntegrationTest {

    private static final long HOUR_MICROS = 3_600_000_000L;
    private static final long DAY_MICROS = 24 * HOUR_MICROS;

    @TempDir
    Path root;

    private Store store;

    @BeforeEach
    void openStore() {
        store = Store.open(root);
    }

    private static long micros(final String instant) {
        final Instant parsed = Instant.parse(instant);
        return parsed.getEpochSecond() * 1_000_000L + parsed.getNano() / 1000;
    }

    private static TableDef timeOnly(final String name, final PartitionBy partitionBy, final long ttlMicros) {
        return new TableDef(name, List.of(new ColumnDef("ts", CdbType.TIMESTAMP)), 0, partitionBy, List.of(), ttlMicros);
    }

    private void insert(final String table, final String... instants) {
        try (TableWriter writer = store.writer(table, 1000)) {
            for (final String instant : instants) {
                writer.newRow(micros(instant));
                writer.endRow();
            }
            writer.commit();
        }
    }

    /// The row `tables()` gives for a table: its definition, its statistics and QuestDB's figures for a table written
    /// once (a WAL table's directory carries its id; an unpartitioned table is not WAL and has no lower bound).
    private static List<Object> tablesRow(
            final long id,
            final String name,
            final String partitionBy,
            final boolean dedup,
            final long rows,
            final Long minTimestamp,
            final Long maxTimestamp
    ) {
        final boolean wal = !partitionBy.equals("NONE");
        final List<Object> row = new ArrayList<>(Arrays.asList(
                id, name, "ts", partitionBy, wal, dedup, 0L, "HOUR", false, wal ? name + "~" + id : name, 500_000L,
                600_000_000L, false, "T", rows, wal ? minTimestamp : null, maxTimestamp, maxTimestamp, null,
                wal ? 0L : null, 0L, 0.0, 0.0, 0.0, 0.0
        ));
        row.addAll(Collections.nCopies(7, 0L));
        row.add(null);
        row.add(wal ? maxTimestamp : null);
        row.addAll(Collections.nCopies(10, 0L));
        row.add(false);
        return row;
    }

    private List<List<Object>> read(final String function, final String... args) {
        final List<String> argList = List.of(args);
        final Cursor cursor = Catalog.open(new Plan.Source(function, argList, Catalog.fields(function, argList)), store);
        return Rows.drain(cursor);
    }

    private void createTrades() {
        store.create(new TableDef(
                "trades",
                List.of(
                        new ColumnDef("ts", CdbType.TIMESTAMP),
                        new ColumnDef("sym", CdbType.SYMBOL),
                        new ColumnDef("price", CdbType.DOUBLE)
                ),
                0,
                PartitionBy.DAY,
                List.of("ts", "sym"),
                0
        ), false);
        insert("trades", "2026-11-11T08:00:00Z", "2026-11-10T01:00:00Z", "2026-11-12T23:59:59.123456Z");
    }

    /// `tables()` lists each table with its definition and its rows' count and time range across partitions.
    @Test
    void tablesListsDefinitionAndRowStatistics() {
        createTrades();
        store.create(timeOnly("empty", PartitionBy.MONTH, 0), false);
        store.create(timeOnly("plain", PartitionBy.NONE, 0), false);
        insert("plain", "2026-11-12T07:50:55Z");
        assertThat(read("tables")).containsExactly(
                tablesRow(1, "empty", "MONTH", false, 0, null, null),
                tablesRow(2, "plain", "NONE", false, 1, null, micros("2026-11-12T07:50:55Z")),
                tablesRow(3, "trades", "DAY", true, 3, micros("2026-11-10T01:00:00Z"),
                        micros("2026-11-12T23:59:59.123456Z"))
        );
        assertThat(read("tables").get(0)).hasSize(Listings.TABLES.size());
    }

    /// An unpartitioned table has no WAL, and a table with no dedup keys reports none.
    @Test
    void tablesReportsWalAndDedupFromTheDefinition() {
        store.create(new TableDef(
                "plain", List.of(new ColumnDef("ts", CdbType.TIMESTAMP)), 0, PartitionBy.NONE, List.of(), 0
        ), false);
        final List<Object> row = read("tables").get(0);
        assertThat(row.get(4)).isEqualTo(false);
        assertThat(row.get(5)).isEqualTo(false);
    }

    /// A TTL shows in the largest of week, day and hour that divides it.
    @Test
    void tablesReportsTtlInTheLargestExactUnit() {
        store.create(timeOnly("a_weeks", PartitionBy.DAY, 14 * DAY_MICROS), false);
        store.create(timeOnly("b_days", PartitionBy.DAY, 3 * DAY_MICROS), false);
        store.create(timeOnly("c_hours", PartitionBy.DAY, 36 * HOUR_MICROS), false);
        final List<List<Object>> rows = read("tables");
        assertThat(rows).extracting(r -> r.get(6)).containsExactly(2L, 3L, 36L);
        assertThat(rows).extracting(r -> r.get(7)).containsExactly("WEEK", "DAY", "HOUR");
    }

    /// `table_columns(t)` lists the columns in table order with the designated and upsert flags and a SYMBOL's
    /// defaults; the rows hold only NULL in `sym`, which counts as one symbol.
    @Test
    void tableColumnsListsTheColumnsOfATable() {
        createTrades();
        assertThat(read("table_columns", "trades")).containsExactly(
                List.of("ts", "TIMESTAMP", false, 0L, false, 0L, 0L, true, true, "", ""),
                List.of("sym", "SYMBOL", false, 256L, true, 256L, 1L, false, true, "", ""),
                List.of("price", "DOUBLE", false, 0L, false, 0L, 0L, false, false, "", "")
        );
    }

    /// A SYMBOL's capacity, cache and index show as defined, and `symbolTableSize` counts its distinct values across
    /// partitions, NULL once.
    @Test
    void tableColumnsShowsSymbolOptionsAndTableSize() {
        store.create(new TableDef("s", List.of(
                new ColumnDef("ts", CdbType.TIMESTAMP),
                new ColumnDef("a", CdbType.SYMBOL, 4096, false, true, 512),
                new ColumnDef("b", CdbType.SYMBOL)
        ), 0, PartitionBy.DAY, List.of(), 0), false);
        try (final TableWriter writer = store.writer("s", 1000)) {
            final String[] values = {"x", "y", "x", null};
            for (int i = 0; i < values.length; i++) {
                writer.newRow(micros("2026-11-1" + i + "T00:00:00Z"));
                if (values[i] != null) {
                    writer.putText(1, values[i]);
                }
                writer.putText(2, "z");
                writer.endRow();
            }
            writer.commit();
        }
        assertThat(read("table_columns", "s")).containsExactly(
                List.of("ts", "TIMESTAMP", false, 0L, false, 0L, 0L, true, false, "", ""),
                List.of("a", "SYMBOL", true, 512L, false, 4096L, 3L, false, false, "BITMAP", ""),
                List.of("b", "SYMBOL", false, 256L, true, 256L, 1L, false, false, "", "")
        );
    }

    /// `SHOW CREATE TABLE t` is QuestDB's text: tab-indented columns, a SYMBOL's NOCACHE and index but not its
    /// capacity, the designated timestamp, partitioning, TTL, BYPASS WAL for an unpartitioned table, then the dedup
    /// keys in column order.
    @Test
    void showCreateTableIsQuestDbsText() {
        store.create(new TableDef("k", List.of(
                new ColumnDef("ts", CdbType.TIMESTAMP),
                new ColumnDef("kit", CdbType.SYMBOL, 4096, true, false, 256),
                new ColumnDef("n", CdbType.SYMBOL, 256, false, true, 1024),
                new ColumnDef("v", CdbType.VARCHAR)
        ), 0, PartitionBy.MONTH, List.of("kit", "ts"), 0), false);
        store.create(timeOnly("plain", PartitionBy.NONE, 0), false);
        store.create(timeOnly("short", PartitionBy.DAY, 2 * DAY_MICROS), false);
        store.create(timeOnly("weekly", PartitionBy.DAY, 7 * DAY_MICROS), false);
        assertThat(read("show_create_table", "k")).containsExactly(List.of(
                "CREATE TABLE 'k' ( \n\tts TIMESTAMP,\n\tkit SYMBOL,\n\tn SYMBOL NOCACHE INDEX CAPACITY 1024,"
                        + "\n\tv VARCHAR\n) timestamp(ts) PARTITION BY MONTH\nDEDUP UPSERT KEYS(ts,kit);"));
        assertThat(read("show_create_table", "plain")).containsExactly(List.of(
                "CREATE TABLE 'plain' ( \n\tts TIMESTAMP\n) timestamp(ts) PARTITION BY NONE BYPASS WAL;"));
        assertThat(read("show_create_table", "short")).containsExactly(List.of(
                "CREATE TABLE 'short' ( \n\tts TIMESTAMP\n) timestamp(ts) PARTITION BY DAY TTL 2 DAYS;"));
        assertThat(read("show_create_table", "weekly")).containsExactly(List.of(
                "CREATE TABLE 'weekly' ( \n\tts TIMESTAMP\n) timestamp(ts) PARTITION BY DAY TTL 1 WEEK;"));
    }

    /// `SHOW CREATE TABLE` of a table that does not exist is refused, naming it.
    @Test
    void showCreateTableOfAMissingTableIsRefused() {
        assertThatThrownBy(() -> read("show_create_table", "nope"))
                .isInstanceOf(CdbException.class)
                .hasMessage("table does not exist [table=nope]");
    }

    /// `SHOW COLUMNS FROM t` gives the rows of `table_columns(t)`, and the table name matches without regard to case.
    @Test
    void showColumnsEqualsTableColumnsAndIgnoresCase() {
        createTrades();
        assertThat(read("show_columns", "TRADES")).isEqualTo(read("table_columns", "trades"));
    }

    /// A table that does not exist is refused, naming it.
    @Test
    void columnsOfAMissingTableAreRefused() {
        assertThatThrownBy(() -> read("table_columns", "nope"))
                .isInstanceOf(CdbException.class)
                .hasMessage("table does not exist [table=nope]");
    }

    /// `SHOW TABLES` lists the names in order; a store with no table lists none.
    @Test
    void showTablesListsTheNames() {
        assertThat(read("show_tables")).isEmpty();
        store.create(timeOnly("b", PartitionBy.DAY, 0), false);
        store.create(timeOnly("a", PartitionBy.DAY, 0), false);
        assertThat(read("show_tables")).containsExactly(List.of("a"), List.of("b"));
    }

    /// More tables than a page holds continue on the next page.
    @Test
    void showTablesPagesBeyondTheCapacity() {
        for (int i = 0; i < 1030; i++) {
            store.create(timeOnly(String.format("t%04d", i), PartitionBy.DAY, 0), false);
        }
        final Cursor cursor = Catalog.open(
                new Plan.Source("show_tables", List.of(), Catalog.fields("show_tables", List.of())), store
        );
        assertThat(Rows.pageSizes(cursor)).containsExactly(1024, 6);
    }

    /// The NULL of a timestamp cell is the LONG sentinel, so the empty table's statistics read as NULL to operators.
    @Test
    void emptyTableStatisticsAreTheTimestampNull() {
        store.create(timeOnly("empty", PartitionBy.DAY, 0), false);
        final Cursor cursor = Catalog.open(
                new Plan.Source("tables", List.of(), Catalog.fields("tables", List.of())), store
        );
        final Vector minimum = cursor.next().columns[15];
        assertThat(minimum.longs[0]).isEqualTo(Vector.NULL_LONG);
        cursor.close();
    }
}
