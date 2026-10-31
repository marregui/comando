package dev.marregui.comando.ui.shell;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;

import dev.marregui.comando.core.model.FolderRoot;
import dev.marregui.comando.core.model.KitRoot;
import dev.marregui.comando.ui.kit.action.ActionRegistry;
import dev.marregui.comando.ui.kit.action.Command;
import dev.marregui.comando.ui.panels.CommandButton;
import dev.marregui.comando.ui.panels.SessionsSidebar;

/**
 * The window's menu bar, its corner buttons and the commands the sidebar's menus gain, from the registered commands.
 */
final class MenuBuilder {

    private final ComandoUi.Dependencies deps;
    private final ActionRegistry actions;

    MenuBuilder(final ComandoUi.Dependencies deps, final ActionRegistry actions) {
        this.deps = deps;
        this.actions = actions;
    }

    JMenuBar menuBar() {
        return actions.menuBar(Menus.bar(), Menus::tip, id -> null);
    }

    /**
     * The buttons left of the theme's swatch: the shell, the live plots, and the embedded server and the Edge if any.
     */
    List<CommandButton> cornerButtons() {
        final List<CommandButton> buttons = new ArrayList<>();
        for (final List<String> ids : Menus.corner(deps.server() != null, deps.edge() != null)) {
            final List<Command> commands = ids.stream().map(actions::get).toList();
            final ServerToggle toggle = ids.contains("edge.start") ? deps.edge() : ids.contains("server.start") ? deps.server() : null;
            buttons.add(new CommandButton(commands, Menus::tip, toggle == null ? () -> "" : toggle::status));
        }
        return buttons;
    }

    /** A sidebar tab's menu gains the window's commands for that tab, each with its key and enabled as it can run. */
    void tabMenu(final int tab, final JPopupMenu menu) {
        final List<String> ids = switch (tab) {
            case SessionsSidebar.SESSIONS -> List.of("panel.new", "panel.reopen");
            case SessionsSidebar.CONNECTIONS -> Menus.toggles(deps.server() != null, deps.edge() != null);
            case SessionsSidebar.FILES -> List.of("files.settings", "files.jobs");
            default -> List.of();
        };
        for (final String id : ids) {
            final Command c = actions.get(id);
            if (c != null) {
                final JMenuItem item = new JMenuItem(ActionRegistry.action(c));
                item.setEnabled(c.enabled().getAsBoolean());
                menu.add(item);
            }
        }
    }

    /**
     * A Files tab top row's menu (record 118): the brain's row, or a folder inside the brain, gains the brain's backup;
     * the kit's root's row gains the kit's backup and its upgrade (record 119), each enabled as it can run.
     */
    void folderMenu(final FolderRoot folder, final JPopupMenu menu) {
        final KitRoot root = deps.setup().root();
        final Path p = Path.of(folder.path());
        final List<String> ids = root.brain().filter(b -> inside(p, b)).isPresent() ? List.of("kit.backup")
                : root.kit().isPresent() && inside(p, root.dir()) && inside(root.dir(), p) ? List.of("kit.backupKit", "kit.upgrade") : List.of();
        boolean first = true;
        for (final String id : ids) {
            final Command c = actions.get(id);
            if (c == null) {
                continue;
            }
            if (first) {
                menu.addSeparator();
                first = false;
            }
            final JMenuItem item = new JMenuItem(ActionRegistry.action(c));
            item.setEnabled(c.enabled().getAsBoolean());
            menu.add(item);
        }
    }

    /**
     * Whether {@code p} is {@code dir} or inside it, links followed: {@code ~/.claude/MEMORY} may link into the brain.
     */
    static boolean inside(final Path p, final Path dir) {
        Path a = p.toAbsolutePath().normalize();
        Path b = dir.toAbsolutePath().normalize();
        try {
            a = a.toRealPath();
            b = b.toRealPath();
        } catch (IOException | RuntimeException e) {
            // as written: a folder not there yet is compared by its path
        }
        return a.startsWith(b);
    }
}
