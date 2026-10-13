package dev.marregui.comando.ui.widgets.editor;

import dev.marregui.comando.ui.kit.icon.Icons;
import dev.marregui.comando.ui.kit.theme.Theme;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import java.awt.BorderLayout;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * One line to the panel's session: Enter sends, Up and Down walk the history. A prompt sent while a turn
 * runs is queued by Claude Code and folded into that turn.
 */
public final class PromptBar extends JPanel {

    private static final int HISTORY = 200;

    private final JTextField field = new JTextField();
    private final List<String> history = new ArrayList<>();
    private int cursor;

    public PromptBar(Consumer<String> send) {
        super(new BorderLayout());
        setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
        field.putClientProperty("JTextField.leadingIcon", Icons.get("claude"));
        field.putClientProperty("JTextField.showClearButton", true);
        field.setFont(Theme.mono());
        field.addActionListener(e -> {
            String text = field.getText().strip();
            if (text.isEmpty()) {
                return;
            }
            if (history.isEmpty() || !history.getLast().equals(text)) {
                history.add(text);
                if (history.size() > HISTORY) {
                    history.removeFirst();
                }
            }
            cursor = history.size();
            field.setText("");
            send.accept(text);
        });
        key(KeyEvent.VK_UP, () -> walk(-1));
        key(KeyEvent.VK_DOWN, () -> walk(1));
        key(KeyEvent.VK_ESCAPE, () -> field.setText(""));
        add(field, BorderLayout.CENTER);
    }

    public void setTarget(String session, boolean busy) {
        String hint = session.isEmpty() ? "Bind a session to talk to Claude"
                : busy ? "Message " + session + " — a turn is running; Enter queues"
                : "Message " + session + " — Enter sends, ↑ history";
        field.putClientProperty("JTextField.placeholderText", hint);
        field.repaint();
    }

    public void focus() {
        field.requestFocusInWindow();
    }

    private void walk(int delta) {
        if (history.isEmpty()) {
            return;
        }
        cursor = Math.max(0, Math.min(history.size(), cursor + delta));
        field.setText(cursor == history.size() ? "" : history.get(cursor));
    }

    private void key(int code, Runnable r) {
        String id = "prompt-" + code;
        field.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(code, 0), id);
        field.getActionMap().put(id, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                r.run();
            }
        });
    }
}
