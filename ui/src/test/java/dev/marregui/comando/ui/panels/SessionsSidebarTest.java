package dev.marregui.comando.ui.panels;

import com.formdev.flatlaf.FlatDarkLaf;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.core.view.ViewSpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.swing.AbstractButton;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JTabbedPane;
import javax.swing.JTree;
import javax.swing.KeyStroke;
import javax.swing.LookAndFeel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Component;
import java.awt.Container;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

class SessionsSidebarTest {

    private static final SessionsSidebar.Dialogs NO_DIALOGS = new Dialogs();

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
    void theScratchpadIsANavigatorWithoutARootRowWhoseFilesOpenWithOneClick() throws Exception {
        List<Path> opened = new CopyOnWriteArrayList<>();
        SessionsSidebar.Dialogs dialogs = new Dialogs() {
            @Override
            public void openFile(Path file) {
                opened.add(file);
            }
        };
        List<Intent> intents = new CopyOnWriteArrayList<>();
        SessionsSidebar[] s = new SessionsSidebar[1];
        SwingUtilities.invokeAndWait(() -> s[0] = new SessionsSidebar(intents::add, dialogs, () -> {
        }));
        SessionsSidebar sidebar = s[0];
        SwingUtilities.invokeAndWait(() -> sidebar.selectTab(SessionsSidebar.SCRATCHPAD));
        assertThat(intents).as("the tools adapter keeps the tree in sync; a tab switch reads nothing").isEmpty();

        ViewSpec.TreeNode doc = new ViewSpec.TreeNode("READ-01-X.md", "1 kB", "comando-file:/s/2026-10-14/READ-01-X.md#0", List.of());
        ViewSpec.TreeNode root = new ViewSpec.TreeNode("SCRATCHPAD", "/s", List.of(
                new ViewSpec.TreeNode("notes", "0 entries", List.of()),
                new ViewSpec.TreeNode("2026-10-13", "0 entries", List.of()),
                new ViewSpec.TreeNode("2026-10-14", "1 entry", List.of(doc)),
                new ViewSpec.TreeNode("PLAN.md", "2 kB", "comando-file:/s/PLAN.md#0", List.of())));
        SwingUtilities.invokeAndWait(() -> sidebar.showScratchpad(root));
        JTree tree = find(sidebar.component(), JTree.class, t -> "Scratchpad".equals(t.getAccessibleContext().getAccessibleName()));
        assertThat(tree.isRootVisible()).isFalse();
        assertThat(tree.getRowCount()).as("the four entries and the newest day open").isEqualTo(5);

        SwingUtilities.invokeAndWait(() -> {
            tree.setSize(260, 400);
            tree.doLayout();
            Rectangle row = tree.getRowBounds(3);
            tree.dispatchEvent(new MouseEvent(tree, MouseEvent.MOUSE_CLICKED, 0, 0, row.x + 4, row.y + row.height / 2, 1, false,
                    MouseEvent.BUTTON1));
        });
        assertThat(opened).containsExactly(Path.of("/s/2026-10-14/READ-01-X.md"));
    }

    @Test
    void aSessionRowConnectsChangesModelAndEffortAndOpensItsEditor() throws Exception {
        List<String> edited = new CopyOnWriteArrayList<>();
        SessionsSidebar.Dialogs dialogs = new Dialogs() {
            @Override
            public void sessions(String select) {
                edited.add(select);
            }
        };
        List<Intent> intents = new CopyOnWriteArrayList<>();
        SessionState edge = SessionState.closed(SessionSpec.fresh("edge", "/w/edge").withModel("opus").withEffort("high"));
        PanelState panel = new PanelState(new PanelId("p"), "spec", "", "");
        AppState state = AppState.initial(List.of(panel), List.of(), List.of(edge));
        SessionsSidebar[] s = new SessionsSidebar[1];
        SwingUtilities.invokeAndWait(() -> {
            s[0] = new SessionsSidebar(intents::add, dialogs, () -> {
            });
            s[0].render(state);
        });
        SessionsSidebar sidebar = s[0];
        JList<?> list = find(sidebar.component(), JList.class, l -> "Sessions".equals(l.getAccessibleContext().getAccessibleName()));
        SwingUtilities.invokeAndWait(() -> {
            list.setSize(260, 400);
            list.doLayout();
        });

        Rectangle power = bounds(sidebar, SessionRow.Control.POWER);
        click(list, power, 1);
        assertThat(intents).containsExactly(new Intent.StartSession("edge"));
        assertThat(list.getToolTipText(event(list, power, 0))).isEqualTo("Connect: start the session");

        intents.clear();
        click(list, power, 2);
        assertThat(intents).as("a double click on a control binds nothing").doesNotHaveAnyElementsOfTypes(Intent.BindSession.class);
        intents.clear();
        Rectangle cell = list.getCellBounds(0, 0);
        click(list, new Rectangle(cell.x + 40, cell.y + 4, 1, 1), 2);
        assertThat(intents).containsExactly(new Intent.BindSession(new PanelId("p"), "edge"));

        click(list, bounds(sidebar, SessionRow.Control.EDIT), 1);
        assertThat(edited).containsExactly("edge");

        intents.clear();
        List<JMenuItem> models = sidebar.modelItems(edge);
        assertThat(models).extracting(JMenuItem::getText).containsExactly("fable", "opus", "sonnet", "haiku");
        assertThat(models).filteredOn(AbstractButton::isSelected).extracting(JMenuItem::getText).containsExactly("opus");
        SwingUtilities.invokeAndWait(() -> models.getFirst().doClick());
        List<JMenuItem> efforts = sidebar.effortItems(edge);
        assertThat(efforts).filteredOn(AbstractButton::isSelected).extracting(JMenuItem::getText).containsExactly("high");
        SwingUtilities.invokeAndWait(() -> efforts.getLast().doClick());
        assertThat(intents).containsExactly(new Intent.SetSessionModel("edge", "fable"), new Intent.SetSessionEffort("edge", "max"));
    }

    @Test
    void aRowNamesTheModelItAskedForElseTheOneReportedElseDefault() {
        SessionSpec spec = SessionSpec.fresh("edge", "/w/edge");
        assertThat(SessionRow.modelName(SessionState.closed(spec))).isEqualTo("default");
        assertThat(SessionRow.modelName(SessionState.closed(spec.withModel("claude-opus-5-5")))).isEqualTo("opus");
        assertThat(SessionRow.effortName(SessionState.closed(spec))).isEqualTo("default");
    }

    private static Rectangle bounds(SessionsSidebar sidebar, SessionRow.Control control) throws Exception {
        Rectangle[] r = new Rectangle[1];
        SwingUtilities.invokeAndWait(() -> r[0] = sidebar.controlBounds(0, control));
        assertThat(r[0].width).as(control + " is laid out").isPositive();
        return r[0];
    }

    private static MouseEvent event(Component c, Rectangle at, int clicks) {
        return new MouseEvent(c, clicks == 0 ? MouseEvent.MOUSE_MOVED : MouseEvent.MOUSE_CLICKED, 0, 0,
                at.x + at.width / 2, at.y + at.height / 2, clicks, false, MouseEvent.BUTTON1);
    }

    private static void click(Component c, Rectangle at, int clicks) throws Exception {
        SwingUtilities.invokeAndWait(() -> c.dispatchEvent(event(c, at, clicks)));
    }

    /** Dialogs that do nothing, for tests that override what they look at. */
    private static class Dialogs implements SessionsSidebar.Dialogs {
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

        @Override
        public void openFile(Path file) {
        }
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
