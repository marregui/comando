package dev.marregui.comando.ui.dialogs;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.WindowConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.JTextComponent;

import dev.marregui.comando.ui.kit.icon.Icons;
import dev.marregui.comando.ui.kit.text.MarkdownHtml;
import dev.marregui.comando.ui.kit.text.Plain;
import dev.marregui.comando.ui.kit.theme.Theme;

/**
 * The form the folder tabs' folders window and the kit jobs window share: a list beside labelled fields, each field
 * with its label, accessible name and tooltip, outlined in red while it fails, and a message line under the fields
 * naming the first that fails. An instance holds one window's fields and lays them out as rows; the static parts
 * build the list beside them, the window around them and its buttons, alike in both windows. EDT only.
 */
public final class FormRows {

    /** Styles a list's cell for {@code value}, its place {@code index}, selected or not. */
    @FunctionalInterface
    public interface Look<T> {
        void style(JLabel cell, T value, int index, boolean selected);
    }

    private final JPanel panel = new JPanel(new GridBagLayout());
    /** One line under the fields: what was refused or the first problem in red, else a note in grey. */
    private final JLabel message = new JLabel(" ");
    /** The inputs by key, outlined in red while they fail. */
    private final Map<String, JComponent> inputs = new HashMap<>();
    /** The labels by key, naming each input in the message line. */
    private final Map<String, String> labels = new HashMap<>();
    /** The next free grid row. */
    private int y;

    /** The rows laid out so far. */
    public JPanel panel() {
        return panel;
    }

    /**
     * Adds a row: {@code label}, then {@code field}, which holds {@code input}, the component editing {@code key}; the
     * label and the input explain it under the mouse with {@code tip}, the input takes the label as its accessible
     * name, and the label points at it.
     */
    public void row(final String label, final JComponent field, final JComponent input, final String key,
                    final String tip) {
        final GridBagConstraints g = new GridBagConstraints();
        g.gridx = 0;
        g.gridy = y;
        g.anchor = GridBagConstraints.LINE_END;
        g.insets = new Insets(y == 0 ? 0 : 2, 0, 0, Theme.GAP);
        final JLabel caption = new JLabel(label);
        caption.setToolTipText(tip);
        panel.add(caption, g);
        g.gridx = 1;
        g.anchor = GridBagConstraints.LINE_START;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.weightx = 1;
        g.insets = new Insets(y == 0 ? 0 : 2, 0, 0, 0);
        panel.add(field, g);
        input.setToolTipText(tip);
        input.getAccessibleContext().setAccessibleName(label);
        caption.setLabelFor(input);
        input(key, input, label);
        y++;
    }

    /** Takes {@code input}, editing {@code key}, among the outlined inputs, named {@code label} in the message line. */
    public void input(final String key, final JComponent input, final String label) {
        inputs.put(key, input);
        labels.put(key, label);
    }

    /**
     * Adds, under the rows, the message line, then {@code facts}, a muted line named {@code factsName}, and below them
     * the space that keeps the rows at the top.
     */
    public void footer(final JLabel facts, final String factsName) {
        final GridBagConstraints g = new GridBagConstraints();
        g.gridx = 1;
        g.gridy = y;
        g.anchor = GridBagConstraints.LINE_START;
        g.insets = new Insets(Theme.GAP, 0, 0, 0);
        message.getAccessibleContext().setAccessibleName("Problem");
        panel.add(message, g);
        g.gridy = y + 1;
        g.insets = new Insets(2, 0, 0, 0);
        facts.setForeground(Theme.MUTED_STRONG);
        facts.getAccessibleContext().setAccessibleName(factsName);
        panel.add(facts, g);
        g.gridy = y + 2;
        g.weighty = 1;
        panel.add(new JPanel(), g);
    }

    /**
     * Shows {@code problems} (by key): outlines in red the input of each whose text {@code marks} accepts, clearing the
     * others, and says in the message line {@code refused} when it is not empty, else the first problem as "Label:
     * why", else {@code note}; the line is red for what was refused or a problem {@code marks} accepts, grey otherwise.
     */
    public void show(final Map<String, String> problems, final Predicate<String> marks, final String refused,
                     final String note) {
        inputs.forEach((key, field) -> field.putClientProperty("JComponent.outline",
                problems.containsKey(key) && marks.test(problems.get(key)) ? "error" : null));
        final Map.Entry<String, String> first = problems.entrySet().stream().findFirst().orElse(null);
        final String text = !refused.isEmpty() ? refused
                : first != null ? labels.getOrDefault(first.getKey(), first.getKey()) + ": " + first.getValue() : note;
        message.setText(text.isEmpty() ? " " : Plain.text(text));
        final boolean red = !refused.isEmpty() || first != null && marks.test(first.getValue());
        message.setForeground(red ? Theme.DANGER : Theme.MUTED_STRONG);
    }

    /** The message line's text, for tests. */
    public String messageShown() {
        return message.getText().strip();
    }

    // ---- the parts around the rows

