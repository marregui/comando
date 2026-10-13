package dev.marregui.comando.ui.dialogs.resource;

import dev.marregui.comando.ui.kit.text.Plain;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.Status;
import dev.marregui.comando.core.port.IntentSink;
import dev.marregui.comando.core.resource.Attribute;
import dev.marregui.comando.core.resource.ResourceKind;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.ui.kit.icon.Icons;
import dev.marregui.comando.ui.kit.theme.Theme;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The one dialog for configurable resources: a list on the left, a form generated from the kind's
 * attributes on the right, and Add, Clone, Remove, Test, Open/Close, Assign and Save. Connections and
 * sessions are two configurations of it.
 *
 * @param <S> the spec record
 */
public final class ResourceDialog<S> {

    /** What differs between connections and sessions. */
    public record Config<S>(
            ResourceKind<S> kind,
            Function<AppState, List<S>> specs,
            Function<AppState, Map<String, Status>> statuses,
            java.util.function.BiFunction<String, S, Intent> save,
            Function<String, Intent> remove,
            Function<S, Intent> test,
            Function<String, Intent> open,
            Function<String, Intent> close,
            java.util.function.BiFunction<AppState, String, Intent> assign,
            String icon,
            Runnable extra,
            String extraLabel) {
    }

    private final Config<S> config;
    private final IntentSink intents;
    private final JDialog dialog;
    private final DefaultListModel<String> names = new DefaultListModel<>();
    private final JList<String> list = new JList<>(names);
    private final Map<String, JComponent> fields = new LinkedHashMap<>();
    private final JLabel message = new JLabel(" ");
    private final JButton openClose = new JButton();
    private AppState state;
    private String editing = "";
    /** The values the form was last filled with: what the user has not changed since is refreshed from the state. */
    private Map<String, String> filled = Map.of();

