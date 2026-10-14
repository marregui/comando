package dev.marregui.comando.ui.panels;

import org.junit.jupiter.api.Test;

import javax.swing.AbstractButton;
import java.awt.Component;
import java.awt.Container;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class SessionsSidebarTest {

    private static final SessionsSidebar.Dialogs NO_DIALOGS = new SessionsSidebar.Dialogs() {
        @Override
        public void sessions(String select) {
        }

        @Override
        public void importSessions() {
        }

        @Override
        public void connections(String select) {
        }

        @Override
        public void exploreSchema(String connection) {
        }
    };

    @Test
    void hiddenTheSidebarCollapsesToARailWhoseButtonTogglesItBack() {
        AtomicInteger toggles = new AtomicInteger();
        SessionsSidebar sidebar = new SessionsSidebar(intent -> {
        }, NO_DIALOGS, toggles::incrementAndGet);
        AbstractButton hide = button(sidebar.component(), "Hide the sidebar (⌘B)");
        AbstractButton show = button(sidebar.component(), "Show the sidebar (⌘B)");
        assertThat(sidebar.isShown()).isTrue();
        assertThat(shows(hide, sidebar.component())).isTrue();
        assertThat(shows(show, sidebar.component())).as("no rail beside the lists").isFalse();

        hide.doClick();
        assertThat(toggles).hasValue(1);

        sidebar.show(false);
        assertThat(sidebar.isShown()).isFalse();
        assertThat(shows(hide, sidebar.component())).isFalse();
        assertThat(shows(show, sidebar.component())).isTrue();
        // the test runs without FlatLaf, whose toolbar buttons are narrower than Metal's
        assertThat(sidebar.railWidth()).as("a rail, not a column").isPositive().isLessThan(64);

        show.doClick();
        assertThat(toggles).hasValue(2);

        sidebar.show(true);
        assertThat(shows(hide, sidebar.component())).isTrue();
        assertThat(shows(show, sidebar.component())).isFalse();
    }

    /** Whether {@code c} and every ancestor up to {@code root} are visible. */
    private static boolean shows(Component c, Component root) {
        for (Component x = c; x != null; x = x.getParent()) {
            if (!x.isVisible()) {
                return false;
            }
            if (x == root) {
                return true;
            }
        }
        return false;
    }

    private static AbstractButton button(Component root, String name) {
        AbstractButton found = find(root, name);
        assertThat(found).as(name).isNotNull();
        return found;
    }

    private static AbstractButton find(Component root, String name) {
        if (root instanceof AbstractButton b && name.equals(b.getAccessibleContext().getAccessibleName())) {
            return b;
        }
        if (root instanceof Container c) {
            for (Component child : c.getComponents()) {
                AbstractButton b = find(child, name);
                if (b != null) {
                    return b;
                }
            }
        }
        return null;
    }
}
