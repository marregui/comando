package dev.marregui.comando.ui.shell;

import dev.marregui.comando.ui.kit.action.MenuEntry;
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
    void threeMenusHoldingOnlyWhatHasNoKeyOrControlAndWhatMacOsExpects() {
        List<MenuEntry.Menu> bar = Menus.bar(true);
        assertThat(bar).extracting(MenuEntry.Menu::title).containsExactly("Files", "Edit", "Tools");
        assertThat(ids(bar)).containsExactly(
                "panel.openSpec", "files.settings", "view.export",
                "edit.undo", "edit.redo", "edit.cut", "edit.copy", "edit.paste", "edit.selectAll",
                // a menu's own items, then its submenus'
                "view.palette", "terminal.restart", "run.plotStop", "server.start", "server.stop",
                "help.tutorial", "help.keys", "help.markdown", "help.kit", "help.log", "help.dataRoot");
        assertThat(submenus(bar)).extracting(MenuEntry.Menu::title).containsExactly("Embedded QuestDB", "Help");
        assertThat(ids(Menus.bar(false))).as("no embedded server, no submenu for it").doesNotContain("server.start", "server.stop");
        assertThat(submenus(Menus.bar(false))).extracting(MenuEntry.Menu::title).containsExactly("Help");
    }

    @Test
    void everyItemAndSubmenuHasAnIconThatExistsAndATooltip() {
        List<MenuEntry.Menu> bar = Menus.bar(true);
        for (String id : ids(bar)) {
            assertThat(Menus.icon(id)).as(id + " has an icon").isNotNull();
            assertThat(MenusTest.class.getResource("/icons/" + Menus.icon(id) + ".svg")).as(id + "'s icon is a resource").isNotNull();
            assertThat(Menus.tip(id)).as(id + " has a tooltip").isNotBlank();
        }
        for (MenuEntry.Menu sub : submenus(bar)) {
            assertThat(sub.icon()).as(sub.title() + " has an icon").isNotNull();
            assertThat(MenusTest.class.getResource("/icons/" + sub.icon() + ".svg")).isNotNull();
            assertThat(Menus.MENU_TIPS.get(sub.title())).as(sub.title() + " has a tooltip").isNotBlank();
        }
        assertThat(bar).as("the bar's own titles are words, as macOS draws them").allMatch(m -> m.icon() == null);
        assertThat(Menus.TIPS.keySet()).as("no tooltip for an item that is gone").isEqualTo(new HashSet<>(ids(bar)));
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
