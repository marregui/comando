package dev.marregui.comando.ui.widgets.editor;

import dev.marregui.comando.ui.PromptHistory;
import dev.marregui.comando.ui.kit.icon.Icons;
import dev.marregui.comando.ui.kit.theme.Theme;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.ScrollPaneConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultEditorKit;
import javax.swing.text.PlainDocument;
import javax.swing.text.Utilities;
import javax.swing.text.View;
import java.awt.BorderLayout;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * The prompt to the panel's session. ⏎ sends and ⇧⏎ starts a new line; the field grows by wrapped rows to six.
 * ↑ on the first row and ↓ on the last walk the session's history, which is kept across runs and shared by the
 * panels bound to the session; elsewhere they move one row. A prompt the panel cannot send stays in the field,
 * outlined, until it is edited. Esc gives the keyboard back to the editor.
 */
public final class PromptBar extends JPanel {

    /** Decides whether a prompt can go; returns false when it stays in the field. */
    public interface Sender {
        boolean send(String text);
    }

    static final int MAX_ROWS = 6;
    private static final int HISTORY = 200;

    /** Typed first, so the field's view has taken each edit in before the bar measures the rows. */
    private final PlainDocument document = new PlainDocument();
    private String placeholder = "";

    {
        // listeners hear an edit newest first: this one, added before the field (and so its view) exists, hears
        // each edit after the view has taken it in, so the rows the bar measures are the new text's
        document.addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                edited();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                edited();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                // attributes only
            }
        });
    }

    private final JTextArea field = new JTextArea(document, null, 1, 20) {
        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            paintPlaceholder(this, g);
        }
    };
    private final JScrollPane scroll = new JScrollPane(field, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
    private final Sender sender;
    private final PromptHistory store;
    /** The session's history as it stood when the walk started; empty while not walking. */
    private final List<String> history = new ArrayList<>();
    private Runnable onEscape = () -> {
    };
    private String session = "";
    private int cursor;
    private String draft = "";

    public PromptBar(Sender sender, PromptHistory store) {
        super(new BorderLayout(Theme.PAD, 0));
        this.sender = sender;
        this.store = store;
        setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
        field.setFont(Theme.mono());
        field.setLineWrap(true);
        field.setWrapStyleWord(true);
        field.setName("prompt");
        field.getAccessibleContext().setAccessibleName("Prompt to the panel's session");
        scroll.putClientProperty("JComponent.roundRect", true);
        scroll.getViewport().addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                fitRows();
            }
        });
        key(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), this::sendNow);
        key(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK), () -> field.replaceSelection("\n"));
        key(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.ALT_DOWN_MASK), () -> field.replaceSelection("\n"));
        key(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), () -> {
            if (onFirstRow()) {
                walk(-1);
            } else {
                moveRow(DefaultEditorKit.upAction);
            }
        });
        key(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), () -> {
            if (onLastRow()) {
                walk(1);
            } else {
                moveRow(DefaultEditorKit.downAction);
            }
        });
        key(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), () -> onEscape.run());
        JLabel icon = new JLabel(Icons.get("claude"));
        add(icon, BorderLayout.WEST);
        add(scroll, BorderLayout.CENTER);
        fitRows();
    }

    /** What Esc does: the panel gives the keyboard back to its editor. */
    public void onEscape(Runnable r) {
        onEscape = r;
    }

    public void setTarget(String newSession, boolean busy) {
        String hint = newSession.isEmpty() ? "Bind a session to talk to Claude"
                : busy ? "Message " + newSession + " — a turn is running; ⏎ queues"
                : "Message " + newSession + " — ⏎ sends, ⇧⏎ new line, ↑ history";
        placeholder = hint;
        field.getAccessibleContext().setAccessibleDescription(hint);
        if (!newSession.equals(session)) {
            session = newSession;
            history.clear();
            cursor = 0;
        }
        field.repaint();
    }

    /** The hint shown while the field is empty. */
    String placeholder() {
        return placeholder;
    }

    /** FlatLaf paints a placeholder only in a text field; the bar is a text area, so it paints its own. */
    private void paintPlaceholder(JTextArea area, Graphics g) {
        if (placeholder.isEmpty() || area.getDocument().getLength() > 0) {
            return;
        }
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setFont(area.getFont());
            g2.setColor(Theme.MUTED);
            Insets in = area.getInsets();
            FontMetrics fm = g2.getFontMetrics();
            g2.clipRect(in.left, in.top, area.getWidth() - in.left - in.right, area.getHeight() - in.top - in.bottom);
            g2.drawString(placeholder, in.left, in.top + fm.getAscent());
        } finally {
            g2.dispose();
        }
    }

    public void focus() {
        field.requestFocusInWindow();
    }

    /** True while the keyboard is in the field: the run keys send its text instead of the editor's block. */
    public boolean hasFocus() {
        return field.isFocusOwner();
    }

    public String text() {
        return field.getText();
    }

    /** Sends the field's text, as ⏎ does; the run keys call it while the field has the keyboard. */
    public void sendNow() {
        String text = field.getText().strip();
        if (text.isEmpty()) {
            return;
        }
        if (!sender.send(text)) {
            // FlatLaf draws the outline of the component that owns the border: the scroll pane, not the text area
            scroll.putClientProperty("JComponent.outline", "error");
            return;
        }
        remember(text);
        field.setText("");
    }

    /** The store keeps the prompt; the next walk reads it from there, as do the other panels bound to the session. */
    private void remember(String text) {
        if (!session.isEmpty()) {
            store.add(session, text);
        }
        history.clear();
        cursor = 0;
        draft = "";
    }

    private void edited() {
        scroll.putClientProperty("JComponent.outline", null);
        fitRows();
    }

    /** One row per wrapped row of text, up to {@link #MAX_ROWS}, then a scroll bar. */
    void fitRows() {
        int rows = Math.max(1, Math.min(MAX_ROWS, visualRows()));
        if (field.getRows() != rows) {
            field.setRows(rows);
            revalidate();
        }
    }

    /** The rows the text takes when wrapped at the viewport's width; the lines of text before the bar is laid out. */
    private int visualRows() {
        Insets in = field.getInsets();
        int width = scroll.getViewport().getWidth() - in.left - in.right;
        if (width <= 0) {
            return field.getLineCount();
        }
        View root = field.getUI().getRootView(field);
        root.setSize(width, Short.MAX_VALUE);
        float height = root.getPreferredSpan(View.Y_AXIS);
        int rowHeight = field.getFontMetrics(field.getFont()).getHeight();
        return rowHeight <= 0 ? field.getLineCount() : Math.max(1, Math.round(height / rowHeight));
    }

    int rows() {
        return field.getRows();
    }

    /** The caret is on the first wrapped row; before the bar is laid out, on the first line. */
    private boolean onFirstRow() {
        try {
            int start = Utilities.getRowStart(field, field.getCaretPosition());
            return start < 0 ? field.getLineOfOffset(field.getCaretPosition()) == 0 : start == 0;
        } catch (BadLocationException e) {
            return true;
        }
    }

    /** The caret is on the last wrapped row; before the bar is laid out, on the last line. */
    private boolean onLastRow() {
        try {
            int end = Utilities.getRowEnd(field, field.getCaretPosition());
            return end < 0 ? field.getLineOfOffset(field.getCaretPosition()) == field.getLineCount() - 1
                    : end >= field.getDocument().getLength();
        } catch (BadLocationException e) {
            return true;
        }
    }

    /** One wrapped row up or down, as the text area's own caret keys move. */
    private void moveRow(String action) {
        javax.swing.Action a = field.getActionMap().get(action);
        if (a != null) {
            a.actionPerformed(new ActionEvent(field, ActionEvent.ACTION_PERFORMED, action));
        }
    }

    /**
     * Walks the history; the text being typed is kept and comes back after the newest entry. A walk that starts
     * from the text being typed reads the session's history afresh, so a prompt sent from another panel is there.
     */
    private void walk(int delta) {
        if (cursor == history.size()) {
            history.clear();
            if (!session.isEmpty()) {
                List<String> kept = store.load(session);
                history.addAll(kept.subList(Math.max(0, kept.size() - HISTORY), kept.size()));
            }
            cursor = history.size();
            draft = field.getText();
        }
        if (history.isEmpty()) {
            return;
        }
        cursor = Math.max(0, Math.min(history.size(), cursor + delta));
        field.setText(cursor == history.size() ? draft : history.get(cursor));
        field.setCaretPosition(field.getDocument().getLength());
    }

    private void key(KeyStroke stroke, Runnable r) {
        String id = "prompt-" + stroke;
        field.getInputMap(JComponent.WHEN_FOCUSED).put(stroke, id);
        field.getActionMap().put(id, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                r.run();
            }
        });
    }

    JTextArea field() {
        return field;
    }

    JScrollPane scroll() {
        return scroll;
    }
}
