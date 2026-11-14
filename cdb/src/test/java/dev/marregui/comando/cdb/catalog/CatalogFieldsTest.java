package dev.marregui.comando.cdb.catalog;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.exec.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The output fields of every catalog function: QuestDB's names and types, checked against what the live server
/// reported, and the planner-facing errors.
class CatalogFieldsTest {

    /// A column as the live QuestDB reports it: its name and its type name.
    private record QuestDbColumn(String name, String type) {
    }

    /// QuestDB's 45 columns of `tables()`, with the type the server reports, captured on 2026-11-14:
    /// `curl -sG http://127.0.0.1:9000/exec --data-urlencode "query=tables()"`
    private static final List<QuestDbColumn> QUESTDB_TABLES = List.of(
            new QuestDbColumn("id", "INT"),
            new QuestDbColumn("table_name", "STRING"),
            new QuestDbColumn("designatedTimestamp", "STRING"),
            new QuestDbColumn("partitionBy", "STRING"),
            new QuestDbColumn("walEnabled", "BOOLEAN"),
            new QuestDbColumn("dedup", "BOOLEAN"),
            new QuestDbColumn("ttlValue", "INT"),
            new QuestDbColumn("ttlUnit", "STRING"),
            new QuestDbColumn("matView", "BOOLEAN"),
            new QuestDbColumn("directoryName", "STRING"),
            new QuestDbColumn("maxUncommittedRows", "INT"),
            new QuestDbColumn("o3MaxLag", "LONG"),
            new QuestDbColumn("table_suspended", "BOOLEAN"),
            new QuestDbColumn("table_type", "CHAR"),
            new QuestDbColumn("table_row_count", "LONG"),
            new QuestDbColumn("table_min_timestamp", "TIMESTAMP"),
            new QuestDbColumn("table_max_timestamp", "TIMESTAMP"),
            new QuestDbColumn("table_last_write_timestamp", "TIMESTAMP"),
            new QuestDbColumn("table_txn", "LONG"),
            new QuestDbColumn("table_memory_pressure_level", "INT"),
            new QuestDbColumn("table_write_amp_count", "LONG"),
            new QuestDbColumn("table_write_amp_p50", "DOUBLE"),
            new QuestDbColumn("table_write_amp_p90", "DOUBLE"),
            new QuestDbColumn("table_write_amp_p99", "DOUBLE"),
            new QuestDbColumn("table_write_amp_max", "DOUBLE"),
            new QuestDbColumn("table_merge_rate_count", "LONG"),
            new QuestDbColumn("table_merge_rate_p50", "LONG"),
            new QuestDbColumn("table_merge_rate_p90", "LONG"),
            new QuestDbColumn("table_merge_rate_p99", "LONG"),
            new QuestDbColumn("table_merge_rate_max", "LONG"),
            new QuestDbColumn("wal_pending_row_count", "LONG"),
            new QuestDbColumn("wal_dedup_row_count_since_start", "LONG"),
            new QuestDbColumn("wal_txn", "LONG"),
            new QuestDbColumn("wal_max_timestamp", "TIMESTAMP"),
            new QuestDbColumn("wal_tx_count", "LONG"),
            new QuestDbColumn("wal_tx_size_p50", "LONG"),
            new QuestDbColumn("wal_tx_size_p90", "LONG"),
            new QuestDbColumn("wal_tx_size_p99", "LONG"),
            new QuestDbColumn("wal_tx_size_max", "LONG"),
            new QuestDbColumn("replica_batch_count", "LONG"),
            new QuestDbColumn("replica_batch_size_p50", "LONG"),
            new QuestDbColumn("replica_batch_size_p90", "LONG"),
            new QuestDbColumn("replica_batch_size_p99", "LONG"),
            new QuestDbColumn("replica_batch_size_max", "LONG"),
            new QuestDbColumn("replica_more_pending", "BOOLEAN")
    );

    /// QuestDB's columns of `table_columns('kit_run')` and of `SHOW COLUMNS FROM kit_run`, the same eleven, captured on
    /// 2026-11-14:
    /// `curl -sG http://127.0.0.1:9000/exec --data-urlencode "query=table_columns('kit_run')"`
    /// `curl -sG http://127.0.0.1:9000/exec --data-urlencode "query=SHOW COLUMNS FROM kit_run"`
    private static final List<QuestDbColumn> QUESTDB_COLUMNS = List.of(
            new QuestDbColumn("column", "STRING"),
            new QuestDbColumn("type", "STRING"),
            new QuestDbColumn("indexed", "BOOLEAN"),
            new QuestDbColumn("indexBlockCapacity", "INT"),
            new QuestDbColumn("symbolCached", "BOOLEAN"),
            new QuestDbColumn("symbolCapacity", "INT"),
            new QuestDbColumn("symbolTableSize", "INT"),
            new QuestDbColumn("designated", "BOOLEAN"),
            new QuestDbColumn("upsertKey", "BOOLEAN"),
            new QuestDbColumn("indexType", "STRING"),
            new QuestDbColumn("indexInclude", "STRING")
    );

