package dev.marregui.comando.ui.panels;

import com.formdev.flatlaf.FlatDarkLaf;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.ConnectionSpec;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.model.Status;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.ConnectionState;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.core.view.ViewSpec;
import dev.marregui.comando.ui.kit.resource.ResourceActions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.swing.AbstractButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JToggleButton;
import javax.swing.JTree;
import javax.swing.KeyStroke;
import javax.swing.LookAndFeel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Component;
import java.awt.Container;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    void theColumnIsFourTabsInTwoFixedRowsAndHidesToARailWhoseButtonTogglesItBack() throws Exception {
        AtomicInteger toggles = new AtomicInteger();
        SessionsSidebar[] s = new SessionsSidebar[1];
        SwingUtilities.invokeAndWait(() -> s[0] = new SessionsSidebar(intent -> {
        }, NO_DIALOGS, toggles::incrementAndGet));
        SessionsSidebar sidebar = s[0];
        Map<String, Rectangle> at = new HashMap<>();
        SwingUtilities.invokeAndWait(() -> {
            sidebar.component().setSize(280, 500);
            layout(sidebar.component());
            for (String t : List.of("Sessions", "Panels", "Connections", "Scratchpad")) {
                JToggleButton h = find(sidebar.component(), JToggleButton.class, b -> t.equals(b.getText()));
                at.put(t, SwingUtilities.convertRectangle(h.getParent(), h.getBounds(), sidebar.component()));
            }
        });
        assertThat(at.get("Panels").y).as("Panels and Scratchpad on the top row").isEqualTo(at.get("Scratchpad").y)
                .isLessThan(at.get("Sessions").y);
        assertThat(at.get("Sessions").y).as("Sessions and Connections below").isEqualTo(at.get("Connections").y);
        assertThat(at.get("Panels").x).isLessThan(at.get("Scratchpad").x).isEqualTo(at.get("Sessions").x);
        assertThat(at.get("Sessions").x).isLessThan(at.get("Connections").x);

        for (int tab : new int[] {SessionsSidebar.SESSIONS, SessionsSidebar.PANELS, SessionsSidebar.CONNECTIONS, SessionsSidebar.SCRATCHPAD}) {
            SwingUtilities.invokeAndWait(() -> sidebar.selectTab(tab));
            assertThat(sidebar.selectedTab()).isEqualTo(tab);
        }
        SwingUtilities.invokeAndWait(() -> find(sidebar.component(), JToggleButton.class, b -> "Panels".equals(b.getText())).doClick());
        assertThat(sidebar.selectedTab()).as("a click selects; nothing moves").isEqualTo(SessionsSidebar.PANELS);
        Rectangle panelsAfter = new Rectangle();
        SwingUtilities.invokeAndWait(() -> {
            layout(sidebar.component());
            JToggleButton h = find(sidebar.component(), JToggleButton.class, b -> "Panels".equals(b.getText()));
            panelsAfter.setBounds(SwingUtilities.convertRectangle(h.getParent(), h.getBounds(), sidebar.component()));
        });
        assertThat(panelsAfter).isEqualTo(at.get("Panels"));

        AbstractButton hide = button(sidebar.component(), "Hide the sidebar (⌘B)");
        AbstractButton show = button(sidebar.component(), "Show the sidebar (⌘B)");
        assertThat(sidebar.isShown()).isTrue();
        assertThat(shows(show, sidebar.component())).as("no rail beside the tabs").isFalse();
        for (int tab : new int[] {SessionsSidebar.SESSIONS, SessionsSidebar.PANELS, SessionsSidebar.CONNECTIONS, SessionsSidebar.SCRATCHPAD}) {
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
    void aSessionRowConnectsAndChangesModelAndEffort() throws Exception {
        SessionsSidebar.Dialogs dialogs = new Dialogs();
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
        assertThat(list.getToolTipText(event(list, power, 0))).isEqualTo("Disconnected; click to connect (starts the session)");

        intents.clear();
        click(list, power, 2);
        assertThat(intents).as("a double click on a control binds nothing").doesNotHaveAnyElementsOfTypes(Intent.BindSession.class, Intent.OpenSession.class);
        intents.clear();
        Rectangle cell = list.getCellBounds(0, 0);
        click(list, new Rectangle(cell.x + 40, cell.y + 4, 1, 1), 2);
        assertThat(intents).singleElement().isInstanceOfSatisfying(Intent.OpenSession.class, o -> {
            assertThat(o.session()).isEqualTo("edge");
            assertThat(o.target()).isEqualTo(new PanelId("p"));
        });

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
    void sessionsAndConnectionsCarryTheSameButtonsAndOnlySessionsImport() throws Exception {
        List<String> opened = new CopyOnWriteArrayList<>();
        SessionsSidebar.Dialogs dialogs = new Dialogs() {
            @Override
            public ResourceActions.Editor sessions() {
                return recording("session", opened);
            }

            @Override
            public ResourceActions.Editor connections() {
                return recording("connection", opened);
            }

            @Override
            public void importSessions() {
                opened.add("import");
            }
        };
        List<Intent> intents = new CopyOnWriteArrayList<>();
        List<String> asked = new CopyOnWriteArrayList<>();
        SessionState edge = SessionState.closed(SessionSpec.fresh("edge", "/w/edge"));
        ConnectionState local = ConnectionState.closed(ConnectionSpec.questDbDefaults("local")).with(Status.OPEN, "");
        AppState state = AppState.initial(List.of(new PanelState(new PanelId("p"), "main", "local", "")), List.of(local), List.of(edge));
        SessionsSidebar[] s = new SessionsSidebar[1];
        SwingUtilities.invokeAndWait(() -> {
            s[0] = new SessionsSidebar(intents::add, dialogs, () -> {
            }, (parent, text, title, options) -> {
                asked.add(title + ": " + List.of(options));
                return 0;
            });
            s[0].render(state);
        });
        Component root = s[0].component();
        JList<?> connections = find(root, JList.class, l -> "Connections".equals(l.getAccessibleContext().getAccessibleName()));
        JList<?> sessions = find(root, JList.class, l -> "Sessions".equals(l.getAccessibleContext().getAccessibleName()));

        button(root, "New session…").doClick();
        button(root, "Import stored conversations…").doClick();
        button(root, "New connection…").doClick();
        assertThat(search(root, AbstractButton.class, b -> b.getAccessibleContext().getAccessibleName() != null
                && b.getAccessibleContext().getAccessibleName().startsWith("Import") && b != button(root, "Import stored conversations…")))
                .as("one import, on the sessions").isNull();
        SwingUtilities.invokeAndWait(() -> {
            sessions.setSelectedIndex(0);
            connections.setSelectedIndex(0);
        });
        button(root, "Edit the selected session…").doClick();
        button(root, "Clone the selected connection…").doClick();
        assertThat(opened).containsExactly("new session", "import", "new connection", "edit session edge", "clone connection local");

        button(root, "Remove the selected connection…").doClick();
        assertThat(asked).as("an open connection is disconnected first, or left")
                .containsExactly("Remove local: [Disconnect and remove, Leave it]");
        assertThat(intents).containsExactly(new Intent.RemoveConnection("local"));
        intents.clear();
        button(root, "Remove the selected session…").doClick();
        assertThat(intents).containsExactly(new Intent.RemoveSession("edge"));
    }

    private static ResourceActions.Editor recording(String noun, List<String> opened) {
        return new ResourceActions.Editor() {
            @Override
            public void add() {
                opened.add("new " + noun);
            }

            @Override
            public void edit(String name) {
                opened.add("edit " + noun + " " + name);
            }

            @Override
            public void cloneOf(String name) {
                opened.add("clone " + noun + " " + name);
            }
        };
    }

    @Test
    void aRowNamesThePanelsItIsBoundToElseHeadlessAndItsPlugShowsTheState() throws Exception {
        SessionState edge = SessionState.closed(SessionSpec.fresh("edge", "/w/edge"));
        SessionState work = SessionState.closed(SessionSpec.fresh("work", "/w/work")).withStatus(Status.OPEN, "");
        AppState state = AppState.initial(List.of(new PanelState(new PanelId("p"), "main", "", "work"),
                new PanelState(new PanelId("q"), "notes", "", "work")), List.of(), List.of(edge, work));
        SessionsSidebar[] s = new SessionsSidebar[1];
        SwingUtilities.invokeAndWait(() -> {
            s[0] = new SessionsSidebar(intent -> {
            }, new Dialogs(), () -> {
            });
            s[0].render(state);
        });
        JList<?> list = find(s[0].component(), JList.class, l -> "Sessions".equals(l.getAccessibleContext().getAccessibleName()));
        assertThat(rowText(list, 0)).contains("· headless").doesNotContain("this panel");
        assertThat(rowText(list, 1)).contains("· main, notes");

        SessionRow row = (SessionRow) render(list, 1);
        assertThat(row.tip(new Rectangle(0, 0, 260, 60), new Point(0, 0))).isEmpty();
        assertThat(SessionRow.Control.values()).as("no edit control: the button above the list edits")
                .containsExactly(SessionRow.Control.POWER, SessionRow.Control.MODEL, SessionRow.Control.EFFORT);
        Rectangle power = s[0].controlBounds(1, SessionRow.Control.POWER);
        assertThat(list.getToolTipText(event(list, power, 0))).isEqualTo("Connected; click to disconnect (stops the session)");
    }

    private static Component render(JList<?> list, int index) throws Exception {
        Component[] c = new Component[1];
        SwingUtilities.invokeAndWait(() -> {
            list.setSize(260, 400);
            list.doLayout();
            @SuppressWarnings("unchecked")
            JList<Object> l = (JList<Object>) list;
            c[0] = l.getCellRenderer().getListCellRendererComponent(l, l.getModel().getElementAt(index), index, false, false);
        });
        return c[0];
    }

    /** The row's text, read on the EDT in the same step as the render: the list shares one renderer, which a paint reuses. */
    private static String rowText(JList<?> list, int index) throws Exception {
        String[] text = new String[1];
        SwingUtilities.invokeAndWait(() -> {
            @SuppressWarnings("unchecked")
            JList<Object> l = (JList<Object>) list;
            Component row = l.getCellRenderer().getListCellRendererComponent(l, l.getModel().getElementAt(index), index, false, false);
            JLabel label = search(row, JLabel.class, x -> x.getText() != null && x.getText().startsWith("<html>"));
            text[0] = label == null ? null : label.getText();
        });
        assertThat(text[0]).as("row " + index + " text").isNotNull();
        return text[0];
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
        public ResourceActions.Editor sessions() {
            return recording("session", new CopyOnWriteArrayList<>());
        }

        @Override
        public void importSessions() {
        }

        @Override
        public ResourceActions.Editor connections() {
            return recording("connection", new CopyOnWriteArrayList<>());
        }

        @Override
        public void exploreSchema(String connection) {
        }

        @Override
        public void openFile(Path file) {
        }
    }

    private static void layout(Component c) {
        if (c instanceof Container k) {
            k.doLayout();
            for (Component child : k.getComponents()) {
                layout(child);
            }
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
