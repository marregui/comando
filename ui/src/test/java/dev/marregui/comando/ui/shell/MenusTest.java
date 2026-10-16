package dev.marregui.comando.ui.shell;

import dev.marregui.comando.ui.kit.action.MenuEntry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

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

    @Test
    void fiveMenusInTheirOrderEachCommandOnce() {
        List<MenuEntry.Menu> bar = Menus.bar(true);
        assertThat(bar).extracting(MenuEntry.Menu::title).containsExactly("File", "Edit", "Panel", "View", "Help");
        List<String> ids = ids(bar);
        assertThat(new HashSet<>(ids)).hasSameSizeAs(ids);
        assertThat(ids).contains("server.start", "server.stop", "session.model.fable", "session.effort.max");
        assertThat(ids(Menus.bar(false))).as("no embedded server, no submenu for it").doesNotContain("server.start");
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

    @Test
    void eachIconMeansOneThing() {
        // items: an icon where the same action is a button with it, and the Help items' own
        Map<String, Set<String>> byIcon = Menus.ICONS.entrySet().stream()
                .collect(Collectors.groupingBy(Map.Entry::getValue, Collectors.mapping(Map.Entry::getKey, Collectors.toSet())));
        assertThat(byIcon).containsOnly(
                Map.entry("play", Set.of("run")),
                Map.entry("stop", Set.of("run.stop")),
                Map.entry("add", Set.of("panel.new")),
                Map.entry("close", Set.of("file.close")),
                Map.entry("plug", Set.of("session.toggle", "connection.toggle")),
                Map.entry("tutorial", Set.of("help.tutorial")),
                Map.entry("keyboard", Set.of("help.keys")),
                Map.entry("log", Set.of("help.log")),
                Map.entry("comando-folder", Set.of("help.dataRoot")));
        assertThat(ids(Menus.bar(true))).containsAll(Menus.ICONS.keySet());

        // menus: the Panel menu's submenus carry their subject's icon, and no other menu does
        List<String> withIcons = new ArrayList<>();
        Menus.bar(true).forEach(top -> walk(top, m -> {
            if (m.icon() != null) {
                withIcons.add(m.title() + "=" + m.icon());
            }
        }));
        assertThat(withIcons).containsExactly("Session=claude", "Connection=database", "Result=table", "Tools=tool");
        assertThat(Set.copyOf(Menus.ICONS.values())).doesNotContainAnyElementsOf(Set.of("claude", "database", "table", "tool"));
    }
}
