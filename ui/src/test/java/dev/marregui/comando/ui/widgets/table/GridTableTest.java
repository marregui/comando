package dev.marregui.comando.ui.widgets.table;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.core.view.ViewSpec;
import dev.marregui.comando.ui.widget.capability.Capabilities;
import dev.marregui.comando.ui.widget.capability.Copyable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// [GridTable]'s find, its sort and its CSV of line breaks.
/// - find forward and backward wraps at the ends, ignoring case
/// - a find without a match counts zero and moves nothing
/// - CSV quotes a field holding a carriage return or a newline
/// - find is bounded by cells and matches without regard to case; a copy is capped, selected or not
/// - CSV and copy tell SQL NULL from empty text and from the text `null`
/// - find folds case as the transcript's find does, Greek and Turkish letters included
/// - ⌘⇧C is disabled on an empty grid
/// - a sort runs off the event thread; the latest click wins, and a new source drops a sort still running
/// - a sort orders numbers as `Double.compare` does, text as `String.compareTo` does, equal keys stably, and
///   descending as the exact reverse
class GridTableTest {

    /// A click on a header returns while the sort still reads its keys, blocked on a latch: the event thread is
    /// free and the grid keeps its order; once the latch opens, the sorted order shows, on the event thread.
    @Test
    void aSortRunsOffTheEventThread() throws Exception {
        final CountDownLatch open = new CountDownLatch(1);
        final CountDownLatch reading = new CountDownLatch(1);
        final GridTable grid = new GridTable();
        SwingUtilities.invokeAndWait(() -> grid.setSource(new Keys(new double[]{3, 1, 2}, new String[]{"c", "a", "b"},
                open, reading), "t.csv"));
        final CountDownLatch clicked = new CountDownLatch(1);
        try {
            SwingUtilities.invokeLater(() -> {
                grid.model.toggleSort(1);
                clicked.countDown();
            });
            assertThat(clicked.await(5, TimeUnit.SECONDS)).as("the click returns while the keys are read").isTrue();
            assertThat(reading.await(5, TimeUnit.SECONDS)).as("the sort reads its keys").isTrue();
            assertThat(order(grid)).as("no order before the sort ends").isNull();
        } finally {
            open.countDown();
        }
        await().atMost(Duration.ofSeconds(5)).until(() -> order(grid) != null);
        assertThat(order(grid)).containsExactly(1, 2, 0);
        assertThat(sortColumn(grid)).isZero();
    }

