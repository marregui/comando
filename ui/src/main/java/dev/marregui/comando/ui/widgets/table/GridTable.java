package dev.marregui.comando.ui.widgets.table;

import dev.marregui.comando.ui.kit.text.Plain;
import dev.marregui.comando.ui.kit.theme.Theme;
import dev.marregui.comando.ui.widget.capability.Capabilities;
import dev.marregui.comando.ui.widget.capability.Copyable;
import dev.marregui.comando.ui.widget.capability.Exportable;
import dev.marregui.comando.ui.widget.capability.Searchable;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumn;
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
import java.util.Locale;

/**
 * A grid over a {@link TableSource}. JTable paints only the visible rows; cells format into one reused
 * buffer. Sorting keeps a permutation of row indices instead of a sorter over boxed values. Sort and find run on the
 * event thread, so both are bounded to {@link #MAX_EDT_ROWS} rows, a few tens of milliseconds: a larger result is not
 * sorted, and find looks at its first rows. A growing source adds rows without rebuilding the columns.
 */
public final class GridTable implements Searchable, Copyable, Exportable {

    static final int MAX_EDT_ROWS = 200_000;
    private static final int MAX_COPY_ROWS = 200_000;

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
            public void mouseClicked(MouseEvent e) {
                int view = table.columnAtPoint(e.getPoint());
                if (view >= 0 && e.getClickCount() == 1) {
                    model.toggleSort(table.convertColumnIndexToModel(view));
                }
            }
        });
        scroll.setBorder(null);
        Capabilities.mark(scroll, this);
    }

    public JComponent component() {
        return scroll;
    }

    /** Shows {@code source}, resetting columns and sort. */
    public void setSource(TableSource source, String exportFileName) {
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
        TableSource s = model.source;
        if (s == null) {
            return;
        }
        int before = model.rows;
        int now = s.rowCount();
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
        TableSource s = model.source;
        if (s == null) {
            return;
        }
        FontMetrics fm = table.getFontMetrics(table.getFont());
        int sample = Math.min(s.rowCount(), 200);
        for (int c = 0; c < table.getColumnCount(); c++) {
            int m = table.convertColumnIndexToModel(c);
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
            TableColumn col = table.getColumnModel().getColumn(c);
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

        int sourceRow(int row) {
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
        public String getColumnName(int column) {
            if (column == 0) {
                return "#";
            }
            String arrow = column - 1 == sortColumn ? (ascending ? " ▲" : " ▼") : "";
            return source.columnName(column - 1) + arrow;
        }

        @Override
        public Object getValueAt(int row, int column) {
            if (column == 0) {
                return sourceRow(row) + 1;
            }
            cell.setLength(0);
            source.format(sourceRow(row), column - 1, cell);
            return cell.toString();
        }

        void toggleSort(int modelColumn) {
            if (modelColumn == 0 || source == null || rows > MAX_EDT_ROWS) {
                return;
            }
            int c = modelColumn - 1;
            ascending = sortColumn != c || !ascending;
            sortColumn = c;
            Integer[] idx = new Integer[rows];
            for (int i = 0; i < rows; i++) {
                idx[i] = i;
            }
            if (source.isNumeric(c)) {
                double[] keys = new double[rows];
                for (int i = 0; i < rows; i++) {
                    keys[i] = source.numeric(i, c);
                }
                Arrays.sort(idx, (a, b) -> Double.compare(keys[a], keys[b]));
            } else {
                String[] keys = new String[rows];
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < rows; i++) {
                    sb.setLength(0);
                    source.format(i, c, sb);
                    keys[i] = sb.toString();
                }
                Arrays.sort(idx, (a, b) -> keys[a].compareTo(keys[b]));
            }
            int[] o = new int[rows];
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
        public Component getTableCellRendererComponent(JTable t, Object value, boolean selected, boolean focus, int row, int column) {
            JLabel l = (JLabel) super.getTableCellRendererComponent(t, value, selected, focus, row, column);
            int m = t.convertColumnIndexToModel(column);
            TableSource s = model.source;
            if (m == 0) {
                l.setHorizontalAlignment(SwingConstants.RIGHT);
                l.setForeground(Theme.MUTED);
                l.setFont(t.getFont());
                return l;
            }
            int r = model.sourceRow(row);
            boolean isNull = s != null && r < s.rowCount() && s.isNull(r, m - 1);
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
    public int find(String text, boolean forward) {
        TableSource s = model.source;
        if (s == null || text.isEmpty()) {
            return 0;
        }
        String needle = text.toLowerCase(Locale.ROOT);
        int rows = Math.min(model.rows, MAX_EDT_ROWS);
        int cols = s.columnCount();
        long cells = (long) rows * cols;
        int matches = 0;
        int firstRow = -1;
        int firstCol = -1;
        int lastRow = -1;
        int lastCol = -1;
        int nextRow = -1;
        int nextCol = -1;
        long start = (long) Math.max(findRow, 0) * cols + Math.max(findColumn, -1);
        StringBuilder sb = new StringBuilder();
        for (long i = 0; i < cells && matches < 100_000; i++) {
            int r = (int) (i / cols);
            int c = (int) (i % cols);
            sb.setLength(0);
            s.format(model.sourceRow(r), c, sb);
            if (sb.toString().toLowerCase(Locale.ROOT).contains(needle)) {
                matches++;
                if (firstRow < 0) {
                    firstRow = r;
                    firstCol = c;
                }
                lastRow = r;
                lastCol = c;
                long at = (long) r * cols + c;
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
            int viewCol = table.convertColumnIndexToView(nextCol + 1);
            table.changeSelection(nextRow, viewCol, false, false);
            table.scrollRectToVisible(table.getCellRect(nextRow, viewCol, true));
        }
        return matches;
    }

    @Override
    public void clearFind() {
        findRow = -1;
        findColumn = -1;
    }

    @Override
    public String copyText() {
        TableSource s = model.source;
        if (s == null) {
            return "";
        }
        int[] rows = table.getSelectedRows();
        StringBuilder out = new StringBuilder();
        for (int c = 0; c < s.columnCount(); c++) {
            out.append(c == 0 ? "" : "\t").append(s.columnName(c));
        }
        out.append('\n');
        int n = rows.length > 0 ? rows.length : Math.min(model.rows, MAX_COPY_ROWS);
        for (int i = 0; i < n; i++) {
            int r = model.sourceRow(rows.length > 0 ? rows[i] : i);
            for (int c = 0; c < s.columnCount(); c++) {
                if (c > 0) {
                    out.append('\t');
                }
                s.format(r, c, out);
            }
            out.append('\n');
        }
        return out.toString();
    }

    @Override
    public String exportName() {
        return exportName;
    }

    @Override
    public Export prepare() {
        // the source's rows below the count are immutable; the sort order and the count are copied here
        TableSource s = model.source;
        int rows = model.rows;
        int[] order = model.order == null ? null : model.order.clone();
        return file -> {
            if (s != null) {
                writeCsv(file, s, rows, order);
            }
        };
    }

    private static void writeCsv(Path file, TableSource s, int rows, int[] order) throws IOException {
        try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            StringBuilder sb = new StringBuilder();
            for (int c = 0; c < s.columnCount(); c++) {
                sb.setLength(0);
                sb.append(s.columnName(c));
                w.write(c == 0 ? "" : ",");
                w.write(csv(sb));
            }
            w.write('\n');
            for (int i = 0; i < rows; i++) {
                int r = order == null ? i : order[i];
                for (int c = 0; c < s.columnCount(); c++) {
                    if (c > 0) {
                        w.write(',');
                    }
                    if (!s.isNull(r, c)) {
                        sb.setLength(0);
                        s.format(r, c, sb);
                        w.write(csv(sb));
                    }
                }
                w.write('\n');
            }
        }
    }

    static String csv(CharSequence v) {
        String s = v.toString();
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
        int[] rows = table.getSelectedRows();
        int[] out = new int[rows.length];
        for (int i = 0; i < rows.length; i++) {
            out[i] = model.sourceRow(rows[i]);
        }
        return out;
    }

    public JTable table() {
        return table;
    }
}