    /**
     * The list's side: {@code list}, single selection, each of its {@code type} cells styled by {@code look}, named
     * {@code name}, running {@code load} when the selection settles, in a {@code size} square scroll pane over
     * {@code buttons}.
     */
    public static <T> JPanel listSide(final JList<T> list, final Class<T> type, final Look<T> look, final String name,
                                      final int size, final Runnable load, final JButton... buttons) {
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(cells(type, look));
        list.getAccessibleContext().setAccessibleName(name);
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                load.run();
            }
        });
        final JScrollPane scroll = new JScrollPane(list);
        scroll.setPreferredSize(new Dimension(size, size));
        final JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEADING, 2, 0));
        for (final JButton b : buttons) {
            bar.add(b);
        }
        final JPanel side = new JPanel(new BorderLayout(0, Theme.GAP));
        side.add(scroll, BorderLayout.CENTER);
        side.add(bar, BorderLayout.SOUTH);
        return side;
    }

    /** Lays {@code side}, the list's, beside {@code form} in {@code body}, padded. */
    public static void lay(final JPanel body, final JComponent side, final JComponent form) {
        body.setBorder(BorderFactory.createEmptyBorder(Theme.PAD, Theme.PAD, Theme.PAD, Theme.PAD));
        body.add(side, BorderLayout.WEST);
        body.add(form, BorderLayout.CENTER);
    }

    /** A cell renderer that lets {@code look} style each cell holding a {@code type}, and leaves the others plain. */
    public static <T> DefaultListCellRenderer cells(final Class<T> type, final Look<T> look) {
        return new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(final JList<?> l, final Object value, final int i,
                                                          final boolean sel, final boolean focus) {
                final JLabel c = (JLabel) super.getListCellRendererComponent(l, value, i, sel, focus);
                if (type.isInstance(value)) {
                    look.style(c, type.cast(value), i, sel);
                }
                return c;
            }
        };
    }

    /** Runs {@code store} on every change to the text of each of {@code fields}. */
    public static void onEdit(final Runnable store, final List<? extends JTextComponent> fields) {
        final DocumentListener listener = new DocumentListener() {
            @Override
            public void insertUpdate(final DocumentEvent e) {
                store.run();
            }

            @Override
            public void removeUpdate(final DocumentEvent e) {
                store.run();
            }

            @Override
            public void changedUpdate(final DocumentEvent e) {
                store.run();
            }
        };
        for (final JTextComponent f : fields) {
            f.getDocument().addDocumentListener(listener);
        }
    }

    /** Removes the selected element of {@code rows}, shown by {@code list}, selecting the next; false with none. */
    public static <T> boolean removeSelected(final JList<T> list, final DefaultListModel<T> rows) {
        final int i = list.getSelectedIndex();
        if (i < 0) {
            return false;
        }
        rows.remove(i);
        if (!rows.isEmpty()) {
            list.setSelectedIndex(Math.min(i, rows.size() - 1));
        }
        return true;
    }

    /** Selects the first element of {@code rows}, shown by {@code list}, that {@code which} accepts, if any. */
    public static <T> void select(final JList<T> list, final DefaultListModel<T> rows, final Predicate<T> which) {
        for (int i = 0; i < rows.size(); i++) {
            if (which.test(rows.get(i))) {
                list.setSelectedIndex(i);
                return;
            }
        }
    }

    /** Selects {@code list}'s first element, when it has one. */
    public static void selectFirst(final JList<?> list) {
        if (list.getModel().getSize() > 0) {
            list.setSelectedIndex(0);
        }
    }

    /** Whether some element of {@code rows} is accepted by {@code which}. */
    public static <T> boolean any(final DefaultListModel<T> rows, final Predicate<T> which) {
        for (int i = 0; i < rows.size(); i++) {
            if (which.test(rows.get(i))) {
                return true;
            }
        }
        return false;
    }

    /** {@code field} with {@code button} at its end. */
    public static JPanel withButton(final JComponent field, final JButton button) {
        final JPanel p = new JPanel(new BorderLayout(4, 0));
        p.add(field, BorderLayout.CENTER);
        p.add(button, BorderLayout.EAST);
        return p;
    }

    /** A tooltip of a few lines. */
    public static String lines(final String... lines) {
        return "<html>" + String.join("<br>", List.of(lines).stream().map(MarkdownHtml::escape).toList()) + "</html>";
    }

    /** A tool bar button showing icon {@code icon}, {@code tip} its tooltip and accessible name. */
    public static JButton tool(final String icon, final String tip) {
        return tool(new JButton(Icons.get(icon)), tip);
    }

    /** {@code b} as a tool bar button, {@code tip} its tooltip and accessible name. */
    public static JButton tool(final JButton b, final String tip) {
        b.putClientProperty("JButton.buttonType", "toolBarButton");
        b.setToolTipText(tip);
        b.getAccessibleContext().setAccessibleName(tip);
        return b;
    }

    /** The window's bottom: {@code cancel} and {@code save}, the default button, at its end; padded. */
    public static JPanel south(final JButton cancel, final JButton save) {
        save.putClientProperty("JButton.buttonType", "default");
        final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.TRAILING, Theme.GAP, 0));
        buttons.add(cancel);
        buttons.add(save);
        final JPanel south = new JPanel(new BorderLayout());
        south.setBorder(BorderFactory.createEmptyBorder(0, Theme.PAD, Theme.PAD, Theme.PAD));
        south.add(buttons, BorderLayout.EAST);
        return south;
    }

    /**
     * Fills {@code dialog} with {@code editor} over {@code south}, hands Esc and the window's close to {@code cancel},
     * which decides whether it closes, and sizes it over {@code owner}.
     */
    public static void frame(final JDialog dialog, final Window owner, final JComponent editor, final JComponent south,
                             final Runnable cancel) {
        dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        dialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(final WindowEvent e) {
                cancel.run();
            }
        });
        final JPanel content = new JPanel(new BorderLayout());
        content.add(editor, BorderLayout.CENTER);
        content.add(south, BorderLayout.SOUTH);
        dialog.setContentPane(content);
        final JComponent root = dialog.getRootPane();
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cancel");
        root.getActionMap().put("cancel", new AbstractAction() {
            @Override
            public void actionPerformed(final ActionEvent e) {
                cancel.run();
            }
        });
        dialog.pack();
        dialog.setLocationRelativeTo(owner);
    }
}
