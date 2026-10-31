package dev.marregui.comando.ui.widget.capability;

import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import dev.marregui.comando.ui.kit.async.Debouncer;
import dev.marregui.comando.ui.kit.icon.Icons;
import dev.marregui.comando.ui.kit.theme.Theme;

/** The shared find bar: one instance per window, pointed at whichever {@link Searchable} has focus. */
public final class FindBar extends JPanel {

    private final JTextField text = new JTextField(24);
    private final JLabel count = new JLabel();
    private Searchable target;
    private final Debouncer typing = new Debouncer(250, () -> {
        if (target != null) {
            // a longer search text starts where the current match begins, so the match under the cursor is not skipped
            target.rewindFind();
            if (text.getText().isEmpty()) {
                count.setText("");
            } else {
                find(true);
            }
        }
    });

    public FindBar() {
        super(new FlowLayout(FlowLayout.LEFT, Theme.GAP, 2));
        setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.CARD_BORDER));
        text.putClientProperty("JTextField.leadingIcon", Icons.get("search"));
        text.putClientProperty("JTextField.placeholderText", "Find");
        text.addActionListener(e -> {
            typing.stop();
            find(true);
        });
        // find as you type, once typing pauses; ⏎ and ⇧⏎ then step through the matches
        text.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(final DocumentEvent e) {
                typing.request();
            }

            @Override
            public void removeUpdate(final DocumentEvent e) {
                typing.request();
            }

            @Override
            public void changedUpdate(final DocumentEvent e) {
                // attributes only
            }
        });
        text.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.SHIFT_DOWN_MASK), "previous");
        text.getActionMap().put("previous", new AbstractAction() {
            @Override
            public void actionPerformed(final ActionEvent e) {
                find(false);
            }
        });
        text.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close");
        text.getActionMap().put("close", new AbstractAction() {
            @Override
            public void actionPerformed(final ActionEvent e) {
                close();
            }
        });
        final JButton close = new JButton(Icons.get("close"));
        close.setToolTipText("Close (Esc)");
        close.getAccessibleContext().setAccessibleName("Close the find bar");
        text.getAccessibleContext().setAccessibleName("Find");
        close.putClientProperty("JButton.buttonType", "toolBarButton");
        close.addActionListener(e -> close());
        count.setForeground(Theme.MUTED);
        add(text);
        add(count);
        add(close);
        setVisible(false);
    }

    /** Opens the bar for {@code searchable}; does nothing when it is null. */
    public void open(final Searchable searchable) {
        if (searchable == null) {
            return;
        }
        target = searchable;
        setVisible(true);
        text.selectAll();
        text.requestFocusInWindow();
        revalidateParent();
    }

    /** Whether the view the bar searches is still in the window; true when it cannot tell. */
    public boolean targetShowing() {
        final JComponent root = target == null ? null : Capabilities.rootOf(target);
        return root == null || root.isDisplayable();
    }

    /** Whether the bar is open on a view. */
    public boolean isOpen() {
        return target != null;
    }

    /** Finds the next or previous match of the bar's text (⌘G, ⌘⇧G); does nothing when the bar is closed. */
    public void next(final boolean forward) {
        find(forward);
    }

    private void find(final boolean forward) {
        if (target != null && !text.getText().isEmpty()) {
            final int n = target.find(text.getText(), forward);
            count.setText(n == 0 ? "no matches" : n + (n == 1 ? " match" : " matches"));
        }
    }

    /** Clears the target's highlights and hides the bar. */
    public void close() {
        typing.stop();
        if (target != null) {
            target.clearFind();
        }
        target = null;
        setVisible(false);
        revalidateParent();
    }

    private void revalidateParent() {
        if (getParent() instanceof JComponent p) {
            p.revalidate();
            p.repaint();
        }
    }
}
