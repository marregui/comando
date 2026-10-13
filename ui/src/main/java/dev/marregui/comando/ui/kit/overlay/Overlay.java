package dev.marregui.comando.ui.kit.overlay;

import dev.marregui.comando.ui.kit.icon.Icons;
import dev.marregui.comando.ui.kit.theme.Theme;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.GridBagLayout;

/**
 * The uniform empty, loading and error states. A widget wraps its content once and switches states; no
 * widget draws its own spinner or placeholder.
 */
public final class Overlay extends JPanel {

    private static final String CONTENT = "content";
    private static final String MESSAGE = "message";

    private final CardLayout cards = new CardLayout();
    private final JLabel message = new JLabel("", SwingConstants.CENTER);
    private final JProgressBar progress = new JProgressBar();
    private String showing = CONTENT;

    public Overlay(JComponent content) {
        setLayout(cards);
        progress.setIndeterminate(true);
        progress.setVisible(false);
        JPanel inner = new JPanel(new BorderLayout(0, Theme.GAP));
        inner.setOpaque(false);
        inner.add(message, BorderLayout.CENTER);
        inner.add(progress, BorderLayout.SOUTH);
        JPanel centered = new JPanel(new GridBagLayout());
        centered.setBorder(BorderFactory.createEmptyBorder(Theme.PAD, Theme.PAD, Theme.PAD, Theme.PAD));
        centered.add(inner);
        add(content, CONTENT);
        add(centered, MESSAGE);
        cards.show(this, CONTENT);
    }

    public void showContent() {
        if (!showing.equals(CONTENT)) {
            showing = CONTENT;
            cards.show(this, CONTENT);
        }
    }

    public void showEmpty(String text) {
        show(text, null, false, Theme.MUTED);
    }

    public void showLoading(String text) {
        show(text, null, true, Theme.MUTED);
    }

    public void showError(String text) {
        show(text, "error", false, Theme.DANGER);
    }

    private void show(String text, String icon, boolean loading, java.awt.Color color) {
        message.setText("<html><div style='text-align:center;width:420px'>" + escape(text) + "</div></html>");
        message.setIcon(icon == null ? null : Icons.get(icon));
        message.setForeground(color);
        progress.setVisible(loading);
        showing = MESSAGE;
        cards.show(this, MESSAGE);
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>");
    }
}
