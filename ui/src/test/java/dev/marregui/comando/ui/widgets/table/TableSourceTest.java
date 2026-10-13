package dev.marregui.comando.ui.widgets.table;

import dev.marregui.comando.core.view.ViewSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TableSourceTest {

    @Test
    void numericColumnsAreDetectedFromTheValues() {
        TableSource s = TableSource.of(new ViewSpec.Table(List.of("n", "name"), List.of(List.of("1", "a"), List.of("null", "b"), List.of("2.5", "c"))));
        assertThat(s.isNumeric(0)).isTrue();
        assertThat(s.isNumeric(1)).isFalse();
        assertThat(s.isNull(1, 0)).isTrue();
        assertThat(s.numeric(2, 0)).isEqualTo(2.5);
    }

    @Test
    void selectionAndExportFollowTheSortOrder(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        GridTable grid = new GridTable();
        grid.setSource(TableSource.of(new ViewSpec.Table(List.of("id", "v"),
                List.of(List.of("a", "3"), List.of("b", "1"), List.of("c", "2")))), "t.csv");
        grid.model.toggleSort(2); // model column 0 is the row number; 2 is v, ascending: b, c, a
        grid.table().setRowSelectionInterval(0, 1);
        assertThat(grid.selectedSourceRows()).containsExactly(1, 2);

        java.nio.file.Path file = dir.resolve("t.csv");
        grid.prepare().writeTo(file);
        assertThat(java.nio.file.Files.readString(file)).isEqualTo("id,v\nb,1\nc,2\na,3\n");
    }

    @Test
    void csvQuotesOnlyWhenNeeded() {
        assertThat(GridTable.csv("plain")).isEqualTo("plain");
        assertThat(GridTable.csv("a,b")).isEqualTo("\"a,b\"");
        assertThat(GridTable.csv("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
    }
}
