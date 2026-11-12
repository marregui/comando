package dev.marregui.comando.ui.shell;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.ui.kit.theme.NamedTheme;
import dev.marregui.comando.ui.panels.SessionsSidebar;

import static org.assertj.core.api.Assertions.assertThat;

/// [WindowLayout] reads and writes the window's layout settings, including keys from older layouts.
/// - The sidebar tab in front is kept by its name, and read from the older layouts' indexes until it is, the newest
///   key winning.
/// - A tab that is gone, or a name no tab has, reads as Sessions.
/// - Each panel's lower divider is kept, and a save replaces the ones before.
/// - The Search and Changes views on the right side are kept in their order; other types are left out.
class WindowLayoutTest {

    private final Settings prefs = Settings.inMemory();

    /// With only the oldest key, indexes of Sessions, Panels, Connections, Files map to the current tabs, Panels
    /// reading as Sessions; nothing saved reads as Sessions.
    @Test
    void theTabInFrontBeforeTheOneRowStaysInFront() {
        final WindowLayout layout = new WindowLayout(prefs);
        assertThat(layout.sidebarTab()).as("nothing saved").isEqualTo(SessionsSidebar.SESSIONS);
        // Sessions, Panels, Connections, Files: the Panels tab reads as Sessions, which lists the panels without one
        final int[][] oldToNew = {{0, SessionsSidebar.SESSIONS}, {1, SessionsSidebar.SESSIONS}, {2, SessionsSidebar.CONNECTIONS},
                {3, SessionsSidebar.KIT}};
        for (final int[] pair : oldToNew) {
            prefs.putInt("sidebar.tab", pair[0]);
            assertThat(layout.sidebarTab()).as("old index " + pair[0]).isEqualTo(pair[1]);
        }
    }

    /// The middle key, from the layout with a Panels tab (record 70), wins over the oldest and maps Panels to Sessions;
    /// the index key wins over both.
    @Test
    void theTabInFrontWithAPanelsTabStaysInFront() {
        final WindowLayout layout = new WindowLayout(prefs);
        prefs.putInt("sidebar.tab", 2);
        // Sessions, Connections, Panels, Files (record 70), read over the older key
        final int[][] oldToNew = {{0, SessionsSidebar.SESSIONS}, {1, SessionsSidebar.CONNECTIONS}, {2, SessionsSidebar.SESSIONS},
                {3, SessionsSidebar.KIT}};
        for (final int[] pair : oldToNew) {
            prefs.putInt("sidebar.front", pair[0]);
            assertThat(layout.sidebarTab()).as("index " + pair[0] + " with a Panels tab").isEqualTo(pair[1]);
        }
        prefs.putInt("sidebar.selected", 2);
        assertThat(layout.sidebarTab()).as("the index key wins").isEqualTo(SessionsSidebar.KIT);
    }

    /// Without a name kept, the index key is read in the order before the Logbook moved ahead of Instances (Sessions,
    /// Connections, Files, Instances, Logbook), each index to the same tab in today's order; an index no tab had reads
    /// as Sessions.
    @Test
    void theIndexKeptBeforeTheLogbookMovedReadsAsTheSameTab() {
        final WindowLayout layout = new WindowLayout(prefs);
        final int[][] oldToNew = {{0, SessionsSidebar.SESSIONS}, {1, SessionsSidebar.CONNECTIONS},
                {2, SessionsSidebar.KIT}, {3, SessionsSidebar.INSTANCES}, {4, SessionsSidebar.LOGBOOK},
                {7, SessionsSidebar.SESSIONS}};
        for (final int[] pair : oldToNew) {
            prefs.putInt("sidebar.selected", pair[0]);
            assertThat(layout.sidebarTab()).as("index " + pair[0]).isEqualTo(pair[1]);
        }
    }

