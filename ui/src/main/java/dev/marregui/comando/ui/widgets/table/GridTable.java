package dev.marregui.comando.ui.widgets.table;

import java.awt.Component;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumn;

import dev.marregui.comando.ui.kit.text.CaseFold;
import dev.marregui.comando.ui.kit.text.Plain;
import dev.marregui.comando.ui.kit.theme.FocusRing;
import dev.marregui.comando.ui.kit.theme.Theme;
import dev.marregui.comando.ui.widget.capability.Capabilities;
import dev.marregui.comando.ui.widget.capability.Copyable;
import dev.marregui.comando.ui.widget.capability.Exportable;
import dev.marregui.comando.ui.widget.capability.Searchable;

/**
 * A grid over a {@link TableSource}. JTable paints only the visible rows; cells format into one reused
 * buffer. Sorting keeps a permutation of row indices instead of a sorter over boxed values. Sort and find run on the
 * event thread: a sort is bounded to {@link #MAX_EDT_ROWS} rows, and a larger result is not sorted; a find, whose cost
 * is rows times columns, to {@link #MAX_EDT_CELLS} cells, and looks at the first rows that fit. A copy takes at most
 * {@link #MAX_COPY_ROWS} rows, selected or not. A growing source adds rows without rebuilding the columns.
 */
public final class GridTable implements Searchable, Copyable, Exportable {

    static final int MAX_EDT_ROWS = 200_000;
    static final long MAX_EDT_CELLS = 2_000_000L;
    static final int MAX_COPY_ROWS = 200_000;

    final Model model = new Model(); // package-visible for tests
    private final JTable table = new JTable(model);
    private final JScrollPane scroll = new JScrollPane(table);
    private final StringBuilder cell = new StringBuilder(64);
    private String exportName = "result.csv";

