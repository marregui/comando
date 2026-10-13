package dev.marregui.comando.ui.kit.text;

import javax.swing.JComponent;
import java.util.Locale;

/**
 * Text shown as text. Swing renders a label, button, tooltip, tab title or cell whose text starts with
 * {@code <html>} as HTML, and loads the images it names while laying it out. Text from Claude, a database
 * or a stored conversation must never get that treatment: an {@code <img>} URL in a row value or a
 * question option would be a network request nobody approved.
 */
public final class Plain {

    /** Zero-width space: invisible, and enough to stop Swing from seeing the {@code <html>} prefix. */
    private static final char GUARD = '\u200B';

    private Plain() {
    }

    /** {@code text}, with an invisible guard in front when Swing would render it as HTML. */
    public static String text(String text) {
        if (text == null || text.length() < 6) {
            return text;
        }
        String head = text.substring(0, 6).toLowerCase(Locale.ROOT);
        return head.equals("<html>") ? GUARD + text : text;
    }

    /**
     * Turns HTML rendering off for {@code c}, for renderers and labels that only ever show text. Swing reads
     * the property when the text is set, so create the component without text, call this, then set the text;
     * text already set is set again so it is rebuilt without HTML.
     */
    public static <T extends JComponent> T only(T c) {
        c.putClientProperty("html.disable", Boolean.TRUE);
        if (c instanceof javax.swing.JLabel l && l.getText() != null && !l.getText().isEmpty()) {
            String t = l.getText();
            l.setText("");
            l.setText(t);
        } else if (c instanceof javax.swing.AbstractButton b && b.getText() != null && !b.getText().isEmpty()) {
            String t = b.getText();
            b.setText("");
            b.setText(t);
        }
        return c;
    }
}
