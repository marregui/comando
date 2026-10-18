package dev.marregui.comando.ui.kit.action;

import dev.marregui.comando.ui.kit.icon.Icons;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.KeyStroke;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;
import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** The registry of commands. Widgets register; the shell builds menus, bindings and the palette from it. */
public final class ActionRegistry {

    private final Map<String, Command> commands = new LinkedHashMap<>();
    /** Every menu item built from a command, so {@link #refresh} can keep their enabled state current. */
    private final List<JMenuItem> built = new ArrayList<>();
    /** The ids of the commands the menu bar holds, whose keys are their items' accelerators. */
    private final Set<String> inMenus = new HashSet<>();

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
     * menu get their key as the menu item's accelerator; binding both would run them twice. Call it after
     * {@link #menuBar}, which says which commands are in a menu.
     */
    public void bind(JComponent root) {
        for (Command c : commands.values()) {
            if (c.key() != null && !inMenus.contains(c.id())) {
                root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(c.key(), c.id());
                root.getActionMap().put(c.id(), action(c));
            }
        }
    }

    /**
     * The menu bar of {@code menus}. Each command it places is named by its path ("Tools ▸ Help") for the palette and
     * the shortcuts list, and gets its key as its item's accelerator; {@link #bind} binds the others. Items grey out,
     * as the palette hides them, when their command cannot run now: each menu asks its items as it opens. An item's
     * tooltip is {@code tips} of its command's id, a submenu's {@code menuTips} of its title; null for none.
     *
     * @throws IllegalArgumentException when an entry names no registered command, or a command is placed twice
     */
    public JMenuBar menuBar(List<MenuEntry.Menu> menus, Function<String, String> tips, Function<String, String> menuTips) {
        JMenuBar bar = new JMenuBar();
        Set<String> placed = new HashSet<>();
        for (MenuEntry.Menu m : menus) {
            bar.add(build(m, m.title(), placed, tips, menuTips));
        }
        return bar;
    }

    /** The menu bar of {@code menus}, without tooltips. */
    public JMenuBar menuBar(List<MenuEntry.Menu> menus) {
        return menuBar(menus, id -> null, title -> null);
    }

    /**
     * Names where each command in no menu belongs ("Session"), for the palette and the shortcuts list. Call it after
     * {@link #menuBar}.
     *
     * @throws IllegalStateException when a command is in no menu and {@code areas} names none for it
     */
    public void areas(Map<String, String> areas) {
        List<String> nowhere = new ArrayList<>();
        for (Command c : List.copyOf(commands.values())) {
            if (!inMenus.contains(c.id())) {
                String area = areas.get(c.id());
                if (area == null) {
                    nowhere.add(c.id());
                } else {
                    commands.put(c.id(), c.withGroup(area));
                }
            }
        }
        if (!nowhere.isEmpty()) {
            throw new IllegalStateException("commands in no menu and no area: " + nowhere);
        }
    }

    private JMenu build(MenuEntry.Menu spec, String path, Set<String> placed, Function<String, String> tips,
                        Function<String, String> menuTips) {
        JMenu menu = new JMenu(spec.title());
        if (spec.icon() != null) {
            menu.setIcon(Icons.get(spec.icon()));
        }
        menu.setToolTipText(menuTips.apply(spec.title()));
        List<JMenuItem> items = new ArrayList<>();
        for (MenuEntry e : spec.entries()) {
            switch (e) {
                case MenuEntry.Separator s -> menu.addSeparator();
                case MenuEntry.Menu sub -> menu.add(build(sub, path + " ▸ " + sub.title(), placed, tips, menuTips));
                case MenuEntry.Item i -> {
                    Command c = commands.get(i.id());
                    if (c == null) {
                        throw new IllegalArgumentException("the menu names no command: " + i.id());
                    }
                    if (!placed.add(i.id())) {
                        throw new IllegalArgumentException("command placed twice in the menus: " + i.id());
                    }
                    inMenus.add(i.id());
                    c = c.withGroup(path);
                    commands.put(c.id(), c);
                    JMenuItem item = new JMenuItem(action(c));
                    item.setAccelerator(c.key());
                    item.setToolTipText(tips.apply(c.id()));
                    item.setEnabled(c.enabled().getAsBoolean());
                    items.add(item);
                    built.add(item);
                    menu.add(item);
                }
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
