package dev.marregui.comando.cdb.catalog;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.exec.Field;
import dev.marregui.comando.cdb.plan.Plan;
import dev.marregui.comando.cdb.store.ColumnDef;
import dev.marregui.comando.cdb.store.PartitionBy;
import dev.marregui.comando.cdb.store.Store;
import dev.marregui.comando.cdb.store.TableDef;
import dev.marregui.comando.cdb.store.TableWriter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// `table_partitions(t)` (`SHOW PARTITIONS FROM t`) and `show_wal([t])` (`SHOW WAL [FROM t]`) over a real store: their
/// fields, one row per partition or per WAL table, the filter to one table, and the refusals.
class PartitionAndWalListingsTest {

    private static final int PARTITION_NAME = 2;
    private static final int PARTITION_ROWS = 5;

    @TempDir
    Path root;

    private Store store;

    @BeforeEach
    void openStore() {
        store = Store.open(root);
    }

    /// Closes the store, so its queued commits' row blocks and its threads do not outlive the test.
    @AfterEach
    void closeStore() {
        store.close();
    }

    private static long micros(final String instant) {
        final Instant parsed = Instant.parse(instant);
        return parsed.getEpochSecond() * 1_000_000L + parsed.getNano() / 1000;
    }

    private void createTable(final String name, final PartitionBy partitionBy, final String... instants) {
        store.create(new TableDef(
                name, List.of(new ColumnDef("ts", CdbType.TIMESTAMP)), 0, partitionBy, List.of(), 0
        ), false);
        if (instants.length == 0) {
            return;
        }
        try (TableWriter writer = store.writer(name, 1000)) {
            for (final String instant : instants) {
                writer.newRow(micros(instant));
                writer.endRow();
            }
            writer.commit();
        }
    }

    private List<List<Object>> read(final String function, final String... args) {
        final List<String> argList = List.of(args);
        final Plan.Source source = new Plan.Source(function, argList, Catalog.fields(function, argList));
        return Rows.drain(Catalog.open(source, store));
    }

    /// The eight columns carry QuestDB's `SHOW PARTITIONS` names; `partitionBy` is a SYMBOL, the counts LONG.
    @Test
    void tablePartitionsHasQuestDbsNamesAndTypes() {
        assertThat(Catalog.fields("table_partitions", List.of("t"))).containsExactly(
                new Field("index", CdbType.LONG),
                new Field("partitionBy", CdbType.SYMBOL),
                new Field("name", CdbType.VARCHAR),
                new Field("minTimestamp", CdbType.TIMESTAMP),
                new Field("maxTimestamp", CdbType.TIMESTAMP),
                new Field("numRows", CdbType.LONG),
                new Field("diskSize", CdbType.LONG),
                new Field("active", CdbType.BOOLEAN)
        );
    }

    /// A DAY table written over three days lists three rows in time order: `index` from 0, the day as the name, the
    /// rows and time range each holds, a size on disk, and only the newest marked active.
    @Test
    void tablePartitionsListsEachDayOldestFirst() {
        createTable("trades", PartitionBy.DAY,
                "2026-11-11T08:00:00Z", "2026-11-10T01:00:00Z", "2026-11-12T23:59:59.123456Z",
                "2026-11-11T09:30:00Z");
        final List<List<Object>> rows = read("table_partitions", "trades");
        assertThat(rows).hasSize(3);
        assertThat(rows.stream().map(row -> row.get(0)).toList()).containsExactly(0L, 1L, 2L);
        assertThat(rows.stream().map(row -> row.get(1)).toList()).containsOnly("DAY");
        assertThat(rows.stream().map(row -> row.get(PARTITION_NAME)).toList())
                .containsExactly("2026-11-10", "2026-11-11", "2026-11-12");
        assertThat(rows.stream().map(row -> row.get(PARTITION_ROWS)).toList()).containsExactly(1L, 2L, 1L);
        assertThat(rows.get(1).get(3)).isEqualTo(micros("2026-11-11T08:00:00Z"));
        assertThat(rows.get(1).get(4)).isEqualTo(micros("2026-11-11T09:30:00Z"));
        assertThat(rows.get(2).get(4)).isEqualTo(micros("2026-11-12T23:59:59.123456Z"));
        for (final List<Object> row : rows) {
            assertThat((Long) row.get(6)).isPositive();
        }
        assertThat(rows.stream().map(row -> row.get(7)).toList()).containsExactly(false, false, true);
    }

