package dev.marregui.comando.ui.widget.capability;

import dev.marregui.comando.ui.kit.icon.Icons;
import dev.marregui.comando.ui.kit.theme.Theme;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import java.awt.FlowLayout;
import java.awt.event.KeyEvent;

/** The shared find bar: one instance per window, pointed at whichever {@link Searchable} has focus. */
public final class FindBar extends JPanel {

    private final JTextField text = new JTextField(24);
    private final JLabel count = new JLabel();
    private Searchable target;

    public FindBar() {
        super(new FlowLayout(FlowLayout.LEFT, Theme.GAP, 2));
        setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.CARD_BORDER));
        text.putClientProperty("JTextField.leadingIcon", Icons.get("search"));
        text.putClientProperty("JTextField.placeholderText", "Find");
        text.addActionListener(e -> find(true));
        text.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.SHIFT_DOWN_MASK), "previous");
        text.getActionMap().put("previous", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                find(false);
            }
        });
        text.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close");
        text.getActionMap().put("close", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                close();
            }
        });
        JButton close = new JButton(Icons.get("close"));
        close.putClientProperty("JButton.buttonType", "toolBarButton");
        close.addActionListener(e -> close());
        count.setForeground(Theme.MUTED);
        add(text);
        add(count);
        add(close);
        setVisible(false);
    }

    /** Opens the bar for {@code searchable}; does nothing when it is null. */
    public void open(Searchable searchable) {
        if (searchable == null) {
            return;
        }
        target = searchable;
        setVisible(true);
        text.selectAll();
        text.requestFocusInWindow();
        revalidateParent();
    }

    private void find(boolean forward) {
        if (target != null && !text.getText().isEmpty()) {
            int n = target.find(text.getText(), forward);
            count.setText(n == 0 ? "no matches" : n + (n == 1 ? " match" : " matches"));
        }
    }

    public void close() {
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
