package dev.marregui.comando.ui.kit.overlay;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.GridBagLayout;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;

import dev.marregui.comando.ui.kit.icon.Icons;
import dev.marregui.comando.ui.kit.text.MarkdownHtml;
import dev.marregui.comando.ui.kit.theme.Theme;

/**
 * A widget's content and its empty, loading and error states, one shown at a time. A widget wraps its content once
 * and switches states, so no widget draws its own progress bar or placeholder.
 */
public final class Overlay extends JPanel {

    private static final String CONTENT = "content";
    private static final String MESSAGE = "message";
    private static final String ERROR = "error";

    private final CardLayout cards = new CardLayout();
    private final JLabel message = new JLabel("", SwingConstants.CENTER);
    private final JProgressBar progress = new JProgressBar();
    private final JTextArea error = new JTextArea();
    private String showing = CONTENT;
    private String messageText = "";

    public Overlay(final JComponent content) {
        setLayout(cards);
        // indeterminate only while loading: an indeterminate bar animates on a 15 ms timer even while hidden
        progress.setVisible(false);
        // the text is a centred block of fixed width: an icon beside it lands far from short messages
        message.setHorizontalTextPosition(SwingConstants.CENTER);
        message.setVerticalTextPosition(SwingConstants.BOTTOM);
        final JPanel inner = new JPanel(new BorderLayout(0, Theme.GAP));
        inner.setOpaque(false);
        inner.add(message, BorderLayout.CENTER);
        inner.add(progress, BorderLayout.SOUTH);
        final JPanel centered = new JPanel(new GridBagLayout());
        centered.setBorder(BorderFactory.createEmptyBorder(Theme.PAD, Theme.PAD, Theme.PAD, Theme.PAD));
        centered.add(inner);
        // an error is text to read, select and copy: a read-only area, not a label
        error.setEditable(false);
        error.setLineWrap(true);
        error.setWrapStyleWord(true);
        error.setFont(Theme.mono());
        error.setForeground(Theme.DANGER);
        error.setBorder(BorderFactory.createEmptyBorder(Theme.PAD, Theme.PAD, Theme.PAD, Theme.PAD));
        error.getAccessibleContext().setAccessibleName("Error");
        final JLabel errorIcon = new JLabel(Icons.get("error"));
        errorIcon.setVerticalAlignment(SwingConstants.TOP);
        errorIcon.setBorder(BorderFactory.createEmptyBorder(Theme.PAD, Theme.PAD, 0, 0));
        final JPanel errorPanel = new JPanel(new BorderLayout());
        errorPanel.add(errorIcon, BorderLayout.WEST);
        final JScrollPane errorScroll = new JScrollPane(error);
        errorScroll.setBorder(BorderFactory.createEmptyBorder());
        errorPanel.add(errorScroll, BorderLayout.CENTER);
        add(content, CONTENT);
        add(centered, MESSAGE);
        add(errorPanel, ERROR);
        cards.show(this, CONTENT);
    }

    public void showContent() {
        loading(false);
        if (!showing.equals(CONTENT)) {
            showing = CONTENT;
            cards.show(this, CONTENT);
        }
    }

    public void showEmpty(final String text) {
        show(text, null, false, Theme.MUTED);
    }

    public void showLoading(final String text) {
        show(text, null, true, Theme.MUTED);
    }

    /** The error as selectable text, scrolled to its start. */
    public void showError(final String text) {
        loading(false);
        if (!error.getText().equals(text)) {
            error.setText(text);
            error.setCaretPosition(0);
        }
        showing = ERROR;
        cards.show(this, ERROR);
    }

    /** The error text shown, or empty; for copying and tests. */
    public String errorText() {
        return showing.equals(ERROR) ? error.getText() : "";
    }

    /** The empty or loading message shown, or empty; for tests. */
    public String messageText() {
        return showing.equals(MESSAGE) ? messageText : "";
    }

    private void show(final String text, final String icon, final boolean loading, final Color color) {
        messageText = text;
        message.setText("<html><div style='text-align:center;width:420px'>" + escape(text) + "</div></html>");
        message.setIcon(icon == null ? null : Icons.get(icon));
        message.setForeground(color);
        loading(loading);
        showing = MESSAGE;
        cards.show(this, MESSAGE);
    }

    private void loading(final boolean on) {
        if (progress.isIndeterminate() != on) {
            progress.setIndeterminate(on);
        }
        progress.setVisible(on);
    }

    /** Whether the loading bar animates; for tests. */
    boolean animating() {
        return progress.isIndeterminate();
    }

    private static String escape(final String s) {
        return MarkdownHtml.escape(s).replace("\n", "<br>");
    }
}
