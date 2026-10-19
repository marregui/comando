package dev.marregui.comando.ui.shell;

import dev.marregui.comando.ui.panels.SessionsSidebar;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

import static org.assertj.core.api.Assertions.assertThat;

class WindowLayoutTest {

    private final Preferences prefs = Preferences.userRoot().node("comando-test-" + UUID.randomUUID());

    @AfterEach
    void removeNode() throws BackingStoreException {
        prefs.removeNode();
    }

    @Test
    void theTabInFrontBeforeTheOneRowStaysInFront() {
        WindowLayout layout = new WindowLayout(prefs);
        assertThat(layout.sidebarTab()).as("nothing saved").isEqualTo(SessionsSidebar.SESSIONS);
        int[][] oldToNew = {{0, SessionsSidebar.SESSIONS}, {1, SessionsSidebar.PANELS}, {2, SessionsSidebar.CONNECTIONS},
                {3, SessionsSidebar.FILES}};
        for (int[] pair : oldToNew) {
            prefs.putInt("sidebar.tab", pair[0]);
            assertThat(layout.sidebarTab()).as("old index " + pair[0]).isEqualTo(pair[1]);
        }
        prefs.putInt("sidebar.front", SessionsSidebar.CONNECTIONS);
        assertThat(layout.sidebarTab()).as("the new key wins").isEqualTo(SessionsSidebar.CONNECTIONS);
    }
}
