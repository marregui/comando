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
/// - find is bounded by cells and matches without regard to case; a copy is capped, selected or not
class GridTableTest {

    /// A find looks at the rows whose cells fit the bound: all of a narrow result, fewer of a wide one, at least one.
    @Test
    void findIsBoundedByCellsNotRows() {
        assertThat(GridTable.findRows(10, 1)).isEqualTo(10);
        assertThat(GridTable.findRows(1_000_000, 1)).as("the row bound").isEqualTo(GridTable.MAX_EDT_ROWS);
        assertThat(GridTable.findRows(1_000_000, 100)).as("a wide result").isEqualTo((int) (GridTable.MAX_EDT_CELLS / 100));
        assertThat(GridTable.findRows(5, 10_000_000)).as("at least the first row").isEqualTo(1);
    }

    /// The cell match ignores case and needs no allocation per cell: a needle at the start, the end, absent, or longer.
    @Test
    void cellMatchIgnoresCase() {
        String needle = GridTable.lowerCase("MaTcH");
        assertThat(GridTable.containsLowerCase(new StringBuilder("A MATCH here"), needle)).isTrue();
        assertThat(GridTable.containsLowerCase(new StringBuilder("ends in match"), needle)).isTrue();
        assertThat(GridTable.containsLowerCase(new StringBuilder("matc"), needle)).isFalse();
        assertThat(GridTable.containsLowerCase(new StringBuilder("no hit"), needle)).isFalse();
    }

    /// Copying every row selected of a result larger than the cap copies the cap: the header and the capped rows.
    @Test
    void aCopyOfAllRowsSelectedIsCapped() throws Exception {
        List<List<String>> rows = new ArrayList<>();
        for (int i = 0; i < GridTable.MAX_COPY_ROWS + 5; i++) {
            rows.add(List.of("r"));
        }
        GridTable grid = new GridTable();
        grid.setSource(TableSource.of(new ViewSpec.Table(List.of("v"), rows)), "t.csv");
        AtomicInteger lines = new AtomicInteger();
        SwingUtilities.invokeAndWait(() -> {
            grid.table().selectAll();
            lines.set((int) grid.copyText().lines().count());
        });
        assertThat(lines.get()).isEqualTo(GridTable.MAX_COPY_ROWS + 1);
    }

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