    /// Each tab's name, as a save writes it, reads back as that tab over every older key; a name no tab has reads as
    /// Sessions rather than falling back to an index.
    @Test
    void theTabInFrontIsKeptByItsNameOverTheOlderKeys() {
        final WindowLayout layout = new WindowLayout(prefs);
        prefs.putInt("sidebar.tab", 3);
        prefs.putInt("sidebar.front", 3);
        prefs.putInt("sidebar.selected", 2);
        final Map<Integer, String> names = Map.of(SessionsSidebar.SESSIONS, "sessions",
                SessionsSidebar.CONNECTIONS, "connections", SessionsSidebar.KIT, "kit", SessionsSidebar.BRAIN, "brain",
                SessionsSidebar.SCRATCHPAD, "scratchpad", SessionsSidebar.LOGBOOK, "logbook",
                SessionsSidebar.INSTANCES, "instances");
        names.forEach((tab, name) -> {
            assertThat(WindowLayout.tabName(tab)).isEqualTo(name);
            prefs.put("sidebar.shown", WindowLayout.tabName(tab));
            assertThat(layout.sidebarTab()).as(name).isEqualTo(tab);
        });
        prefs.put("sidebar.shown", "files");
        assertThat(layout.sidebarTab()).as("the Files tab the folder tabs replaced reads as Kit, in its place")
                .isEqualTo(SessionsSidebar.KIT);
        prefs.put("sidebar.shown", "panels");
        assertThat(layout.sidebarTab()).as("a name no tab has, not the index kept beside it")
                .isEqualTo(SessionsSidebar.SESSIONS);
    }

    /// Dividers saved per panel are read back by a new layout, a later save without a panel drops its divider, and the
    /// keys differ from the window's own divider.
    @Test
    void eachPanelsLowerDividerIsKeptAndASaveReplacesTheOnesBefore() {
        final WindowLayout layout = new WindowLayout(prefs);
        assertThat(layout.panelDividers()).as("nothing saved").isEmpty();
        final PanelId a = new PanelId("a");
        final PanelId b = new PanelId("b");
        layout.panelDividers(Map.of(a, 300, b, 520));
        assertThat(new WindowLayout(prefs).panelDividers()).containsExactlyInAnyOrderEntriesOf(Map.of(a, 300, b, 520));
        layout.panelDividers(Map.of(b, 410));
        assertThat(layout.panelDividers()).as("a panel gone takes its divider with it").containsExactlyEntriesOf(Map.of(b, 410));
        assertThat(prefs.values()).as("the window's own divider is another key").doesNotContainKey("divider.lower.panel.a");
    }

    /// A theme chosen is kept by its id, read back before the ground and tint; an id no theme offered has, as a theme
    /// file removed since, falls back to the ground and tint kept, and an old ground no theme has to the tint's theme.
    @Test
    void aThemeIsKeptByItsIdAndFallsBackToItsGroundAndTint() {
        final WindowLayout layout = new WindowLayout(prefs);
        layout.theme(NamedTheme.SLATE_SILVER);
        assertThat(prefs.get("theme.id", null)).isEqualTo("slate_silver");
        assertThat(layout.theme()).isEqualTo(NamedTheme.SLATE_SILVER);
        prefs.put("theme.id", "removed_since");
        prefs.put("theme.ground", "MIDNIGHT");
        prefs.put("theme.tint", "ELECTRIC_BLUE");
        assertThat(layout.theme()).as("an id no theme has").isEqualTo(NamedTheme.MIDNIGHT_BLUE);
        prefs.put("theme.ground", "ONYX");
        prefs.put("theme.tint", "SILVER");
        assertThat(layout.theme()).as("a ground no theme has, then the tint").isEqualTo(NamedTheme.SLATE_SILVER);
    }

    /// The types of the views on the right side are read back by a new layout in their order; nothing saved reads as
    /// none, and a type of a view not kept there, or one repeated, is left out.
    @Test
    void theSearchAndChangesViewsOnTheRightSideAreKeptInTheirOrder() {
        final WindowLayout layout = new WindowLayout(prefs);
        assertThat(layout.sideViews()).as("nothing saved").isEmpty();
        layout.sideViews(List.of("changes", "search"));
        assertThat(new WindowLayout(prefs).sideViews()).containsExactly("changes", "search");
        prefs.put("sidePane.views", "table, search,later-type,search");
        assertThat(layout.sideViews()).as("only the kept types, once each").containsExactly("search");
        layout.sideViews(List.of());
        assertThat(layout.sideViews()).as("none left on the right side").isEmpty();
    }
}
