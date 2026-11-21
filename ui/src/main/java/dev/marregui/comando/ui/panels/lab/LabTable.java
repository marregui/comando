package dev.marregui.comando.ui.panels.lab;

import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.JTable;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellRenderer;

import dev.marregui.comando.ui.kit.text.Plain;
import dev.marregui.comando.ui.kit.theme.Theme;

/**
 * A read-only table of the frontear tab: named columns, rows replaced whole, every cell shown as plain text but a
 * {@link Bar}, drawn as a log's progress. EDT only.
 */
public final class LabTable {

    private final Model model;
    private final JTable table;

    public LabTable(final String name, final String... columns) {
        this.model = new Model(List.of(columns));
        this.table = new JTable(model);
        table.setName(name);
        table.setFillsViewportHeight(true);
        table.setAutoCreateRowSorter(false);
        table.getTableHeader().setReorderingAllowed(false);
        table.setDefaultRenderer(Object.class, new TextCell());
        table.setDefaultRenderer(Bar.class, new BarCell());
    }

    /** How far a peer has read its link's log: {@code cursor} of {@code tail}. */
    public record Bar(long cursor, long tail) {

        /** The part read, from 0 to 1; a log with nothing written is read whole. */
        public double read() {
            return tail <= 0L ? 1.0 : Math.clamp((double) cursor / tail, 0.0, 1.0);
        }

        @Override
        public String toString() {
            return cursor + " / " + tail;
        }
    }

    public JTable table() {
        return table;
    }

    /** Shows {@code rows}, each one value per column; the selection is the caller's to keep. */
    public void rows(final List<Object[]> rows) {
        model.rows = List.copyOf(rows);
        model.fireTableDataChanged();
    }

    public int rowCount() {
        return model.getRowCount();
    }

    /** The text the cell at {@code row}, {@code column} shows. */
    public String text(final int row, final int column) {
        return String.valueOf(model.getValueAt(row, column));
    }

    private static final class Model extends AbstractTableModel {

        private final List<String> columns;
        private List<Object[]> rows = new ArrayList<>();

        Model(final List<String> columns) {
            this.columns = columns;
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return columns.size();
        }

        @Override
        public String getColumnName(final int column) {
            return columns.get(column);
        }

        @Override
        public Class<?> getColumnClass(final int column) {
            return !rows.isEmpty() && rows.getFirst()[column] instanceof Bar ? Bar.class : Object.class;
        }

        @Override
        public Object getValueAt(final int row, final int column) {
            return rows.get(row)[column];
        }
    }

    /** A cell's value as text, never as HTML: topics, origins and reasons come from the peers (rule 6). */
    private static final class TextCell extends DefaultTableCellRenderer {

        TextCell() {
            Plain.only(this);
        }

        @Override
        protected void setValue(final Object value) {
            setText(value == null ? "" : Plain.text(value.toString()));
        }
    }

    /** A {@link Bar}: the part read filled, the rest the table's rule colour, the figures over it. */
    private static final class BarCell extends JComponent implements TableCellRenderer {

        private Bar bar = new Bar(0L, 0L);

        @Override
        public Component getTableCellRendererComponent(
                final JTable table,
                final Object value,
                final boolean selected,
                final boolean focused,
                final int row,
                final int column
        ) {
            bar = value instanceof final Bar b ? b : new Bar(0L, 0L);
            setForeground(selected ? table.getSelectionForeground() : table.getForeground());
            setFont(table.getFont());
            return this;
        }

        @Override
        protected void paintComponent(final Graphics g) {
            final Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                final int w = getWidth() - 2;
                final int h = getHeight() - 4;
                g2.setColor(Theme.TABLE_RULE);
                g2.fillRect(1, 2, w, h);
                g2.setColor(Theme.OK);
                g2.fillRect(1, 2, (int) Math.round(w * bar.read()), h);
                g2.setColor(getForeground());
                g2.drawString(bar.toString(), Theme.SPACE_XS, g2.getFontMetrics().getAscent() + 2);
            } finally {
                g2.dispose();
            }
        }
    }
}
