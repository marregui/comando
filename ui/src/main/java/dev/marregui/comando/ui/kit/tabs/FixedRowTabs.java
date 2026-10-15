package dev.marregui.comando.ui.kit.tabs;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * Tabs whose headers sit in fixed rows that never move: a click selects a tab and nothing changes place, unlike a
 * wrapped {@code JTabbedPane}, which brings the selected tab's row next to the content. Each tab keeps the index it
 * was added with; {@link #arrange} places the headers by index, row by row, with an optional component after the
 * first row.
 */
public final class FixedRowTabs {

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

    /** Adds a tab; its index is the number of tabs added before it. */
    public void add(String title, String tip, JComponent body) {
        int index = headers.size();
        JToggleButton b = new JToggleButton(title);
        b.putClientProperty("JButton.buttonType", "tab");
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
        JPanel grid = new JPanel(new GridLayout(rows.length, 1, 0, 0));
        for (int[] row : rows) {
            JPanel line = new JPanel(new GridLayout(1, row.length, 0, 0));
            for (int i : row) {
                line.add(headers.get(i));
            }
            grid.add(line);
        }
        strip.add(grid, BorderLayout.CENTER);
        if (trailing != null) {
            JPanel corner = new JPanel(new BorderLayout());
            corner.add(trailing, BorderLayout.NORTH);
            corner.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 0));
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
