package dev.marregui.comando.ui.kit.tabs;

import dev.marregui.comando.ui.kit.icon.Icons;
import dev.marregui.comando.ui.kit.theme.SidebarLook;
import dev.marregui.comando.ui.kit.theme.Theme;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Tabs in one row that never moves: a header per tab, drawn as a tab with its icon alone (the selected one underlined),
 * in the order added, then an optional component at the row's end. A header's tooltip names its tab and says what it
 * holds. A right click on a header selects its tab and asks for its menu.
 */
public final class TabStrip {

    private final List<JToggleButton> headers = new ArrayList<>();
    private final ButtonGroup group = new ButtonGroup();
    private final CardLayout cards = new CardLayout();
    private final JPanel content = new JPanel(cards);
    private final JPanel row = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0));
    private final JPanel strip = new JPanel(new BorderLayout());
    private final JPanel root = new JPanel(new BorderLayout());
    private BiConsumer<Integer, MouseEvent> menu = (i, e) -> {
    };
    private int selected = -1;

    public TabStrip(String accessibleName) {
        root.getAccessibleContext().setAccessibleName(accessibleName);
        row.setOpaque(false);
        SidebarLook.ground(strip);
        SidebarLook.ground(content);
        SidebarLook.ground(root);
        strip.add(row, BorderLayout.CENTER);
        strip.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.CARD_BORDER),
                BorderFactory.createEmptyBorder(2, 2, 0, 0)));
        root.add(strip, BorderLayout.NORTH);
        root.add(content, BorderLayout.CENTER);
    }

    /** Adds a tab with the icon of that name, after the others; {@code title} is its name for tooltips and readers. */
    public void add(String title, String icon, String tip, JComponent body) {
        int index = headers.size();
        JToggleButton b = new JToggleButton(Icons.get(icon));
        b.putClientProperty("JButton.buttonType", "tab");
        // the tab in front underlined in the tint, as every "where I am" mark is (record 100)
        SidebarLook.whileShown(b, () -> b.putClientProperty("FlatLaf.style", "tab.underlineHeight: 2; tab.underlineColor: "
                + Theme.hex(Theme.tint().body) + "; margin: 4,7,4,7"));
        b.setToolTipText(title + ": " + tip);
        b.getAccessibleContext().setAccessibleName(title);
        b.setFocusable(false);
        b.addActionListener(e -> select(index));
        b.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                popup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                popup(e);
            }

            private void popup(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    select(index);
                    menu.accept(index, e);
                }
            }
        });
        group.add(b);
        headers.add(b);
        row.add(b);
        content.add(body, Integer.toString(index));
        if (selected < 0) {
            select(index);
        }
    }

    /** The component at the row's end, such as the button that hides the sidebar. */
    public void trailing(JComponent trailing) {
        JPanel corner = new JPanel(new BorderLayout());
        corner.setOpaque(false);
        corner.add(trailing, BorderLayout.NORTH);
        strip.add(corner, BorderLayout.EAST);
        strip.revalidate();
    }

    public JComponent component() {
        return root;
    }

    public int selectedIndex() {
        return selected;
    }

    public void select(int index) {
        if (index < 0 || index >= headers.size()) {
            return;
        }
        selected = index;
        headers.get(index).setSelected(true);
        cards.show(content, Integer.toString(index));
    }

    /** Asked, with the tab's index and the mouse event, when a header is right-clicked; the tab is selected first. */
    public void onMenu(BiConsumer<Integer, MouseEvent> menu) {
        this.menu = menu;
    }

    /** The header of tab {@code index}; for tests and accessibility. */
    public JToggleButton header(int index) {
        return headers.get(index);
    }
}
