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
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Window;
import java.awt.event.InputEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** A search box over every command: type, arrow, Enter. Every action is one keystroke away. */
public final class CommandPalette {

    private final ActionRegistry registry;

    public CommandPalette(ActionRegistry registry) {
        this.registry = registry;
    }

    public void show(Window owner) {
        // judged now, with the window's focus: once the palette has the keyboard, "the editor has focus" is false
        Set<Command> runnable = registry.all().stream().filter(c -> c.enabled().getAsBoolean()).collect(Collectors.toSet());
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
                .max(Comparator.comparingInt((Command c) -> c.label().length() + c.group().length()))
                .ifPresent(list::setPrototypeCellValue);
        Runnable filter = () -> {
            String q = search.getText().strip().toLowerCase(Locale.ROOT);
            model.clear();
            List<Command> found = new ArrayList<>();
            for (Command c : registry.all()) {
                // the menu path counts too: "model" finds the models, whose labels are just their names
                if ((q.isEmpty() || matches((c.label() + " " + c.group()).toLowerCase(Locale.ROOT), q)) && runnable.contains(c)) {
                    found.add(c);
                }
            }
            // a match in the label first; one in the menu path only after them
            found.sort(Comparator.comparingInt(c -> {
                String label = c.label().toLowerCase(Locale.ROOT);
                return matches(label, q) ? rank(label, q) : 4;
            }));
            found.forEach(model::addElement);
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
                SwingUtilities.invokeLater(c.run());
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
        dialog.addWindowFocusListener(new WindowAdapter() {
            @Override
            public void windowLostFocus(WindowEvent e) {
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

    /**
     * How well {@code label} matches: 0 when it starts with the query, 1 when a word does, 2 when it contains it,
     * 3 for a loose subsequence ("rsq" finds "Run as SQL"); the list shows the best first, in registration order.
     */
    static int rank(String label, String query) {
        if (query.isEmpty() || label.startsWith(query)) {
            return 0;
        }
        if (label.contains(" " + query)) {
            return 1;
        }
        return label.contains(query) ? 2 : 3;
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

    /** A key as the Mac writes it: modifiers ⌃⌥⇧⌘ in that order, then the key ("⇧⌘⏎", "⌃1"). */
    public static String keyText(KeyStroke k) {
        int m = k.getModifiers();
        StringBuilder sb = new StringBuilder();
        if ((m & InputEvent.CTRL_DOWN_MASK) != 0) {
            sb.append('⌃');
        }
        if ((m & InputEvent.ALT_DOWN_MASK) != 0) {
            sb.append('⌥');
        }
        if ((m & InputEvent.SHIFT_DOWN_MASK) != 0) {
            sb.append('⇧');
        }
        if ((m & InputEvent.META_DOWN_MASK) != 0) {
            sb.append('⌘');
        }
        return sb.append(switch (k.getKeyCode()) {
            case KeyEvent.VK_ENTER -> "⏎";
            case KeyEvent.VK_PERIOD -> ".";
            case KeyEvent.VK_BACK_SLASH -> "\\";
            case KeyEvent.VK_EQUALS -> "=";
            case KeyEvent.VK_MINUS -> "-";
            case KeyEvent.VK_OPEN_BRACKET -> "[";
            case KeyEvent.VK_CLOSE_BRACKET -> "]";
            case KeyEvent.VK_TAB -> "⇥";
            case KeyEvent.VK_ESCAPE -> "⎋";
            case KeyEvent.VK_SPACE -> "Space";
            case KeyEvent.VK_LEFT -> "←";
            case KeyEvent.VK_RIGHT -> "→";
            case KeyEvent.VK_UP -> "↑";
            case KeyEvent.VK_DOWN -> "↓";
            default -> KeyEvent.getKeyText(k.getKeyCode());
        }).toString();
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
