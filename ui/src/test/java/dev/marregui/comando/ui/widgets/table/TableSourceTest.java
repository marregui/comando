package dev.marregui.comando.ui.widgets.table;

import dev.marregui.comando.core.view.ViewSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// [TableSource] reads a table view's cells; [GridTable] selects and exports them in the shown order.
/// - numeric columns are detected from the values, with `null` as a null cell
/// - a number is a decimal: suffixes, hex, `NaN`, `Infinity` and spaces make a column text
/// - selection and export follow the sort order
/// - CSV quotes a field only when it holds a comma or a quote
class TableSourceTest {

    /// A column of numbers and `null` is numeric with the `null` cell null; a column of words is not; values parse as
    /// doubles.
    @Test
    void numericColumnsAreDetectedFromTheValues() {
        TableSource s = TableSource.of(new ViewSpec.Table(List.of("n", "name"), List.of(List.of("1", "a"), List.of("null", "b"), List.of("2.5", "c"))));
        assertThat(s.isNumeric(0)).isTrue();
        assertThat(s.isNumeric(1)).isFalse();
        assertThat(s.isNull(1, 0)).isTrue();
        assertThat(s.numeric(2, 0)).isEqualTo(2.5);
    }

    /// `-3`, `2.5e-3` and `42` are numbers; `1d`, `2f`, `0x1p3`, `NaN`, `Infinity` and `" 7 "`, all of which
    /// [Double#parseDouble] accepts, are not, so a column holding one of them is text.
    @Test
    void onlyDecimalNumbersMakeANumericColumn() {
        for (final String number : List.of("-3", "2.5e-3", "42", "+.5", "7.")) {
            assertThat(numeric(number)).as(number).isTrue();
        }
        for (final String text : List.of("1d", "2f", "0x1p3", "NaN", "Infinity", " 7 ", "-", ".", "1e", "1e+", "")) {
            assertThat(numeric(text)).as("'%s'", text).isFalse();
        }
    }

    /** Whether a one-column table holding {@code value} and a number is numeric. */
    private static boolean numeric(final String value) {
        return TableSource.of(new ViewSpec.Table(List.of("v"), List.of(List.of(value), List.of("1")))).isNumeric(0);
    }

    /// After sorting by a column, the selected view rows map to their source rows and the export writes the rows in the
    /// sorted order.
    @Test
    void selectionAndExportFollowTheSortOrder(@TempDir Path dir) throws Exception {
        GridTable grid = new GridTable();
        grid.setSource(TableSource.of(new ViewSpec.Table(List.of("id", "v"),
                List.of(List.of("a", "3"), List.of("b", "1"), List.of("c", "2")))), "t.csv");
        grid.model.toggleSort(2); // model column 0 is the row number; 2 is v, ascending: b, c, a
        grid.table().setRowSelectionInterval(0, 1);
        assertThat(grid.selectedSourceRows()).containsExactly(1, 2);

        Path file = dir.resolve("t.csv");
        grid.prepare().writeTo(file);
        assertThat(Files.readString(file)).isEqualTo("id,v\nb,1\nc,2\na,3\n");
    }

    /// [GridTable#csv] leaves a plain field as is, quotes one with a comma, and doubles embedded quotes.
    @Test
    void csvQuotesOnlyWhenNeeded() {
        assertThat(GridTable.csv("plain")).isEqualTo("plain");
        assertThat(GridTable.csv("a,b")).isEqualTo("\"a,b\"");
        assertThat(GridTable.csv("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
    }
}