    public ResourceDialog(Window owner, Config<S> config, IntentSink intents) {
        this.config = config;
        this.intents = intents;
        String noun = config.kind().noun();
        dialog = new JDialog(owner, Character.toUpperCase(noun.charAt(0)) + noun.substring(1) + "s");
        dialog.setModal(false);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> l, Object value, int index, boolean selected, boolean focus) {
                JLabel label = (JLabel) super.getListCellRendererComponent(l, value, index, selected, focus);
                Status st = state == null ? Status.CLOSED : config.statuses().apply(state).getOrDefault((String) value, Status.CLOSED);
                label.setIcon(Icons.get(config.icon()));
                label.setText(Plain.text(value + "   " + st.name().toLowerCase()));
                label.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
                return label;
            }
        });
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && list.getSelectedValue() != null) {
                edit(list.getSelectedValue());
            }
        });
        JPanel left = new JPanel(new BorderLayout(0, Theme.GAP));
        left.add(new JScrollPane(list), BorderLayout.CENTER);
        JPanel leftButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        leftButtons.add(button("Add", "add", this::add));
        leftButtons.add(button("Clone", "copy", this::cloneSelected));
        leftButtons.add(button("Remove", "close", this::removeSelected));
        if (config.extra() != null) {
            leftButtons.add(button(config.extraLabel(), "import", config.extra()));
        }
        left.add(leftButtons, BorderLayout.SOUTH);
        left.setBorder(BorderFactory.createEmptyBorder(Theme.PAD, Theme.PAD, Theme.PAD, 0));

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(3, 3, 3, 3);
        int row = 0;
        for (Attribute a : config.kind().attributes()) {
            g.gridx = 0;
            g.gridy = row;
            g.anchor = GridBagConstraints.LINE_END;
            g.fill = GridBagConstraints.NONE;
            g.weightx = 0;
            form.add(new JLabel(a.label()), g);
            g.gridx = 1;
            g.anchor = GridBagConstraints.LINE_START;
            g.fill = GridBagConstraints.HORIZONTAL;
            g.weightx = 1;
            JComponent field = field(a);
            fields.put(a.key(), field);
            form.add(field instanceof JTextField t && (a.kind() == Attribute.Kind.DIRECTORY || a.kind() == Attribute.Kind.DIRECTORIES)
                    ? withChooser(t, a.kind() == Attribute.Kind.DIRECTORIES) : field, g);
            if (!a.hint().isEmpty()) {
                field.setToolTipText(a.hint());
            }
            row++;
        }
        g.gridx = 0;
        g.gridy = row;
        g.gridwidth = 2;
        g.weighty = 1;
        form.add(new JPanel(), g);
        message.setForeground(Theme.MUTED);
        JPanel right = new JPanel(new BorderLayout(0, Theme.GAP));
        right.setBorder(BorderFactory.createEmptyBorder(Theme.PAD, Theme.PAD, Theme.PAD, Theme.PAD));
        right.add(form, BorderLayout.CENTER);
        JPanel south = new JPanel(new BorderLayout());
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, Theme.GAP, 0));
        if (config.test() != null) {
            actions.add(button("Test", "check", this::testForm));
        }
        actions.add(openClose);
        openClose.addActionListener(e -> toggleOpen());
        actions.add(button("Assign to panel", "pin", this::assign));
        JButton save = button("Save", "check", this::save);
        save.putClientProperty("JButton.buttonType", "default");
        actions.add(save);
        south.add(message, BorderLayout.NORTH);
        south.add(actions, BorderLayout.SOUTH);
        right.add(south, BorderLayout.SOUTH);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        split.setDividerLocation(230);
        split.setBorder(null);
        dialog.setContentPane(split);
        dialog.setSize(new Dimension(820, 520));
        dialog.getRootPane().registerKeyboardAction(e -> dialog.setVisible(false),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
    }

    public void show(AppState s, String select) {
        render(s);
        if (!select.isEmpty() && names.contains(select)) {
            list.setSelectedValue(select, true);
            edit(select); // selecting the row already selected fires no event, and the form may be stale
        } else if (!names.isEmpty() && list.getSelectedIndex() < 0) {
            list.setSelectedIndex(0);
        } else if (names.isEmpty()) {
            add();
        }
        if (!dialog.isVisible()) {
            // placed on each opening: the main window may have moved, or restored its bounds, since the dialog was built
            dialog.setLocationRelativeTo(dialog.getOwner());
        }
        dialog.setVisible(true);
        dialog.toFront();
    }

    public boolean isVisible() {
        return dialog.isVisible();
    }

    /**
     * Refreshes the list from the state. The form keeps what the user changed; every other field takes the
     * stored value, so a value the application changed meanwhile (a session's conversation id after a
     * start) is not written back stale by the next Save.
     */
    public void render(AppState s) {
        state = s;
        List<String> current = config.specs().apply(s).stream().map(config.kind()::nameOf).toList();
        String selected = list.getSelectedValue();
        if (!current.equals(java.util.Collections.list(names.elements()))) {
            names.clear();
            names.addAll(current);
            if (selected != null && current.contains(selected)) {
                list.setSelectedValue(selected, false);
            } else if (!editing.isEmpty() && current.contains(editing)) {
                list.setSelectedValue(editing, false);
            }
        }
        refreshUntouchedFields();
        list.repaint();
        updateOpenClose();
    }

    private void refreshUntouchedFields() {
        if (editing.isEmpty() || state == null) {
            return;
        }
        for (S spec : config.specs().apply(state)) {
            if (config.kind().nameOf(spec).equals(editing)) {
                Map<String, String> stored = config.kind().toAttributes(spec);
                if (stored.equals(filled)) {
                    return;
                }
                fill(merge(read(), filled, stored));
                filled = stored;
                return;
            }
        }
    }

    /** The form after the stored values changed: a field the user left as filled takes the stored value. */
    static Map<String, String> merge(Map<String, String> form, Map<String, String> filled, Map<String, String> stored) {
        Map<String, String> merged = new java.util.HashMap<>(form);
        for (Map.Entry<String, String> e : stored.entrySet()) {
            if (java.util.Objects.equals(form.get(e.getKey()), filled.get(e.getKey()))) {
                merged.put(e.getKey(), e.getValue());
            }
        }
        return merged;
    }

    private void edit(String name) {
        for (S spec : config.specs().apply(state)) {
            if (config.kind().nameOf(spec).equals(name)) {
                editing = name;
                filled = config.kind().toAttributes(spec);
                fill(filled);
                message.setText(" ");
                updateOpenClose();
                return;
            }
        }
    }

    private void add() {
        String name = unique("new " + config.kind().noun());
        editing = "";
        list.clearSelection();
        fill(config.kind().toAttributes(config.kind().template(name)));
        message.setText("New " + config.kind().noun() + "; Save adds it.");
        updateOpenClose();
    }

    private void cloneSelected() {
        S spec = selectedSpec();
        if (spec == null) {
            return;
        }
        String name = unique(config.kind().nameOf(spec) + " copy");
        editing = "";
        list.clearSelection();
        fill(config.kind().toAttributes(config.kind().cloneOf(spec, name)));
        message.setText("Clone of " + config.kind().nameOf(spec) + "; Save adds it.");
        updateOpenClose();
    }

    private void removeSelected() {
        String name = list.getSelectedValue();
        if (name != null) {
            intents.dispatch(config.remove().apply(name));
            editing = "";
        }
    }

    private void save() {
        try {
            S spec = config.kind().fromAttributes(read());
            String name = config.kind().nameOf(spec);
            if (!name.equals(editing) && names.contains(name)) {
                message.setForeground(Theme.DANGER);
                message.setText("A " + config.kind().noun() + " named " + name + " exists; choose another name");
                return;
            }
            Status current = editing.isEmpty() ? Status.CLOSED : config.statuses().apply(state).getOrDefault(editing, Status.CLOSED);
            if (!name.equals(editing) && !editing.isEmpty() && config.kind().noun().equals("session")
                    && current != Status.CLOSED && current != Status.FAILED) {
                // the application refuses this rename; saying "Saved" would leave the form on a name that does not exist
                message.setForeground(Theme.DANGER);
                message.setText("Stop " + editing + " before renaming it");
                return;
            }
            intents.dispatch(config.save().apply(editing, spec));
            filled = config.kind().toAttributes(spec);
            editing = config.kind().nameOf(spec);
            message.setForeground(Theme.MUTED);
            message.setText("Saved " + editing);
        } catch (IllegalArgumentException e) {
            message.setForeground(Theme.DANGER);
            message.setText(e.getMessage());
        }
    }

    private void testForm() {
        try {
            intents.dispatch(config.test().apply(config.kind().fromAttributes(read())));
            message.setForeground(Theme.MUTED);
            message.setText("Testing; the result shows in the status bar");
        } catch (IllegalArgumentException e) {
            message.setForeground(Theme.DANGER);
            message.setText(e.getMessage());
        }
    }

    private void toggleOpen() {
        if (editing.isEmpty()) {
            return;
        }
        Status st = config.statuses().apply(state).getOrDefault(editing, Status.CLOSED);
        intents.dispatch(st.isUsable() ? config.close().apply(editing) : config.open().apply(editing));
    }

    private void assign() {
        if (!editing.isEmpty()) {
            intents.dispatch(config.assign().apply(state, editing));
            message.setText("Assigned " + editing + " to the focused panel");
        }
    }

    private void updateOpenClose() {
        Status st = editing.isEmpty() || state == null ? Status.CLOSED : config.statuses().apply(state).getOrDefault(editing, Status.CLOSED);
        boolean open = st.isUsable();
        boolean session = config.kind().noun().equals("session");
        openClose.setText(open ? (session ? "Stop" : "Disconnect") : (session ? "Start" : "Connect"));
        openClose.setIcon(Icons.get(open ? (session ? "stop" : "unplug") : (session ? "play" : "plug")));
        openClose.setEnabled(!editing.isEmpty());
    }

    private S selectedSpec() {
        String name = list.getSelectedValue();
        if (name == null) {
            return null;
        }
        for (S spec : config.specs().apply(state)) {
            if (config.kind().nameOf(spec).equals(name)) {
                return spec;
            }
        }
        return null;
    }

    private String unique(String base) {
        String name = base;
        for (int i = 2; names.contains(name); i++) {
            name = base + " " + i;
        }
        return name;
    }

    private void fill(Map<String, String> values) {
        for (Attribute a : config.kind().attributes()) {
            JComponent f = fields.get(a.key());
            String v = values.getOrDefault(a.key(), "");
            switch (f) {
                case JCheckBox c -> c.setSelected(Boolean.parseBoolean(v));
                case JComboBox<?> c -> c.setSelectedItem(v);
                case JTextField t -> {
                    if (!t.getText().equals(v)) {
                        t.setText(v); // unchanged fields keep their caret
                    }
                }
                default -> {
                    // no other field kinds
                }
            }
        }
    }

    private Map<String, String> read() {
        Map<String, String> m = new LinkedHashMap<>();
        for (Attribute a : config.kind().attributes()) {
            JComponent f = fields.get(a.key());
            m.put(a.key(), switch (f) {
                case JCheckBox c -> Boolean.toString(c.isSelected());
                case JComboBox<?> c -> String.valueOf(c.getSelectedItem() == null ? "" : c.getSelectedItem());
                case JPasswordField p -> new String(p.getPassword());
                case JTextField t -> t.getText();
                default -> "";
            });
        }
        return m;
    }

    private static JComponent field(Attribute a) {
        return switch (a.kind()) {
            case SECRET -> new JPasswordField(24);
            case BOOLEAN -> new JCheckBox();
            case CHOICE -> {
                JComboBox<String> c = new JComboBox<>(a.choices().toArray(String[]::new));
                c.setEditable(true);
                yield c;
            }
            default -> new JTextField(24);
        };
    }

    private JComponent withChooser(JTextField field, boolean many) {
        JPanel p = new JPanel(new BorderLayout(4, 0));
        p.add(field, BorderLayout.CENTER);
        JButton browse = new JButton("…");
        browse.addActionListener(e -> {
            JFileChooser c = new JFileChooser(field.getText().isBlank() ? System.getProperty("user.home") : field.getText().split(File.pathSeparator)[0]);
            c.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (c.showOpenDialog(dialog) == JFileChooser.APPROVE_OPTION) {
                String path = c.getSelectedFile().getAbsolutePath();
                field.setText(many && !field.getText().isBlank() ? field.getText() + File.pathSeparator + path : path);
            }
        });
        p.add(browse, BorderLayout.EAST);
        return p;
    }

    private static JButton button(String text, String icon, Runnable r) {
        JButton b = new JButton(text, Icons.get(icon));
        b.addActionListener(e -> r.run());
        return b;
    }
}
