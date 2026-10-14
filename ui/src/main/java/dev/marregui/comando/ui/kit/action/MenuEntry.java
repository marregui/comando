package dev.marregui.comando.ui.kit.action;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * The shape of the menu bar, by command id: menus hold commands, separators and submenus. The registry builds the
 * bar from it and names each placed command's path ("Panel ▸ Session") for the palette and the shortcuts list.
 */
public sealed interface MenuEntry {

    /** A command, by its id. */
    record Item(String id) implements MenuEntry {
        public Item {
            Objects.requireNonNull(id, "id");
        }
    }

    /** A line between groups of entries. */
    record Separator() implements MenuEntry {
    }

    /**
     * A menu: a top-level one when in the bar, else a submenu.
     *
     * @param icon the icon of the menu's subject, or null
     */
    record Menu(String title, String icon, List<MenuEntry> entries) implements MenuEntry {
        public Menu {
            Objects.requireNonNull(title, "title");
            entries = List.copyOf(entries);
        }
    }

    MenuEntry SEPARATOR = new Separator();

    /** A menu of {@code entries}: a {@link String} is a command id, {@link #SEPARATOR} a line, a {@link Menu} a submenu. */
    static Menu menu(String title, String icon, Object... entries) {
        return new Menu(title, icon, Arrays.stream(entries).map(e -> e instanceof MenuEntry m ? m : new Item((String) e)).toList());
    }
}
