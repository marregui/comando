package dev.marregui.comando.ui.panels.lab;

import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.JTable;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableCellRenderer;

import dev.marregui.comando.ui.kit.text.Plain;
import dev.marregui.comando.ui.kit.theme.Theme;

/**
 * A read-only table of the frontear tab: named columns, each header with a tooltip saying what the column means, rows
 * replaced whole, every cell shown as plain text but a {@link Bar}, drawn as a log's progress, and a {@link Flag},
 * coloured when it is a fault. EDT only.
 */
public final class LabTable {

    private final Model model;
    private final JTable table;

    public LabTable(final String name, final Column... columns) {
        this.model = new Model(List.of(columns));
        this.table = new JTable(model);
        table.setTableHeader(new Header(table, model.columns));
        table.setName(name);
        table.setFillsViewportHeight(true);
        table.setAutoCreateRowSorter(false);
        table.getTableHeader().setReorderingAllowed(false);
        table.setDefaultRenderer(Object.class, new TextCell());
        table.setDefaultRenderer(Bar.class, new BarCell());
        table.setDefaultRenderer(Flag.class, new FlagCell());
    }

    /** A column: the header's {@code title}, and the one line its tooltip gives as its meaning. */
    public record Column(String title, String tip) {
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

    /** A cell's text, drawn in the theme's danger colour when {@code fault}: a link's status. */
    public record Flag(String text, boolean fault) {

        @Override
        public String toString() {
            return text;
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

        private final List<Column> columns;
        private List<Object[]> rows = new ArrayList<>();

        Model(final List<Column> columns) {
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
            return columns.get(column).title();
        }

        @Override
        public Class<?> getColumnClass(final int column) {
            if (rows.isEmpty()) {
                return Object.class;
            }
            return switch (rows.getFirst()[column]) {
                case Bar _ -> Bar.class;
                case Flag _ -> Flag.class;
                case null, default -> Object.class;
            };
        }

        @Override
        public Object getValueAt(final int row, final int column) {
            return rows.get(row)[column];
        }
    }

    /** The header, whose tooltip is the meaning of the column under the pointer. */
    private static final class Header extends JTableHeader {

        private final List<Column> columns;

        Header(final JTable table, final List<Column> columns) {
            super(table.getColumnModel());
            this.columns = columns;
        }

        @Override
        public String getToolTipText(final MouseEvent e) {
            final int at = columnAtPoint(e.getPoint());
            return at < 0 ? null : columns.get(getColumnModel().getColumn(at).getModelIndex()).tip();
        }
    }

    /** A cell's value as text, never as HTML: topics, origins and reasons come from the peers (rule 6). */
    private static class TextCell extends DefaultTableCellRenderer {

        TextCell() {
            Plain.only(this);
        }

        @Override
        protected void setValue(final Object value) {
            setText(value == null ? "" : Plain.text(value.toString()));
        }
    }

    /** A {@link Flag}: its text, in the danger colour when it is a fault and its row is not selected. */
    private static final class FlagCell extends TextCell {

        @Override
        public Component getTableCellRendererComponent(
                final JTable table,
                final Object value,
                final boolean selected,
                final boolean focused,
                final int row,
                final int column
        ) {
            super.getTableCellRendererComponent(table, value, selected, focused, row, column);
            final boolean fault = value instanceof final Flag f && f.fault();
            setForeground(selected ? table.getSelectionForeground() : fault ? Theme.DANGER : table.getForeground());
            return this;
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
