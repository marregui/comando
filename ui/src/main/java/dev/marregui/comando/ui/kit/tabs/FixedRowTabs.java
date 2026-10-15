package dev.marregui.comando.ui.kit.tabs;

import dev.marregui.comando.ui.kit.icon.Icons;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Cursor;
import java.awt.GridLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * Tabs whose headers sit in fixed rows that never move: a click selects a tab and nothing changes place, unlike a
 * wrapped {@code JTabbedPane}, which brings the selected tab's row next to the content. Each tab keeps the index it
 * was added with; {@link #arrange} places the headers by index, row by row, with an optional component after the
 * first row. A header is a bordered toggle button with an icon, its icon and text at the leading edge, so the bounds of
 * each read as a button and the titles of one column start at the same x.
 */
public final class FixedRowTabs {

    private static final int GAP = 3;

    private final List<JToggleButton> headers = new ArrayList<>();
    private final ButtonGroup group = new ButtonGroup();
    private final CardLayout cards = new CardLayout();
    private final JPanel content = new JPanel(cards);
    private final JPanel strip = new JPanel(new BorderLayout());
    private final JPanel root = new JPanel(new BorderLayout());
    private IntConsumer listener = i -> {
    };
    private int selected = -1;

    public FixedRowTabs(String accessibleName) {
        root.getAccessibleContext().setAccessibleName(accessibleName);
        root.add(strip, BorderLayout.NORTH);
        root.add(content, BorderLayout.CENTER);
    }

    /** Adds a tab with the icon of that name; its index is the number of tabs added before it. */
    public void add(String title, String icon, String tip, JComponent body) {
        int index = headers.size();
        JToggleButton b = new JToggleButton(title, Icons.get(icon));
        b.setHorizontalAlignment(SwingConstants.LEADING);
        b.setIconTextGap(5);
        b.setMargin(new Insets(3, 5, 3, 5));
        // the selected header takes the list-selection blue, so which tab is open reads at a glance
        b.putClientProperty("FlatLaf.style", "selectedBackground: #2E436E; selectedForeground: #FFFFFF");
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.setToolTipText(tip);
        b.setFocusable(false);
        b.addActionListener(e -> select(index));
        group.add(b);
        headers.add(b);
        content.add(body, Integer.toString(index));
        if (selected < 0) {
            select(index);
        }
    }

    /** Places the headers: one row per array, by tab index, the rows equally divided; {@code trailing} ends the first row. */
    public void arrange(int[][] rows, JComponent trailing) {
        strip.removeAll();
        JPanel grid = new JPanel(new GridLayout(rows.length, 1, 0, GAP));
        grid.setBorder(BorderFactory.createEmptyBorder(GAP, GAP, GAP, 0));
        for (int[] row : rows) {
            JPanel line = new JPanel(new GridLayout(1, row.length, GAP, 0));
            for (int i : row) {
                line.add(headers.get(i));
            }
            grid.add(line);
        }
        strip.add(grid, BorderLayout.CENTER);
        if (trailing != null) {
            JPanel corner = new JPanel(new BorderLayout());
            corner.add(trailing, BorderLayout.NORTH);
            corner.setBorder(BorderFactory.createEmptyBorder(GAP, 2, 0, 0));
            strip.add(corner, BorderLayout.EAST);
        }
        strip.revalidate();
    }

    public JComponent component() {
        return root;
    }

    public int selectedIndex() {
        return selected;
    }

    public int tabCount() {
        return headers.size();
    }

    public void select(int index) {
        if (index < 0 || index >= headers.size()) {
            return;
        }
        boolean changed = index != selected;
        selected = index;
        headers.get(index).setSelected(true);
        cards.show(content, Integer.toString(index));
        if (changed) {
            listener.accept(index);
        }
    }

    /** Hears the index of each newly selected tab. */
    public void onSelect(IntConsumer listener) {
        this.listener = listener;
    }

    /** The header of tab {@code index}; for tests and accessibility. */
    public JToggleButton header(int index) {
        return headers.get(index);
    }
}