    public GridTable() {
        table.setFont(Theme.mono());
        table.setRowHeight(Theme.rowHeight(table.getFontMetrics(table.getFont()).getHeight()));
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setCellSelectionEnabled(true);
        table.setFillsViewportHeight(true);
        table.setDefaultRenderer(Object.class, new Renderer());
        table.getTableHeader().setReorderingAllowed(true);
        if (table.getTableHeader().getDefaultRenderer() instanceof JComponent header) {
            Plain.only(header); // column names come from the database
        }
        table.getTableHeader().addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(final MouseEvent e) {
                final int view = table.columnAtPoint(e.getPoint());
                if (view >= 0 && e.getClickCount() == 1) {
                    model.toggleSort(table.convertColumnIndexToModel(view));
                }
            }
        });
        FocusRing.install(scroll);
        Capabilities.mark(scroll, this);
    }

    public JComponent component() {
        return scroll;
    }

    /** Shows {@code source}, resetting columns and sort. */
    public void setSource(final TableSource source, final String exportFileName) {
        exportName = exportFileName;
        model.source = source;
        model.rows = source == null ? 0 : source.rowCount();
        model.order = null;
        model.sortColumn = -1;
        model.fireTableStructureChanged();
        sizeColumns();
    }

    /** The source grew; shows the new rows. */
    public void refreshRows() {
        final TableSource s = model.source;
        if (s == null) {
            return;
        }
        final int before = model.rows;
        final int now = s.rowCount();
        if (now > before) {
            model.rows = now;
            if (model.order != null) {
                model.order = null;
                model.sortColumn = -1;
                model.fireTableDataChanged();
            } else {
                model.fireTableRowsInserted(before, now - 1);
            }
            if (before < 100) {
                sizeColumns();
            }
        }
    }

    public TableSource source() {
        return model.source;
    }

    private void sizeColumns() {
        final TableSource s = model.source;
        if (s == null) {
            return;
        }
        final FontMetrics fm = table.getFontMetrics(table.getFont());
        final int sample = Math.min(s.rowCount(), 200);
        for (int c = 0; c < table.getColumnCount(); c++) {
            final int m = table.convertColumnIndexToModel(c);
            int width = fm.stringWidth(model.getColumnName(m)) + 24;
            if (m > 0) {
                for (int r = 0; r < sample; r++) {
                    cell.setLength(0);
                    s.format(r, m - 1, cell);
                    width = Math.max(width, Math.min(fm.stringWidth(cell.toString()) + 16, 480));
                }
            } else {
                width = Math.max(fm.stringWidth(Integer.toString(Math.max(1, s.rowCount()))) + 16, 40);
            }
            final TableColumn col = table.getColumnModel().getColumn(c);
            col.setPreferredWidth(width);
        }
    }

    // ---- model

    final class Model extends AbstractTableModel {
        TableSource source;
        int rows;
        int[] order;
        int sortColumn = -1;
        boolean ascending = true;

        int sourceRow(final int row) {
            return order == null ? row : order[row];
        }

        @Override
        public int getRowCount() {
            return rows;
        }

        @Override
        public int getColumnCount() {
            return source == null ? 0 : source.columnCount() + 1;
        }

        @Override
        public String getColumnName(final int column) {
            if (column == 0) {
                return "#";
            }
            final String arrow = column - 1 == sortColumn ? (ascending ? " ▲" : " ▼") : "";
            return source.columnName(column - 1) + arrow;
        }

        @Override
        public Object getValueAt(final int row, final int column) {
            if (column == 0) {
                return sourceRow(row) + 1;
            }
            cell.setLength(0);
            source.format(sourceRow(row), column - 1, cell);
            return cell.toString();
        }

        void toggleSort(final int modelColumn) {
            if (modelColumn == 0 || source == null || rows > MAX_EDT_ROWS) {
                return;
            }
            final int c = modelColumn - 1;
            ascending = sortColumn != c || !ascending;
            sortColumn = c;
            final Integer[] idx = new Integer[rows];
            for (int i = 0; i < rows; i++) {
                idx[i] = i;
            }
            if (source.isNumeric(c)) {
                final double[] keys = new double[rows];
                for (int i = 0; i < rows; i++) {
                    keys[i] = source.numeric(i, c);
                }
                Arrays.sort(idx, (a, b) -> Double.compare(keys[a], keys[b]));
            } else {
                final String[] keys = new String[rows];
                final StringBuilder sb = new StringBuilder();
                for (int i = 0; i < rows; i++) {
                    sb.setLength(0);
                    source.format(i, c, sb);
                    keys[i] = sb.toString();
                }
                Arrays.sort(idx, (a, b) -> keys[a].compareTo(keys[b]));
            }
            final int[] o = new int[rows];
            for (int i = 0; i < rows; i++) {
                o[i] = idx[ascending ? i : rows - 1 - i];
            }
            order = o;
            fireTableDataChanged();
            table.getTableHeader().repaint();
        }
    }

    private final class Renderer extends DefaultTableCellRenderer {
        Renderer() {
            Plain.only(this); // cells hold database values: never HTML
        }

        @Override
        public Component getTableCellRendererComponent(final JTable t, final Object value, final boolean selected, final boolean focus, final int row, final int column) {
            final JLabel l = (JLabel) super.getTableCellRendererComponent(t, value, selected, focus, row, column);
            final int m = t.convertColumnIndexToModel(column);
            final TableSource s = model.source;
            if (m == 0) {
                l.setHorizontalAlignment(SwingConstants.RIGHT);
                l.setForeground(Theme.MUTED);
                l.setFont(t.getFont());
                return l;
            }
            final int r = model.sourceRow(row);
            final boolean isNull = s != null && r < s.rowCount() && s.isNull(r, m - 1);
            l.setHorizontalAlignment(s != null && s.isNumeric(m - 1) ? SwingConstants.RIGHT : SwingConstants.LEFT);
            if (isNull && !selected) {
                l.setForeground(Theme.MUTED);
                l.setFont(t.getFont().deriveFont(Font.ITALIC));
            } else {
                l.setForeground(selected ? t.getSelectionForeground() : t.getForeground());
                l.setFont(t.getFont());
            }
            return l;
        }
    }

    // ---- capabilities

    private int findRow = -1;
    private int findColumn = -1;

    @Override
    public int find(final String text, final boolean forward) {
        final TableSource s = model.source;
        if (s == null || text.isEmpty()) {
            return 0;
        }
        final String needle = CaseFold.fold(text);
        final int cols = s.columnCount();
        final int rows = findRows(model.rows, cols);
        final long cells = (long) rows * cols;
        int matches = 0;
        int firstRow = -1;
        int firstCol = -1;
        int lastRow = -1;
        int lastCol = -1;
        int nextRow = -1;
        int nextCol = -1;
        final long start = (long) Math.max(findRow, 0) * cols + Math.max(findColumn, -1);
        final StringBuilder sb = new StringBuilder();
        for (long i = 0; i < cells && matches < 100_000; i++) {
            final int r = (int) (i / cols);
            final int c = (int) (i % cols);
            sb.setLength(0);
            s.format(model.sourceRow(r), c, sb);
            if (CaseFold.contains(sb, needle)) {
                matches++;
                if (firstRow < 0) {
                    firstRow = r;
                    firstCol = c;
                }
                lastRow = r;
                lastCol = c;
                final long at = (long) r * cols + c;
                if (forward && at > start && nextRow < 0) {
                    nextRow = r;
                    nextCol = c;
                }
                if (!forward && at < start) {
                    nextRow = r;
                    nextCol = c;
                }
            }
        }
        if (nextRow < 0) {
            // past the last match forwards, or before the first backwards: wrap around
            nextRow = forward ? firstRow : lastRow;
            nextCol = forward ? firstCol : lastCol;
        }
        if (nextRow >= 0) {
            findRow = nextRow;
            findColumn = nextCol;
            final int viewCol = table.convertColumnIndexToView(nextCol + 1);
            table.changeSelection(nextRow, viewCol, false, false);
            table.scrollRectToVisible(table.getCellRect(nextRow, viewCol, true));
        }
        return matches;
    }

    /** The rows a find reads: the first of {@code rows} whose cells, {@code cols} each, fit {@link #MAX_EDT_CELLS}. */
    static int findRows(final int rows, final int cols) {
        return (int) Math.min(Math.min(rows, MAX_EDT_ROWS), Math.max(1L, MAX_EDT_CELLS / Math.max(1, cols)));
    }

    @Override
    public void clearFind() {
        findRow = -1;
        findColumn = -1;
    }

    @Override
    public String copyText() {
        final TableSource s = model.source;
        if (s == null) {
            return "";
        }
        final int[] rows = table.getSelectedRows();
        final StringBuilder out = new StringBuilder();
        for (int c = 0; c < s.columnCount(); c++) {
            out.append(c == 0 ? "" : "\t").append(s.columnName(c));
        }
        out.append('\n');
        final int n = Math.min(rows.length > 0 ? rows.length : model.rows, MAX_COPY_ROWS);
        for (int i = 0; i < n; i++) {
            final int r = model.sourceRow(rows.length > 0 ? rows[i] : i);
            for (int c = 0; c < s.columnCount(); c++) {
                if (c > 0) {
                    out.append('\t');
                }
                // SQL NULL is an empty cell, so it differs from the text 'null'
                if (!s.isNull(r, c)) {
                    s.format(r, c, out);
                }
            }
            out.append('\n');
        }
        return out.toString();
    }

    /** False on an empty grid: a copy then holds no row, at most the header. */
    @Override
    public boolean canCopy() {
        return model.source != null && model.rows > 0;
    }

    @Override
    public String exportName() {
        return exportName;
    }

    @Override
    public Export prepare() {
        // the source's rows below the count are immutable; the sort order and the count are copied here
        final TableSource s = model.source;
        final int rows = model.rows;
        final int[] order = model.order == null ? null : model.order.clone();
        return file -> {
            if (s != null) {
                writeCsv(file, s, rows, order);
            }
        };
    }

    private static void writeCsv(final Path file, final TableSource s, final int rows, final int[] order) throws IOException {
        try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            final StringBuilder sb = new StringBuilder();
            for (int c = 0; c < s.columnCount(); c++) {
                sb.setLength(0);
                sb.append(s.columnName(c));
                w.write(c == 0 ? "" : ",");
                w.write(csv(sb));
            }
            w.write('\n');
            for (int i = 0; i < rows; i++) {
                final int r = order == null ? i : order[i];
                for (int c = 0; c < s.columnCount(); c++) {
                    if (c > 0) {
                        w.write(',');
                    }
                    // SQL NULL is an empty field and empty text is "", as PostgreSQL's CSV tells them apart
                    if (!s.isNull(r, c)) {
                        sb.setLength(0);
                        s.format(r, c, sb);
                        w.write(sb.isEmpty() ? "\"\"" : csv(sb));
                    }
                }
                w.write('\n');
            }
        }
    }

    static String csv(final CharSequence v) {
        final String s = v.toString();
        if (s.indexOf(',') < 0 && s.indexOf('"') < 0 && s.indexOf('\n') < 0 && s.indexOf('\r') < 0) {
            return s;
        }
        return '"' + s.replace("\"", "\"\"") + '"';
    }

    /**
     * The selected rows as source row indices, in the order shown. The grid sorts with its own permutation,
     * not a {@code RowSorter}, so {@code JTable.convertRowIndexToModel} does not map them.
     */
    public int[] selectedSourceRows() {
        final int[] rows = table.getSelectedRows();
        final int[] out = new int[rows.length];
        for (int i = 0; i < rows.length; i++) {
            out[i] = model.sourceRow(rows[i]);
        }
        return out;
    }

    public JTable table() {
        return table;
    }
}
