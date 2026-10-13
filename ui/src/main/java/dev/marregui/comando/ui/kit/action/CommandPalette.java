package dev.marregui.comando.ui.kit.action;

import dev.marregui.comando.ui.kit.icon.Icons;
import dev.marregui.comando.ui.kit.theme.Theme;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Window;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Locale;

/** A search box over every command: type, arrow, Enter. Every action is one keystroke away. */
public final class CommandPalette {

    private final ActionRegistry registry;

    public CommandPalette(ActionRegistry registry) {
        this.registry = registry;
    }

    public void show(Window owner) {
        JDialog dialog = new JDialog(owner);
        dialog.setUndecorated(true);
        dialog.setModal(false);
        JTextField search = new JTextField();
        search.putClientProperty("JTextField.placeholderText", "Type a command");
        search.putClientProperty("JTextField.leadingIcon", Icons.get("search"));
        DefaultListModel<Command> model = new DefaultListModel<>();
        JList<Command> list = new JList<>(model);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> l, Object value, int index, boolean selected, boolean focus) {
                Command c = (Command) value;
                JLabel label = (JLabel) super.getListCellRendererComponent(l, c.label(), index, selected, focus);
                label.setIcon(c.icon() == null ? null : Icons.get(c.icon()));
                String key = c.key() == null ? "" : keyText(c.key());
                label.setText("<html>" + escape(c.label()) + (key.isEmpty() ? "" : "&nbsp;&nbsp;<span style='color:"
                        + Theme.hex(Theme.MUTED) + "'>" + key + "</span>") + (c.group().isEmpty() ? "" : "&nbsp;&nbsp;<span style='color:"
                        + Theme.hex(Theme.MUTED) + "'>" + escape(c.group()) + "</span>") + "</html>");
                label.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
                return label;
            }
        });
        // rows are HTML labels: without a prototype the list lays out every row's HTML to size itself, over
        // 200 ms on the EDT at the first opening; with one it measures one row and renders only the visible ones
        registry.all().stream()
                .max(java.util.Comparator.comparingInt((Command c) -> c.label().length() + c.group().length()))
                .ifPresent(list::setPrototypeCellValue);
        Runnable filter = () -> {
            String q = search.getText().strip().toLowerCase(Locale.ROOT);
            model.clear();
            for (Command c : registry.all()) {
                if ((q.isEmpty() || matches(c.label().toLowerCase(Locale.ROOT), q)) && c.enabled().getAsBoolean()) {
                    model.addElement(c);
                }
            }
            if (!model.isEmpty()) {
                list.setSelectedIndex(0);
            }
        };
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                filter.run();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                filter.run();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                filter.run();
            }
        });
        Runnable runSelected = () -> {
            Command c = list.getSelectedValue();
            dialog.dispose();
            if (c != null) {
                javax.swing.SwingUtilities.invokeLater(c.run());
            }
        };
        search.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                switch (e.getKeyCode()) {
                    case KeyEvent.VK_DOWN -> list.setSelectedIndex(Math.min(model.size() - 1, list.getSelectedIndex() + 1));
                    case KeyEvent.VK_UP -> list.setSelectedIndex(Math.max(0, list.getSelectedIndex() - 1));
                    case KeyEvent.VK_ENTER -> runSelected.run();
                    case KeyEvent.VK_ESCAPE -> dialog.dispose();
                    default -> {
                        return;
                    }
                }
                list.ensureIndexIsVisible(list.getSelectedIndex());
                e.consume();
            }
        });
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    runSelected.run();
                }
            }
        });
        dialog.addWindowFocusListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowLostFocus(java.awt.event.WindowEvent e) {
                dialog.dispose();
            }
        });
        JComponent content = (JComponent) dialog.getContentPane();
        content.setLayout(new BorderLayout(0, Theme.GAP));
        content.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Theme.CARD_BORDER),
                BorderFactory.createEmptyBorder(Theme.PAD, Theme.PAD, Theme.PAD, Theme.PAD)));
        content.add(search, BorderLayout.NORTH);
        content.add(new JScrollPane(list), BorderLayout.CENTER);
        filter.run();
        dialog.setSize(new Dimension(560, 380));
        dialog.setLocation(owner.getX() + (owner.getWidth() - 560) / 2, owner.getY() + 90);
        dialog.setVisible(true);
        search.requestFocusInWindow();
    }

    /** Subsequence match, so "rsq" finds "Run as SQL". */
    static boolean matches(String label, String query) {
        int i = 0;
        for (int j = 0; j < label.length() && i < query.length(); j++) {
            if (label.charAt(j) == query.charAt(i)) {
                i++;
            }
        }
        return i == query.length();
    }

    public static String keyText(KeyStroke k) {
        String mods = java.awt.event.InputEvent.getModifiersExText(k.getModifiers());
        String key = KeyEvent.getKeyText(k.getKeyCode());
        return mods.isEmpty() ? key : mods + "+" + key;
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