    /// A MONTH table names its partitions by month.
    @Test
    void tablePartitionsNamesMonthsByMonth() {
        createTable("monthly", PartitionBy.MONTH, "2026-09-30T23:00:00Z", "2026-10-01T00:00:00Z");
        final List<List<Object>> rows = read("table_partitions", "monthly");
        assertThat(rows.stream().map(row -> row.get(PARTITION_NAME)).toList()).containsExactly("2026-09", "2026-10");
        assertThat(rows.stream().map(row -> row.get(1)).toList()).containsOnly("MONTH");
    }

    /// A table nothing was written to has no partition, so no row.
    @Test
    void tablePartitionsOfAnEmptyTableHasNoRows() {
        createTable("empty", PartitionBy.DAY);
        assertThat(read("table_partitions", "empty")).isEmpty();
    }

    /// The table's name matches without regard to case, as everywhere in the store.
    @Test
    void tablePartitionsMatchesTheNameWithoutRegardToCase() {
        createTable("trades", PartitionBy.DAY, "2026-11-10T01:00:00Z");
        assertThat(read("table_partitions", "TRADES")).hasSize(1);
    }

    /// A table that does not exist, or a call with no table or two, is refused with its name or its count.
    @Test
    void tablePartitionsRefusesAMissingTableAndABadArgumentCount() {
        assertThatThrownBy(() -> read("table_partitions", "nope"))
                .isInstanceOf(CdbException.class)
                .hasMessageContaining("table does not exist [table=nope]");
        assertThatThrownBy(() -> Catalog.fields("table_partitions", List.of()))
                .isInstanceOf(CdbException.class)
                .hasMessageContaining("wrong argument count");
        assertThatThrownBy(() -> Catalog.fields("table_partitions", List.of("a", "b")))
                .isInstanceOf(CdbException.class)
                .hasMessageContaining("wrong argument count");
    }

    /// The columns are those of `wal_tables()`, then `pendingRows` and `pendingTxns`.
    @Test
    void showWalHasTheWalTablesColumnsThenThePendingFigures() {
        final List<Field> fields = Catalog.fields("show_wal", List.of());
        assertThat(fields).hasSize(Listings.WAL_TABLES.size() + 2);
        assertThat(fields.subList(0, Listings.WAL_TABLES.size())).isEqualTo(Listings.WAL_TABLES);
        assertThat(fields.subList(Listings.WAL_TABLES.size(), fields.size())).containsExactly(
                new Field("pendingRows", CdbType.LONG),
                new Field("pendingTxns", CdbType.LONG)
        );
        assertThat(Catalog.fields("show_wal", List.of("t"))).isEqualTo(fields);
    }

    /// Without a table, one row per table in name order, which agrees with `wal_tables()` on what both name.
    @Test
    void showWalListsEveryTableAndAgreesWithWalTables() {
        createTable("b", PartitionBy.DAY, "2026-11-10T01:00:00Z", "2026-11-10T02:00:00Z");
        createTable("a", PartitionBy.DAY, "2026-11-10T01:00:00Z");
        final List<List<Object>> show = read("show_wal");
        final List<List<Object>> walTables = read("wal_tables");
        assertThat(show).hasSize(2);
        assertThat(show.stream().map(row -> row.get(0)).toList()).containsExactly("a", "b");
        for (int i = 0; i < show.size(); i++) {
            final List<Object> row = show.get(i);
            assertThat(row.get(0)).isEqualTo(walTables.get(i).get(0));
            assertThat(row.get(1)).isEqualTo(false);
            assertThat(row.get(4)).isEqualTo(walTables.get(i).get(4));
            assertThat(row.get(5)).isEqualTo("");
            assertThat(row.get(6)).isEqualTo("");
            assertThat((Long) row.get(8)).isGreaterThanOrEqualTo(0L);
            assertThat(row.get(9)).isEqualTo(row.get(3));
        }
    }

    /// With a table, only its row; its name matches without regard to case and gives the stored name.
    @Test
    void showWalFromATableListsOnlyThatTable() {
        createTable("b", PartitionBy.DAY, "2026-11-10T01:00:00Z");
        createTable("a", PartitionBy.DAY, "2026-11-10T01:00:00Z");
        final List<List<Object>> rows = read("show_wal", "B");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get(0)).isEqualTo("b");
    }

    /// A table that does not exist, or two arguments, are refused; a store with no table has no row.
    @Test
    void showWalRefusesAMissingTableAndHasNoRowsForAnEmptyStore() {
        assertThat(read("show_wal")).isEmpty();
        assertThatThrownBy(() -> read("show_wal", "nope"))
                .isInstanceOf(CdbException.class)
                .hasMessageContaining("table does not exist [table=nope]");
        assertThatThrownBy(() -> Catalog.fields("show_wal", List.of("a", "b")))
                .isInstanceOf(CdbException.class)
                .hasMessageContaining("wrong argument count");
    }
}
