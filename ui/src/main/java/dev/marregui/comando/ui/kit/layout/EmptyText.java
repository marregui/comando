package dev.marregui.comando.ui.kit.layout;

import java.awt.BorderLayout;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.event.ListDataEvent;
import javax.swing.event.ListDataListener;

import dev.marregui.comando.ui.kit.text.MarkdownHtml;
import dev.marregui.comando.ui.kit.theme.Theme;

/**
 * What a list says while it is empty, as the sidebar's lists say what to do and the Machines window's says none is
 * paired: muted text at the top of the list, wrapped at {@link #WIDTH_PX}, shown while the model holds no row and
 * hidden from the first one. EDT only.
 */
public final class EmptyText {

    /** The text wraps at the width the sidebar, the narrowest list that says one, shows it in. */
    static final int WIDTH_PX = 180;

    private EmptyText() {
    }

    /** {@code list} saying {@code text} while it is empty; {@code list} itself, to use in a field's initialiser. */
    public static <T> JList<T> over(final JList<T> list, final String text) {
        final JLabel hint = new JLabel("<html><div style='width:" + WIDTH_PX + "px'>" + MarkdownHtml.escape(text)
                + "</div></html>");
        hint.setForeground(Theme.MUTED_STRONG);
        hint.setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_S, Theme.SPACE_M, Theme.SPACE_S, Theme.SPACE_M));
        final JPanel overlay = new JPanel(new BorderLayout());
        overlay.setOpaque(false);
        overlay.add(hint, BorderLayout.NORTH);
        list.setLayout(new BorderLayout());
        list.add(overlay, BorderLayout.CENTER);
        list.getModel().addListDataListener(new ListDataListener() {
            @Override
            public void intervalAdded(final ListDataEvent e) {
                overlay.setVisible(list.getModel().getSize() == 0);
            }

            @Override
            public void intervalRemoved(final ListDataEvent e) {
                overlay.setVisible(list.getModel().getSize() == 0);
            }

            @Override
            public void contentsChanged(final ListDataEvent e) {
                overlay.setVisible(list.getModel().getSize() == 0);
            }
        });
        return list;
    }
}
