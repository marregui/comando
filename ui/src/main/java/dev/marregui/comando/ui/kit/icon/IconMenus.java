package dev.marregui.comando.ui.kit.icon;

import javax.swing.JMenu;
import javax.swing.JMenuItem;

/** Menu entries with their icon: an item that runs an action, and a submenu. */
public final class IconMenus {

    private IconMenus() {
    }

    /** An item saying {@code text} with the icon named {@code icon}, running {@code action} when chosen. */
    public static JMenuItem item(final String text, final String icon, final Runnable action) {
        final JMenuItem i = new JMenuItem(text, Icons.get(icon));
        i.addActionListener(_ -> action.run());
        return i;
    }

    /** A submenu saying {@code text} with the icon named {@code icon}. */
    public static JMenu submenu(final String text, final String icon) {
        final JMenu m = new JMenu(text);
        m.setIcon(Icons.get(icon));
        return m;
    }
}
