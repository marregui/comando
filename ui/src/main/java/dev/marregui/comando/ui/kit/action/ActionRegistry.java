package dev.marregui.comando.ui.kit.action;

import dev.marregui.comando.ui.kit.icon.Icons;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.KeyStroke;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;
import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The registry of commands. Widgets register; the shell builds menus, bindings and the palette from it. */
public final class ActionRegistry {

    private final Map<String, Command> commands = new LinkedHashMap<>();
    /** Every menu item built from a command, so {@link #refresh} can keep their enabled state current. */
    private final List<JMenuItem> built = new ArrayList<>();

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

    /**
     * The command {@code key} runs: the first enabled one bound to it, else the first bound to it (disabled, so the
     * key goes on to the focused component), or null. Two commands may share a key when at most one is enabled at a
     * time, as ⌘W closes the view that has the keyboard, else the panel.
     */
    public Command forKey(KeyStroke key) {
        Command first = null;
        for (Command c : commands.values()) {
            if (key.equals(c.key())) {
                if (c.enabled().getAsBoolean()) {
                    return c;
                }
                if (first == null) {
                    first = c;
                }
            }
        }
        return first;
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

    /**
     * The commands of {@code group} as a menu, in registration order. Items grey out, as the palette hides them,
     * when their command cannot run now: each item asks its command as the menu opens.
     */
    public JMenu menu(String group, String title) {
        JMenu menu = new JMenu(title);
        List<JMenuItem> items = new ArrayList<>();
        for (Command c : commands.values()) {
            if (c.group().equals(group)) {
                JMenuItem item = new JMenuItem(action(c));
                item.setAccelerator(c.key());
                item.setEnabled(c.enabled().getAsBoolean());
                items.add(item);
                built.add(item);
                menu.add(item);
            }
        }
        menu.addMenuListener(new MenuListener() {
            @Override
            public void menuSelected(MenuEvent e) {
                for (JMenuItem item : items) {
                    item.setEnabled(item.getAction().isEnabled());
                }
            }

            @Override
            public void menuDeselected(MenuEvent e) {
                // nothing to undo
            }

            @Override
            public void menuCanceled(MenuEvent e) {
                // nothing to undo
            }
        });
        return menu;
    }

    /**
     * Sets every menu item's enabled state from its command now. The macOS menu bar answers a key equivalent
     * from the state it last saw, so the shell calls this when the focus or the state changes: a key whose
     * command cannot run then reaches the focused component instead of a disabled item.
     */
    public void refresh() {
        for (JMenuItem item : built) {
            boolean enabled = item.getAction().isEnabled();
            if (item.isEnabled() != enabled) {
                item.setEnabled(enabled);
            }
        }
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
