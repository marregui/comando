package dev.marregui.comando.ui.panels;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;

import dev.marregui.comando.ui.kit.text.MarkdownHtml;
import dev.marregui.comando.ui.kit.text.Plain;
import dev.marregui.comando.ui.kit.theme.FocusRing;
import dev.marregui.comando.ui.kit.theme.SidebarLook;
import dev.marregui.comando.ui.kit.theme.Theme;

/**
 * A Markdown document's outline, at the right of its panel (record 113): a line per heading, indented by its level, the
 * first level in bold; a double click goes to the heading. Says so when the text has none. In the sidebar's look
 * (record 98): the theme's ground, ivory names, the selection a band of the tint with the report's bar, set again when
 * another theme is chosen. EDT only.
 */
final class OutlinePane {

    /** How far each level below the first is indented. */
    static final int INDENT = 12;
    static final String NONE = "No headings";

    private final DefaultListModel<MarkdownHtml.Section> model = new DefaultListModel<>();
    private final JList<MarkdownHtml.Section> list = new JList<>(model) {
        /** The report's bar down the left edge of the selected row, as the sidebar's. */
        @Override
        protected void paintComponent(final Graphics g) {
            super.paintComponent(g);
            for (final int i : getSelectedIndices()) {
                final Rectangle r = getCellBounds(i, i);
                if (r != null) {
                    SidebarLook.bar(g, r);
                }
            }
        }
    };
    private final JLabel empty = new JLabel(NONE);
    private final JPanel root = new JPanel(new BorderLayout());
    private Consumer<MarkdownHtml.Section> onOpen = s -> { };

    OutlinePane() {
        root.setName("outline");
        list.setName("outline-list");
        list.getAccessibleContext().setAccessibleName("Outline");
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(final JList<?> l, final Object value, final int index, final boolean selected, final boolean focus) {
                final MarkdownHtml.Section s = (MarkdownHtml.Section) value;
                final JLabel c = (JLabel) super.getListCellRendererComponent(l, Plain.text(s.text()), index, selected, focus);
                Plain.only(c);
                c.setBorder(BorderFactory.createEmptyBorder(2, Theme.GAP + (s.level() - 1) * INDENT, 2, Theme.GAP));
                c.setFont(l.getFont().deriveFont(s.level() == 1 ? Font.BOLD : Font.PLAIN));
                c.setToolTipText(s.text());
                return c;
            }
        });
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(final MouseEvent e) {
                final int i = list.locationToIndex(e.getPoint());
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e) && i >= 0 && list.getCellBounds(i, i).contains(e.getPoint())) {
                    onOpen.accept(model.get(i));
                }
            }
        });
        empty.setForeground(Theme.MUTED);
        empty.setBorder(BorderFactory.createEmptyBorder(Theme.GAP, Theme.GAP, Theme.GAP, Theme.GAP));
        empty.setVerticalAlignment(JLabel.TOP);
        final JScrollPane scroll = new JScrollPane(list);
        FocusRing.install(scroll);
        SidebarLook.list(list);
        SidebarLook.ground(scroll);
        SidebarLook.ground(root);
        SidebarLook.ground(empty);
        root.add(scroll, BorderLayout.CENTER);
        root.add(empty, BorderLayout.NORTH);
        show(List.of());
    }

    JComponent component() {
        return root;
    }

    /** {@code open} goes to a heading a double click picked. */
    void onOpen(final Consumer<MarkdownHtml.Section> open) {
        onOpen = open;
    }

    /** Lists {@code sections}; the one selected stays selected when it is still there. */
    void show(final List<MarkdownHtml.Section> sections) {
        if (Collections.list(model.elements()).equals(sections)) {
            return;
        }
        final MarkdownHtml.Section was = list.getSelectedValue();
        model.clear();
        model.addAll(sections);
        if (was != null) {
            final int i = sections.indexOf(was);
            if (i >= 0) {
                list.setSelectedIndex(i);
            }
        }
        empty.setVisible(sections.isEmpty());
    }

    /** The headings listed; for tests. */
    List<MarkdownHtml.Section> sections() {
        return Collections.list(model.elements());
    }

    /** The list; for tests. */
    JList<MarkdownHtml.Section> list() {
        return list;
    }
}
