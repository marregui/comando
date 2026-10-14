package dev.marregui.comando.ui.panels;

import com.formdev.flatlaf.FlatDarkLaf;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.view.ViewSpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.swing.AbstractButton;
import javax.swing.JTabbedPane;
import javax.swing.JTree;
import javax.swing.KeyStroke;
import javax.swing.LookAndFeel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Component;
import java.awt.Container;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

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

    private static LookAndFeel previous;

    @BeforeAll
    static void flatLaf() throws Exception {
        previous = UIManager.getLookAndFeel();
        UIManager.setLookAndFeel(new FlatDarkLaf()); // the application's look: the tab strip's trailing button is FlatLaf's
    }

    @AfterAll
    static void restore() throws Exception {
        UIManager.setLookAndFeel(previous);
    }

    @Test
    void theColumnIsThreeTabsAndHidesToARailWhoseButtonTogglesItBack() throws Exception {
        AtomicInteger toggles = new AtomicInteger();
        SessionsSidebar[] s = new SessionsSidebar[1];
        SwingUtilities.invokeAndWait(() -> s[0] = new SessionsSidebar(intent -> {
        }, NO_DIALOGS, toggles::incrementAndGet));
        SessionsSidebar sidebar = s[0];
        JTabbedPane tabs = find(sidebar.component(), JTabbedPane.class, c -> true);
        assertThat(List.of(tabs.getTitleAt(0), tabs.getTitleAt(1), tabs.getTitleAt(2))).containsExactly("Sessions", "Connections", "Scratchpad");
        assertThat(tabs.getTabLayoutPolicy()).as("one row: a wrapped strip moves rows on a click").isEqualTo(JTabbedPane.SCROLL_TAB_LAYOUT);

        AbstractButton hide = button(sidebar.component(), "Hide the sidebar (⌘B)");
        AbstractButton show = button(sidebar.component(), "Show the sidebar (⌘B)");
        assertThat(sidebar.isShown()).isTrue();
        assertThat(shows(show, sidebar.component())).as("no rail beside the tabs").isFalse();
        for (int tab : new int[] {SessionsSidebar.SESSIONS, SessionsSidebar.CONNECTIONS, SessionsSidebar.SCRATCHPAD}) {
            SwingUtilities.invokeAndWait(() -> sidebar.selectTab(tab));
            assertThat(shows(hide, sidebar.component())).as("the hide button on every tab").isTrue();
        }

        hide.doClick();
        assertThat(toggles).hasValue(1);

        SwingUtilities.invokeAndWait(() -> sidebar.show(false));
        assertThat(sidebar.isShown()).isFalse();
        assertThat(shows(hide, sidebar.component())).isFalse();
        assertThat(shows(show, sidebar.component())).isTrue();
        assertThat(sidebar.railWidth()).as("a rail, not a column").isBetween(16, 40);

        show.doClick();
        assertThat(toggles).hasValue(2);

        SwingUtilities.invokeAndWait(() -> sidebar.show(true));
        assertThat(shows(hide, sidebar.component())).isTrue();
        assertThat(shows(show, sidebar.component())).isFalse();
    }

    @Test
    void theScratchpadIsReadWhenItsTabComesToTheFrontAndShownAsATree() throws Exception {
        List<Intent> intents = new CopyOnWriteArrayList<>();
        SessionsSidebar[] s = new SessionsSidebar[1];
        SwingUtilities.invokeAndWait(() -> s[0] = new SessionsSidebar(intents::add, NO_DIALOGS, () -> {
        }));
        SessionsSidebar sidebar = s[0];
        assertThat(sidebar.scratchpadShowing()).isFalse();
        SwingUtilities.invokeAndWait(() -> sidebar.selectTab(SessionsSidebar.SCRATCHPAD));
        assertThat(sidebar.scratchpadShowing()).isTrue();
        assertThat(intents).containsExactly(new Intent.BrowseScratchpad());

        ViewSpec.TreeNode doc = new ViewSpec.TreeNode("READ-01-X.md", "1 kB", "comando-file:/s/2026-10-14/READ-01-X.md#0", List.of());
        ViewSpec.TreeNode root = new ViewSpec.TreeNode("SCRATCHPAD", "/s", List.of(
                new ViewSpec.TreeNode("PLAN.md", "2 kB", "comando-file:/s/PLAN.md#0", List.of()),
                new ViewSpec.TreeNode("2026-10-14/", "1 entries", List.of(doc)),
                new ViewSpec.TreeNode("2026-10-13/", "0 entries", List.of())));
        SwingUtilities.invokeAndWait(() -> sidebar.showScratchpad(root));
        JTree tree = find(sidebar.component(), JTree.class, t -> "Scratchpad".equals(t.getAccessibleContext().getAccessibleName()));
        assertThat(tree.getRowCount()).as("root, its three entries, and the newest day open").isEqualTo(5);

        SwingUtilities.invokeAndWait(() -> {
            tree.setSelectionRow(3);
            // Enter's binding, run as the focused tree would: a key event sent to an unfocused component is dropped
            Object action = tree.getInputMap().get(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0));
            tree.getActionMap().get(action).actionPerformed(new ActionEvent(tree, ActionEvent.ACTION_PERFORMED, "activate"));
        });
        assertThat(intents).last().isEqualTo(new Intent.OpenFile("", "/s/2026-10-14/READ-01-X.md", 0));
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
        return find(root, AbstractButton.class, b -> name.equals(b.getAccessibleContext().getAccessibleName()));
    }

    private static <T extends Component> T find(Component root, Class<T> type, Predicate<T> match) {
        T found = search(root, type, match);
        assertThat(found).as(type.getSimpleName()).isNotNull();
        return found;
    }

    private static <T extends Component> T search(Component root, Class<T> type, Predicate<T> match) {
        if (type.isInstance(root) && match.test(type.cast(root))) {
            return type.cast(root);
        }
        if (root instanceof Container c) {
            for (Component child : c.getComponents()) {
                T t = search(child, type, match);
                if (t != null) {
                    return t;
                }
            }
        }
        return null;
    }
}
