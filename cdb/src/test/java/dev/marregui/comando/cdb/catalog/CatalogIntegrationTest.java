package dev.marregui.comando.cdb.catalog;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
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

/// The listings over a real store: `tables()`, `table_columns`, `SHOW COLUMNS`, `SHOW TABLES`, with the values the
/// store holds. Needs the store built.
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
        assertThat(read("tables")).containsExactly(
                Arrays.asList(1L, "empty", "ts", "MONTH", true, false, 0L, "HOUR", 0L, null, null),
                Arrays.asList(
                        2L, "trades", "ts", "DAY", true, true, 0L, "HOUR", 3L,
                        micros("2026-11-10T01:00:00Z"), micros("2026-11-12T23:59:59.123456Z")
                )
        );
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

    /// `table_columns(t)` lists the columns in table order with the designated and upsert flags and SYMBOL options.
    @Test
    void tableColumnsListsTheColumnsOfATable() {
        createTrades();
        assertThat(read("table_columns", "trades")).containsExactly(
                List.of("ts", "TIMESTAMP", false, 0L, false, 0L, true, true),
                List.of("sym", "SYMBOL", false, 256L, true, 256L, false, true),
                List.of("price", "DOUBLE", false, 0L, false, 0L, false, false)
        );
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
        final Vector minimum = cursor.next().columns[9];
        assertThat(minimum.longs[0]).isEqualTo(Vector.NULL_LONG);
        cursor.close();
    }
}
