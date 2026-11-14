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

    /// QuestDB's columns of `tables()` that the javadoc lists, with the type the server reports, captured on 2026-11-14:
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
            new QuestDbColumn("table_row_count", "LONG"),
            new QuestDbColumn("table_min_timestamp", "TIMESTAMP"),
            new QuestDbColumn("table_max_timestamp", "TIMESTAMP")
    );

    /// QuestDB's columns of `table_columns('kit_run')` and of `SHOW COLUMNS FROM kit_run` (the same eleven; the javadoc
    /// lists these eight), captured on 2026-11-14:
    /// `curl -sG http://127.0.0.1:9000/exec --data-urlencode "query=table_columns('kit_run')"`
    /// `curl -sG http://127.0.0.1:9000/exec --data-urlencode "query=SHOW COLUMNS FROM kit_run"`
    private static final List<QuestDbColumn> QUESTDB_COLUMNS = List.of(
            new QuestDbColumn("column", "STRING"),
            new QuestDbColumn("type", "STRING"),
            new QuestDbColumn("indexed", "BOOLEAN"),
            new QuestDbColumn("indexBlockCapacity", "INT"),
            new QuestDbColumn("symbolCached", "BOOLEAN"),
            new QuestDbColumn("symbolCapacity", "INT"),
            new QuestDbColumn("designated", "BOOLEAN"),
            new QuestDbColumn("upsertKey", "BOOLEAN")
    );

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
