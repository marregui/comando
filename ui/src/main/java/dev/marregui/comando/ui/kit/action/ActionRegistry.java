package dev.marregui.comando.ui.kit.action;

import dev.marregui.comando.ui.kit.icon.Icons;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The registry of commands. Widgets register; the shell builds menus, bindings and the palette from it. */
public final class ActionRegistry {

    private final Map<String, Command> commands = new LinkedHashMap<>();

    public void register(Command command) {
        if (commands.putIfAbsent(command.id(), command) != null) {
            throw new IllegalArgumentException("command registered twice: " + command.id());
        }
    }

    public List<Command> all() {
        return List.copyOf(commands.values());
    }

    public Command get(String id) {
        return commands.get(id);
    }

    public void run(String id) {
        Command c = commands.get(id);
        if (c != null && c.enabled().getAsBoolean()) {
            c.run().run();
        }
    }

    /**
     * Binds the keys of commands that are in no menu in {@code root}'s window-wide input map. Commands in a
     * menu get their key as the menu item's accelerator; binding both would run them twice.
     */
    public void bind(JComponent root) {
        for (Command c : commands.values()) {
            if (c.key() != null && c.group().isEmpty()) {
                root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(c.key(), c.id());
                root.getActionMap().put(c.id(), action(c));
            }
        }
    }

    /** The commands of {@code group} as a menu, in registration order. */
    public JMenu menu(String group, String title) {
        JMenu menu = new JMenu(title);
        for (Command c : commands.values()) {
            if (c.group().equals(group)) {
                JMenuItem item = new JMenuItem(action(c));
                item.setAccelerator(c.key());
                menu.add(item);
            }
        }
        return menu;
    }

    public List<String> groups() {
        List<String> groups = new ArrayList<>();
        for (Command c : commands.values()) {
            if (!c.group().isEmpty() && !groups.contains(c.group())) {
                groups.add(c.group());
            }
        }
        return groups;
    }

    public static Action action(Command c) {
        AbstractAction a = new AbstractAction(c.label(), c.icon() == null ? null : Icons.get(c.icon())) {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (c.enabled().getAsBoolean()) {
                    c.run().run();
                }
            }

            @Override
            public boolean isEnabled() {
                return c.enabled().getAsBoolean();
            }
        };
        a.putValue(Action.ACCELERATOR_KEY, c.key());
        return a;
    }
}