    /// The column of `SHOW CREATE TABLE kit_run`, captured on 2026-11-14:
    /// `curl -sG http://127.0.0.1:9000/exec --data-urlencode "query=SHOW CREATE TABLE kit_run"`
    private static final List<QuestDbColumn> QUESTDB_SHOW_CREATE_TABLE = List.of(new QuestDbColumn("ddl", "VARCHAR"));

    /// The column of `SHOW TABLES`, captured on 2026-11-14:
    /// `curl -sG http://127.0.0.1:9000/exec --data-urlencode "query=SHOW TABLES"`
    private static final List<QuestDbColumn> QUESTDB_SHOW_TABLES = List.of(new QuestDbColumn("table_name", "STRING"));

    /// The column of `long_sequence(3)`, captured on 2026-11-14:
    /// `curl -sG http://127.0.0.1:9000/exec --data-urlencode "query=long_sequence(3)"`
    private static final List<QuestDbColumn> QUESTDB_LONG_SEQUENCE = List.of(new QuestDbColumn("x", "LONG"));

    private static void assertMatchesQuestDb(final List<Field> actual, final List<QuestDbColumn> questDb) {
        assertThat(actual).extracting(Field::name).containsExactlyElementsOf(questDb.stream().map(QuestDbColumn::name).toList());
        assertThat(actual).extracting(Field::type)
                .containsExactlyElementsOf(questDb.stream().map(c -> Rows.mapQuestDbType(c.type())).toList());
    }

    /// `tables()` has QuestDB's column names and types, in QuestDB's order.
    @Test
    void tablesFieldsEqualQuestDbs() {
        assertMatchesQuestDb(Catalog.fields("tables", List.of()), QUESTDB_TABLES);
    }

    /// `table_columns(t)` has QuestDB's column names and types, in QuestDB's order.
    @Test
    void tableColumnsFieldsEqualQuestDbs() {
        assertMatchesQuestDb(Catalog.fields("table_columns", List.of("t")), QUESTDB_COLUMNS);
    }

    /// `SHOW COLUMNS FROM t` has QuestDB's column names and types, in QuestDB's order.
    @Test
    void showColumnsFieldsEqualQuestDbs() {
        assertMatchesQuestDb(Catalog.fields("show_columns", List.of("t")), QUESTDB_COLUMNS);
    }

    /// `SHOW CREATE TABLE t` has QuestDB's single column, and takes the table as its one argument.
    @Test
    void showCreateTableFieldEqualsQuestDbs() {
        assertMatchesQuestDb(Catalog.fields("show_create_table", List.of("t")), QUESTDB_SHOW_CREATE_TABLE);
        assertThatThrownBy(() -> Catalog.fields("show_create_table", List.of()))
                .isInstanceOf(CdbException.class)
                .hasMessage("wrong argument count [function=show_create_table, expected=1, got=0]");
    }

    /// `SHOW TABLES` has QuestDB's single column.
    @Test
    void showTablesFieldsEqualQuestDbs() {
        assertMatchesQuestDb(Catalog.fields("show_tables", List.of()), QUESTDB_SHOW_TABLES);
    }

    /// `long_sequence(n)` has the LONG column `x`.
    @Test
    void longSequenceFieldIsX() {
        assertMatchesQuestDb(Catalog.fields("long_sequence", List.of("5")), QUESTDB_LONG_SEQUENCE);
    }

    /// `one_row` has no column.
    @Test
    void oneRowHasNoFields() {
        assertThat(Catalog.fields("one_row", List.of())).isEmpty();
    }

    /// Function names match without regard to case, as SQL does.
    @Test
    void functionNamesMatchWithoutRegardToCase() {
        assertThat(Catalog.fields("TABLES", List.of())).isEqualTo(Catalog.fields("tables", List.of()));
        assertThat(Catalog.fields("Long_Sequence", List.of("1")).get(0).type()).isEqualTo(CdbType.LONG);
    }

    /// An unknown function is refused with its name.
    @Test
    void unknownFunctionIsRefused() {
        assertThatThrownBy(() -> Catalog.fields("nope", List.of()))
                .isInstanceOf(CdbException.class)
                .hasMessage("unknown table function [function=nope]");
    }

    /// A wrong number of arguments is refused with the function, the expected and the given counts.
    @Test
    void wrongArgumentCountIsRefused() {
        assertThatThrownBy(() -> Catalog.fields("table_columns", List.of()))
                .isInstanceOf(CdbException.class)
                .hasMessage("wrong argument count [function=table_columns, expected=1, got=0]");
        assertThatThrownBy(() -> Catalog.fields("tables", List.of("x")))
                .isInstanceOf(CdbException.class)
                .hasMessage("wrong argument count [function=tables, expected=0, got=1]");
    }

    /// A count that is not a number is refused with the count.
    @Test
    void nonNumericSequenceCountIsRefused() {
        assertThatThrownBy(() -> Catalog.fields("long_sequence", List.of("many")))
                .isInstanceOf(CdbException.class)
                .hasMessage("count is not a number [function=long_sequence, count=many]");
    }
}
