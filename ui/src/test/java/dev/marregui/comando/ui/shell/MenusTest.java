package dev.marregui.comando.ui.shell;

import dev.marregui.comando.ui.kit.action.MenuEntry;
import dev.marregui.comando.ui.kit.icon.Icons;
import dev.marregui.comando.ui.kit.theme.GroundIcon;
import dev.marregui.comando.ui.kit.theme.NamedTheme;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/// [Menus] declares the menu bar, the corner buttons, their icons, tooltips and the areas of commands in no menu.
/// - The bar is Edit then Help, flat, the palette then the help items.
/// - The corner holds the shell, the plots, and the server and the Edge only when there are.
/// - Every item and button has an icon that exists and a tooltip of at most 80 characters; no tooltip outlives its
///   item.
/// - A command is in a menu or in an area, never both.
/// - Separators sit only between entries.
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

    /// [Menus#bar] is exactly Edit and Help with the listed item ids in order, no submenus, and one separator in Help.
    @Test
    void editThenHelpFlatThePaletteThenTheHelp() {
        List<MenuEntry.Menu> bar = Menus.bar();
        assertThat(bar).extracting(MenuEntry.Menu::title).as("after macOS's application menu").containsExactly("Edit", "Help");
        assertThat(ids(bar)).containsExactly(
                "edit.undo", "edit.redo", "edit.cut", "edit.copy", "edit.paste", "edit.selectAll",
                "view.palette", "help.tutorial", "help.keys", "help.markdown", "help.log", "help.edgeLog", "help.questDbLog", "help.dataRoot");
        assertThat(submenus(bar)).as("flat: the themes are the swatch's").isEmpty();
        assertThat(bar.get(1).entries().stream().filter(MenuEntry.Separator.class::isInstance).count())
                .as("one separator between the palette and the help").isEqualTo(1);
    }

    /// [Menus#corner] groups terminal, plot, server and Edge buttons, the Edge's after the server's, and leaves the
    /// server or the Edge group out when comando has none; [Menus#toggles] lists the same pairs for the Connections
    /// tab's menu.
    @Test
    void theCornerHoldsTheShellThePlotsTheServerAndTheEdgeOnlyWhenThereAreSome() {
        assertThat(Menus.corner(true, true)).containsExactly(List.of("terminal.start", "terminal.stop"), List.of("run.plotStop"),
                List.of("server.start", "server.stop"), List.of("edge.start", "edge.stop"));
        assertThat(Menus.corner(false, false)).as("no embedded server or Edge, no button for them")
                .containsExactly(List.of("terminal.start", "terminal.stop"), List.of("run.plotStop"));
        assertThat(Menus.corner(true, false)).as("no Edge, no button for it")
                .containsExactly(List.of("terminal.start", "terminal.stop"), List.of("run.plotStop"), List.of("server.start", "server.stop"));
        assertThat(Menus.corner(false, true)).as("no embedded server, the Edge's button alone")
                .containsExactly(List.of("terminal.start", "terminal.stop"), List.of("run.plotStop"), List.of("edge.start", "edge.stop"));
        assertThat(Menus.toggles(true, true)).containsExactly("server.start", "server.stop", "edge.start", "edge.stop");
        assertThat(Menus.toggles(false, false)).isEmpty();
        assertThat(Menus.areas()).containsEntry("edge.start", "Connection").containsEntry("edge.stop", "Connection");
    }

    /// Every placed id has an icon whose SVG is a resource and a non-blank tooltip of at most 80 characters, the bar's
    /// titles have no icon, [Menus#TIPS] holds exactly the placed ids, and each named theme's icon is its swatch.
    @Test
    void everyItemAndButtonHasAnIconThatExistsAndATooltip() {
        List<String> placed = new ArrayList<>(ids(Menus.bar()));
        Menus.corner(true, true).forEach(placed::addAll);
        for (String id : placed) {
            assertThat(Menus.icon(id)).as(id + " has an icon").isNotNull();
            assertThat(MenusTest.class.getResource("/icons/" + Menus.icon(id) + ".svg")).as(id + "'s icon is a resource").isNotNull();
            assertThat(Menus.tip(id)).as(id + " has a tooltip").isNotBlank().hasSizeLessThanOrEqualTo(80);
        }
        assertThat(Menus.bar()).as("the bar's own titles are words, as macOS draws them").allMatch(m -> m.icon() == null);
        assertThat(Menus.TIPS.keySet()).as("no tooltip for an item or button that is gone").isEqualTo(new HashSet<>(placed));
        // the window asks for the areas once the theme files are read, which paints the themes' swatches
        Menus.areas();
        for (NamedTheme t : NamedTheme.values()) {
            assertThat(Icons.get(Menus.icon(t.commandId()))).as(t + "'s icon in the palette is its swatch").isInstanceOf(GroundIcon.class);
        }
    }

    /// No id is placed twice, [Menus#areas] holds no menu item, every corner command has an area, and sample commands
    /// map to their areas.
    @Test
    void aCommandIsInAMenuOrInAnAreaNeverBoth() {
        List<String> placed = ids(Menus.bar());
        assertThat(new HashSet<>(placed)).hasSameSizeAs(placed);
        assertThat(Menus.areas().keySet()).doesNotContainAnyElementsOf(placed);
        assertThat(Menus.areas()).as("the corner's commands are in no menu: each has its area")
                .containsKeys(Menus.corner(true, true).stream().flatMap(List::stream).toArray(String[]::new));
        assertThat(Menus.areas()).containsEntry("run", "Run").containsEntry("session.model.opus", "Session ▸ Model")
                .containsEntry("panel.9", "Panels").containsEntry("theme.slate_silver", "Theme").containsEntry("view.results.alt", "Window").doesNotContainKey("view.lastPrompt");
    }

    /// Every menu and submenu is non-empty, starts and ends with an entry, and has no two separators in a row.
    @Test
    void separatorsOnlyBetweenEntries() {
        Menus.bar().forEach(top -> walk(top, m -> {
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