    /// Two clicks, on the numbers and then on the text, whose sorts end in the reverse order: the text's order
    /// shows, and the numbers' sort, ending last, shows nothing.
    @Test
    void theLatestClickWins() throws Exception {
        final GridTable grid = new GridTable();
        final List<Runnable> sorts = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            grid.setSource(new Keys(new double[]{1, 2, 3}, new String[]{"c", "b", "a"}), "t.csv");
            grid.model.sorts = sorts::add;
            grid.model.toggleSort(1);
            grid.model.toggleSort(2);
        });
        assertThat(sorts).hasSize(2);
        sorts.get(1).run();
        assertThat(order(grid)).as("the text's sort").containsExactly(2, 1, 0);
        sorts.getFirst().run();
        assertThat(order(grid)).as("the numbers' sort, superseded").containsExactly(2, 1, 0);
        assertThat(sortColumn(grid)).isEqualTo(1);
    }

    /// Two clicks on one header before either sort ends sort it descending, as two clicks on a sorted grid do.
    @Test
    void aSecondClickOnTheSameHeaderReversesTheSortStillRunning() throws Exception {
        final GridTable grid = new GridTable();
        final List<Runnable> sorts = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            grid.setSource(new Keys(new double[]{2, 1, 3}, new String[]{"b", "a", "c"}), "t.csv");
            grid.model.sorts = sorts::add;
            grid.model.toggleSort(1);
            grid.model.toggleSort(1);
        });
        sorts.get(1).run();
        assertThat(order(grid)).containsExactly(2, 0, 1);
    }

    /// A sort whose order reaches the event thread after a new source was set shows nothing: the new source is
    /// shown unsorted.
    @Test
    void aNewSourceDropsTheSortStillRunning() throws Exception {
        final GridTable grid = new GridTable();
        final List<Runnable> sorts = new ArrayList<>();
        final Keys other = new Keys(new double[]{9, 8}, new String[]{"y", "x"});
        SwingUtilities.invokeAndWait(() -> {
            grid.setSource(new Keys(new double[]{3, 1, 2}, new String[]{"c", "a", "b"}), "t.csv");
            grid.model.sorts = sorts::add;
            grid.model.toggleSort(1);
        });
        final CountDownLatch go = new CountDownLatch(1);
        // the event thread sets the new source only after the sort has queued its order behind it
        SwingUtilities.invokeLater(() -> {
            awaitOn(go);
            grid.setSource(other, "u.csv");
        });
        sorts.getFirst().run();
        go.countDown();
        assertThat(order(grid)).as("the old source's order").isNull();
        assertThat(sortColumn(grid)).isEqualTo(-1);
        final AtomicReference<TableSource> shown = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> shown.set(grid.source()));
        assertThat(shown.get()).isSameAs(other);
    }

    /// Numbers ascending put negative infinity, -0.0, 0.0 and the NaNs last, as `Double.compare` does, equal keys in
    /// row order; text ascending orders by `String.compareTo` (the empty text, then upper case, then lower); each
    /// descending is the exact reverse, equal keys included; 1,000 rows of 7 keys, past one insertion run, are stable.
    @Test
    void aSortKeepsItsOrderingSemantics() throws Exception {
        final GridTable grid = new GridTable();
        SwingUtilities.invokeAndWait(() -> {
            grid.setSource(new Keys(
                    new double[]{2.0, Double.NaN, -0.0, 1.0, 0.0, 1.0, Double.NEGATIVE_INFINITY, Double.NaN},
                    new String[]{"b", "a", "B", "a", "", "b", "a", "B"}), "t.csv");
            grid.model.sorts = Runnable::run;
        });
        assertThat(click(grid, 1)).as("numbers ascending").containsExactly(6, 2, 4, 3, 5, 0, 1, 7);
        assertThat(click(grid, 1)).as("numbers descending").containsExactly(7, 1, 0, 5, 3, 4, 2, 6);
        assertThat(click(grid, 2)).as("text ascending").containsExactly(4, 2, 7, 1, 3, 6, 0, 5);
        assertThat(click(grid, 2)).as("text descending").containsExactly(5, 0, 6, 3, 1, 7, 2, 4);

        final int rows = 1_000;
        final double[] numbers = new double[rows];
        final String[] texts = new String[rows];
        for (int i = 0; i < rows; i++) {
            numbers[i] = (i * 3) % 7;
            texts[i] = "k" + (i * 3) % 7;
        }
        final int[] expected = new int[rows];
        int at = 0;
        for (int key = 0; key < 7; key++) {
            for (int i = 0; i < rows; i++) {
                if (numbers[i] == key) {
                    expected[at++] = i;
                }
            }
        }
        final Keys many = new Keys(numbers, texts);
        assertThat(GridTable.sortedRows(many, 0, rows, true, () -> false)).as("numbers, stable")
                .containsExactly(expected);
        assertThat(GridTable.sortedRows(many, 1, rows, true, () -> false)).as("text, stable")
                .containsExactly(expected);
        final int[] reversed = new int[rows];
        for (int i = 0; i < rows; i++) {
            reversed[i] = expected[rows - 1 - i];
        }
        assertThat(GridTable.sortedRows(many, 0, rows, false, () -> false)).as("the exact reverse")
                .containsExactly(reversed);
    }

    /** Clicks the header of {@code modelColumn} and returns the order shown once the sort ends. */
    private static int[] click(final GridTable grid, final int modelColumn) throws Exception {
        SwingUtilities.invokeAndWait(() -> grid.model.toggleSort(modelColumn));
        return order(grid);
    }

    /** The order the grid shows, read on the event thread after what is queued there. */
    private static int[] order(final GridTable grid) throws Exception {
        final AtomicReference<int[]> order = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> order.set(grid.model.order));
        return order.get();
    }

    private static int sortColumn(final GridTable grid) throws Exception {
        final AtomicInteger column = new AtomicInteger();
        SwingUtilities.invokeAndWait(() -> column.set(grid.model.sortColumn));
        return column.get();
    }

    /** Waits for {@code latch}, a bounded time, keeping the interrupt. */
    private static void awaitOn(final CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /// A find looks at the rows whose cells fit the bound: all of a narrow result, fewer of a wide one, at least one.
    @Test
    void findIsBoundedByCellsNotRows() {
        assertThat(GridTable.findRows(10, 1)).isEqualTo(10);
        assertThat(GridTable.findRows(1_000_000, 1)).as("the row bound").isEqualTo(GridTable.MAX_EDT_ROWS);
        assertThat(GridTable.findRows(1_000_000, 100)).as("a wide result").isEqualTo((int) (GridTable.MAX_EDT_CELLS / 100));
        assertThat(GridTable.findRows(5, 10_000_000)).as("at least the first row").isEqualTo(1);
    }

    /// A find for `ΛΌΓΟΣ` selects the row holding `λόγος`, whose final `ς` lowers no further, and a find for
    /// `istanbul` selects the row holding `İstanbul`: the grid folds as the transcript's find does.
    @Test
    void findFoldsGreekAndTurkishLettersAsTheTranscriptDoes() throws Exception {
        final AtomicInteger greek = new AtomicInteger(-1);
        final AtomicInteger greekRow = new AtomicInteger(-1);
        final AtomicInteger turkish = new AtomicInteger(-1);
        final AtomicInteger turkishRow = new AtomicInteger(-1);
        SwingUtilities.invokeAndWait(() -> {
            final GridTable grid = new GridTable();
            grid.setSource(TableSource.of(new ViewSpec.Table(List.of("v"),
                    List.of(List.of("alpha"), List.of("λόγος"), List.of("beta"), List.of("İstanbul")))), "t.csv");
            greek.set(grid.find("ΛΌΓΟΣ", true));
            greekRow.set(grid.table().getSelectedRow());
            turkish.set(grid.find("istanbul", true));
            turkishRow.set(grid.table().getSelectedRow());
        });
        assertThat(greek.get()).isEqualTo(1);
        assertThat(greekRow.get()).isEqualTo(1);
        assertThat(turkish.get()).isEqualTo(1);
        assertThat(turkishRow.get()).isEqualTo(3);
    }

    /// The grid's [Copyable], the one ⌘⇧C asks, answers false with no result and with a result of no rows, and true
    /// once the result holds a row.
    @Test
    void copyIsDisabledOnAnEmptyGrid() throws Exception {
        final AtomicBoolean noSource = new AtomicBoolean(true);
        final AtomicBoolean noRows = new AtomicBoolean(true);
        final AtomicBoolean oneRow = new AtomicBoolean(false);
        SwingUtilities.invokeAndWait(() -> {
            final GridTable grid = new GridTable();
            final Copyable copy = Capabilities.at(grid.component(), Copyable.class);
            noSource.set(copy.canCopy());
            grid.setSource(TableSource.of(new ViewSpec.Table(List.of("v"), List.of())), "t.csv");
            noRows.set(copy.canCopy());
            grid.setSource(TableSource.of(new ViewSpec.Table(List.of("v"), List.of(List.of("r")))), "t.csv");
            oneRow.set(copy.canCopy());
        });
        assertThat(noSource.get()).as("no result").isFalse();
        assertThat(noRows.get()).as("a result of no rows").isFalse();
        assertThat(oneRow.get()).as("a result of one row").isTrue();
    }

    /// Copying every row selected of a result larger than the cap copies the cap: the header and the capped rows.
    @Test
    void aCopyOfAllRowsSelectedIsCapped() throws Exception {
        final List<List<String>> rows = new ArrayList<>();
        for (int i = 0; i < GridTable.MAX_COPY_ROWS + 5; i++) {
            rows.add(List.of("r"));
        }
        final GridTable grid = new GridTable();
        grid.setSource(TableSource.of(new ViewSpec.Table(List.of("v"), rows)), "t.csv");
        final AtomicInteger lines = new AtomicInteger();
        SwingUtilities.invokeAndWait(() -> {
            grid.table().selectAll();
            lines.set((int) grid.copyText().lines().count());
        });
        assertThat(lines.get()).isEqualTo(GridTable.MAX_COPY_ROWS + 1);
    }

    /** Ten rows of one column; rows 3, 5 and 8 hold the word. */
    private static GridTable grid() {
        final List<List<String>> rows = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            rows.add(List.of(i == 3 || i == 5 || i == 8 ? "a match " + i : "row " + i));
        }
        final GridTable grid = new GridTable();
        grid.setSource(TableSource.of(new ViewSpec.Table(List.of("v"), rows)), "t.csv");
        return grid;
    }

    private static int selectedRow(final GridTable grid) throws Exception {
        final AtomicInteger row = new AtomicInteger();
        SwingUtilities.invokeAndWait(() -> row.set(grid.table().getSelectedRow()));
        return row.get();
    }

    private static int find(final GridTable grid, final boolean forward) throws Exception {
        final AtomicInteger matches = new AtomicInteger();
        SwingUtilities.invokeAndWait(() -> matches.set(grid.find("MATCH", forward)));
        return matches.get();
    }

    /// Searching "MATCH" forward counts three matches and selects rows 3, 5, 8, then wraps to 3.
    @Test
    void findForwardWrapsToTheFirstMatch() throws Exception {
        final GridTable grid = grid();
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
        final GridTable grid = grid();
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
        final GridTable grid = grid();
        find(grid, true);
        assertThat(selectedRow(grid)).isEqualTo(3);
        final AtomicInteger none = new AtomicInteger(-1);
        final AtomicInteger empty = new AtomicInteger(-1);
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
    void csvQuotesACarriageReturn(@TempDir final Path dir) throws Exception {
        assertThat(GridTable.csv("line1\rline2")).isEqualTo("\"line1\rline2\"");
        assertThat(GridTable.csv("line1\nline2")).isEqualTo("\"line1\nline2\"");

        final GridTable grid = new GridTable();
        grid.setSource(TableSource.of(new ViewSpec.Table(List.of("id", "v"), List.of(List.of("a", "x\ry")))), "t.csv");
        final Path file = dir.resolve("t.csv");
        grid.prepare().writeTo(file);
        assertThat(Files.readString(file)).isEqualTo("id,v\na,\"x\ry\"\n");
    }

    /// Rows `(NULL)`, `('')` and `('null')`: the CSV writes an empty field, `""` and `null`, and the copy an empty
    /// cell, an empty cell and `null`, so a NULL is never read back as the text `null`.
    @Test
    void csvAndCopyTellNullFromEmptyAndFromTheTextNull(@TempDir final Path dir) throws Exception {
        final GridTable grid = new GridTable();
        grid.setSource(new Texts(new String[]{null, "", "null"}), "t.csv");
        final Path file = dir.resolve("t.csv");
        grid.prepare().writeTo(file);
        assertThat(Files.readString(file)).isEqualTo("v\n\n\"\"\nnull\n");
        final AtomicReference<String> copied = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> copied.set(grid.copyText()));
        assertThat(copied.get()).isEqualTo("v\n\n\nnull\n");
    }

    /** One text column {@code v} whose Java nulls are SQL NULLs, as a live result has them. */
    private record Texts(String[] values) implements TableSource {

        @Override
        public int rowCount() {
            return values.length;
        }

        @Override
        public int columnCount() {
            return 1;
        }

        @Override
        public String columnName(final int column) {
            return "v";
        }

        @Override
        public String typeName(final int column) {
            return "text";
        }

        @Override
        public boolean isNumeric(final int column) {
            return false;
        }

        @Override
        public boolean isNull(final int row, final int column) {
            return values[row] == null;
        }

        @Override
        public void format(final int row, final int column, final StringBuilder sink) {
            sink.append(values[row]);
        }

        @Override
        public double numeric(final int row, final int column) {
            return Double.NaN;
        }
    }

    /**
     * A numeric column {@code n} and a text column {@code t}. With a {@code gate}, reading a number counts down
     * {@code reading} and waits for the gate, so a sort blocks on its keys until the test opens it.
     */
    private record Keys(double[] numbers, String[] texts, CountDownLatch gate, CountDownLatch reading)
            implements TableSource {

        Keys(final double[] numbers, final String[] texts) {
            this(numbers, texts, null, null);
        }

        @Override
        public int rowCount() {
            return numbers.length;
        }

        @Override
        public int columnCount() {
            return 2;
        }

        @Override
        public String columnName(final int column) {
            return column == 0 ? "n" : "t";
        }

        @Override
        public String typeName(final int column) {
            return column == 0 ? "double" : "text";
        }

        @Override
        public boolean isNumeric(final int column) {
            return column == 0;
        }

        @Override
        public boolean isNull(final int row, final int column) {
            return false;
        }

        @Override
        public void format(final int row, final int column, final StringBuilder sink) {
            if (column == 0) {
                sink.append(numbers[row]);
            } else {
                sink.append(texts[row]);
            }
        }

        @Override
        public double numeric(final int row, final int column) {
            if (gate != null) {
                reading.countDown();
                awaitOn(gate);
            }
            return numbers[row];
        }
    }
}
