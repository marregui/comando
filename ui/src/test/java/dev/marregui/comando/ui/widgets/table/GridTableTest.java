package dev.marregui.comando.ui.widgets.table;

import dev.marregui.comando.core.view.ViewSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.SwingUtilities;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/// [GridTable]'s find and its CSV of line breaks.
/// - find forward and backward wraps at the ends, ignoring case
/// - a find without a match counts zero and moves nothing
/// - CSV quotes a field holding a carriage return or a newline
class GridTableTest {

    /** Ten rows of one column; rows 3, 5 and 8 hold the word. */
    private static GridTable grid() {
        List<List<String>> rows = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            rows.add(List.of(i == 3 || i == 5 || i == 8 ? "a match " + i : "row " + i));
        }
        GridTable grid = new GridTable();
        grid.setSource(TableSource.of(new ViewSpec.Table(List.of("v"), rows)), "t.csv");
        return grid;
    }

    private static int selectedRow(GridTable grid) throws Exception {
        AtomicInteger row = new AtomicInteger();
        SwingUtilities.invokeAndWait(() -> row.set(grid.table().getSelectedRow()));
        return row.get();
    }

    private static int find(GridTable grid, boolean forward) throws Exception {
        AtomicInteger matches = new AtomicInteger();
        SwingUtilities.invokeAndWait(() -> matches.set(grid.find("MATCH", forward)));
        return matches.get();
    }

    /// Searching "MATCH" forward counts three matches and selects rows 3, 5, 8, then wraps to 3.
    @Test
    void findForwardWrapsToTheFirstMatch() throws Exception {
        GridTable grid = grid();
        assertThat(find(grid, true)).isEqualTo(3);
        assertThat(selectedRow(grid)).isEqualTo(3);
        find(grid, true);
        assertThat(selectedRow(grid)).isEqualTo(5);
        find(grid, true);
        assertThat(selectedRow(grid)).isEqualTo(8);
        find(grid, true);
        assertThat(selectedRow(grid)).as("past the last match: the first").isEqualTo(3);
    }

    /// A first backward search selects the last match, row 8, then 5 and 3, and wraps to 8.
    @Test
    void findBackwardsWrapsToTheLastMatch() throws Exception {
        GridTable grid = grid();
        find(grid, false);
        assertThat(selectedRow(grid)).as("a first find backwards selects the last match").isEqualTo(8);
        find(grid, false);
        assertThat(selectedRow(grid)).isEqualTo(5);
        find(grid, false);
        assertThat(selectedRow(grid)).isEqualTo(3);
        find(grid, false);
        assertThat(selectedRow(grid)).as("before the first match: the last").isEqualTo(8);
    }

    /// A query that matches no cell counts zero and leaves the selection where it was; an empty query counts zero.
    @Test
    void findWithoutAMatchKeepsTheSelection() throws Exception {
        GridTable grid = grid();
        find(grid, true);
        assertThat(selectedRow(grid)).isEqualTo(3);
        AtomicInteger none = new AtomicInteger(-1);
        AtomicInteger empty = new AtomicInteger(-1);
        SwingUtilities.invokeAndWait(() -> {
            none.set(grid.find("absent", true));
            empty.set(grid.find("", true));
        });
        assertThat(none.get()).isZero();
        assertThat(empty.get()).isZero();
        assertThat(selectedRow(grid)).as("no match moves nothing").isEqualTo(3);
    }

    /// A field with `\r` or `\n` is quoted, and an export writes a `\r` cell quoted.
    @Test
    void csvQuotesACarriageReturn(@TempDir Path dir) throws Exception {
        assertThat(GridTable.csv("line1\rline2")).isEqualTo("\"line1\rline2\"");
        assertThat(GridTable.csv("line1\nline2")).isEqualTo("\"line1\nline2\"");

        GridTable grid = new GridTable();
        grid.setSource(TableSource.of(new ViewSpec.Table(List.of("id", "v"), List.of(List.of("a", "x\ry")))), "t.csv");
        Path file = dir.resolve("t.csv");
        grid.prepare().writeTo(file);
        assertThat(Files.readString(file)).isEqualTo("id,v\na,\"x\ry\"\n");
    }
}
