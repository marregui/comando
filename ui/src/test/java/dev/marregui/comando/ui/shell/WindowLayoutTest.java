package dev.marregui.comando.ui.shell;

import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.ui.panels.SessionsSidebar;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// [WindowLayout] reads and writes the window's layout settings, including keys from older layouts.
/// - The sidebar tab in front is kept across the older layouts' indexes, the newest key winning.
/// - A tab that is gone reads as Sessions.
/// - Each panel's lower divider is kept, and a save replaces the ones before.
class WindowLayoutTest {

    private final Settings prefs = Settings.inMemory();

    /// With only the oldest key, indexes of Sessions, Panels, Connections, Files map to the current tabs, Panels reading as
    /// Sessions; nothing saved reads as Sessions.
    @Test
    void theTabInFrontBeforeTheOneRowStaysInFront() {
        WindowLayout layout = new WindowLayout(prefs);
        assertThat(layout.sidebarTab()).as("nothing saved").isEqualTo(SessionsSidebar.SESSIONS);
        // Sessions, Panels, Connections, Files: the Panels tab reads as Sessions, which lists the panels without one
        int[][] oldToNew = {{0, SessionsSidebar.SESSIONS}, {1, SessionsSidebar.SESSIONS}, {2, SessionsSidebar.CONNECTIONS},
                {3, SessionsSidebar.FILES}};
        for (int[] pair : oldToNew) {
            prefs.putInt("sidebar.tab", pair[0]);
            assertThat(layout.sidebarTab()).as("old index " + pair[0]).isEqualTo(pair[1]);
        }
    }

    /// The middle key, from the layout with a Panels tab (record 70), wins over the oldest and maps Panels to Sessions; the
    /// newest key wins over both, and an index that no longer exists reads as Sessions.
    @Test
    void theTabInFrontWithAPanelsTabStaysInFront() {
        WindowLayout layout = new WindowLayout(prefs);
        prefs.putInt("sidebar.tab", 2);
        // Sessions, Connections, Panels, Files (record 70), read over the older key
        int[][] oldToNew = {{0, SessionsSidebar.SESSIONS}, {1, SessionsSidebar.CONNECTIONS}, {2, SessionsSidebar.SESSIONS},
                {3, SessionsSidebar.FILES}};
        for (int[] pair : oldToNew) {
            prefs.putInt("sidebar.front", pair[0]);
            assertThat(layout.sidebarTab()).as("index " + pair[0] + " with a Panels tab").isEqualTo(pair[1]);
        }
        prefs.putInt("sidebar.selected", SessionsSidebar.FILES);
        assertThat(layout.sidebarTab()).as("the newest key wins").isEqualTo(SessionsSidebar.FILES);
        prefs.putInt("sidebar.selected", 7);
        assertThat(layout.sidebarTab()).as("a tab that is gone reads as Sessions").isEqualTo(SessionsSidebar.SESSIONS);
    }

    /// Dividers saved per panel are read back by a new layout, a later save without a panel drops its divider, and the keys
    /// differ from the window's own divider.
    @Test
    void eachPanelsLowerDividerIsKeptAndASaveReplacesTheOnesBefore() {
        WindowLayout layout = new WindowLayout(prefs);
        assertThat(layout.panelDividers()).as("nothing saved").isEmpty();
        PanelId a = new PanelId("a");
        PanelId b = new PanelId("b");
        layout.panelDividers(Map.of(a, 300, b, 520));
        assertThat(new WindowLayout(prefs).panelDividers()).containsExactlyInAnyOrderEntriesOf(Map.of(a, 300, b, 520));
        layout.panelDividers(Map.of(b, 410));
        assertThat(layout.panelDividers()).as("a panel gone takes its divider with it").containsExactlyEntriesOf(Map.of(b, 410));
        assertThat(prefs.values()).as("the window's own divider is another key").doesNotContainKey("divider.lower.panel.a");
    }
}
