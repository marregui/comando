package dev.marregui.comando.ui.shell;

import dev.marregui.comando.ui.kit.action.MenuEntry;
import dev.marregui.comando.ui.kit.icon.Icons;
import dev.marregui.comando.ui.kit.theme.TintIcon;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class MenusTest {

    private static void walk(MenuEntry.Menu m, Consumer<MenuEntry.Menu> visit) {
        visit.accept(m);
        for (MenuEntry e : m.entries()) {
            if (e instanceof MenuEntry.Menu sub) {
                walk(sub, visit);
            }
        }
    }

    private static List<String> ids(List<MenuEntry.Menu> bar) {
        List<String> ids = new ArrayList<>();
        bar.forEach(m -> walk(m, x -> x.entries().stream().filter(MenuEntry.Item.class::isInstance)
                .forEach(i -> ids.add(((MenuEntry.Item) i).id()))));
        return ids;
    }

    private static List<MenuEntry.Menu> submenus(List<MenuEntry.Menu> bar) {
        List<MenuEntry.Menu> subs = new ArrayList<>();
        bar.forEach(top -> walk(top, m -> {
            if (m != top) {
                subs.add(m);
            }
        }));
        return subs;
    }

    @Test
    void editThenActionsInGroupsTheTintsItsOneSubmenuHelpAmongThem() {
        List<MenuEntry.Menu> bar = Menus.bar(true);
        assertThat(bar).extracting(MenuEntry.Menu::title).as("after macOS's application menu").containsExactly("Edit", "Actions");
        assertThat(ids(bar)).containsExactly(
                "edit.undo", "edit.redo", "edit.cut", "edit.copy", "edit.paste", "edit.selectAll",
                "view.palette", "terminal.restart", "run.plotStop", "server.start", "server.stop",
                "help.tutorial", "help.keys", "help.markdown", "help.log", "help.dataRoot",
                "tint.treasure", "tint.yellow", "tint.champagne", "tint.silver", "tint.tropical_forest", "tint.electric_blue");
        assertThat(submenus(bar)).extracting(MenuEntry.Menu::title).as("flat but for the tints").containsExactly("Tint");
        assertThat(bar.get(1).entries().get(1)).as("the tints after the palette").isInstanceOf(MenuEntry.Menu.class);
        assertThat(bar.get(1).entries().stream().filter(MenuEntry.Separator.class::isInstance).count())
                .as("separators between the palette and tints, the shell and plots, the server, help").isEqualTo(3);
        assertThat(ids(Menus.bar(false))).as("no embedded server, no items for it").doesNotContain("server.start", "server.stop");
    }

    @Test
    void everyItemAndSubmenuHasAnIconThatExistsAndATooltip() {
        List<MenuEntry.Menu> bar = Menus.bar(true);
        for (String id : ids(bar)) {
            assertThat(Menus.icon(id)).as(id + " has an icon").isNotNull();
            if (id.startsWith("tint.")) {
                assertThat(Icons.get(Menus.icon(id))).as(id + "'s icon is its swatch").isInstanceOf(TintIcon.class);
            } else {
                assertThat(MenusTest.class.getResource("/icons/" + Menus.icon(id) + ".svg")).as(id + "'s icon is a resource").isNotNull();
            }
            assertThat(Menus.tip(id)).as(id + " has a tooltip").isNotBlank().hasSizeLessThanOrEqualTo(80);
        }
        for (MenuEntry.Menu sub : submenus(bar)) {
            assertThat(sub.icon()).as(sub.title() + " has an icon").isNotNull();
            assertThat(Icons.get(sub.icon())).as("the chosen tint's swatch").isInstanceOf(TintIcon.class);
            assertThat(Menus.MENU_TIPS.get(sub.title())).as(sub.title() + " has a tooltip").isNotBlank();
        }
        assertThat(bar).as("the bar's own titles are words, as macOS draws them").allMatch(m -> m.icon() == null);
        assertThat(Menus.TIPS.keySet()).as("no tooltip for an item that is gone")
                .isEqualTo(new HashSet<>(ids(bar).stream().filter(id -> !id.startsWith("tint.")).toList()));
    }

    @Test
    void aCommandIsInAMenuOrInAnAreaNeverBoth() {
        List<String> placed = ids(Menus.bar(true));
        assertThat(new HashSet<>(placed)).hasSameSizeAs(placed);
        assertThat(Menus.AREAS.keySet()).doesNotContainAnyElementsOf(placed);
        assertThat(Menus.AREAS).containsEntry("run", "Run").containsEntry("session.model.opus", "Session ▸ Model")
                .containsEntry("panel.9", "Panels").containsEntry("view.results.alt", "Window").doesNotContainKey("view.lastPrompt");
    }

    @Test
    void separatorsOnlyBetweenEntries() {
        Menus.bar(true).forEach(top -> walk(top, m -> {
            List<MenuEntry> e = m.entries();
            assertThat(e).as(m.title()).isNotEmpty();
            assertThat(e.getFirst()).as(m.title() + " starts with an entry").isNotInstanceOf(MenuEntry.Separator.class);
            assertThat(e.getLast()).as(m.title() + " ends with an entry").isNotInstanceOf(MenuEntry.Separator.class);
            for (int i = 1; i < e.size(); i++) {
                assertThat(e.get(i) instanceof MenuEntry.Separator && e.get(i - 1) instanceof MenuEntry.Separator)
                        .as(m.title() + " has no double line").isFalse();
            }
        }));
    }
}
