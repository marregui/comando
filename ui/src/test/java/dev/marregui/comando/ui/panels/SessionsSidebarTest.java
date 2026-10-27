package dev.marregui.comando.ui.panels;

import com.formdev.flatlaf.FlatDarkLaf;
import dev.marregui.comando.core.dispatch.Target;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.ConnectionSpec;
import dev.marregui.comando.core.model.FolderRoot;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.model.Status;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.ConnectionState;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.core.state.Reflection;
import dev.marregui.comando.core.state.SessionInfo;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.core.view.ViewSpec;
import dev.marregui.comando.ui.PanelTexts;
import dev.marregui.comando.ui.kit.format.KeyText;
import dev.marregui.comando.ui.kit.icon.Icons;
import dev.marregui.comando.ui.kit.resource.ResourceActions;
import dev.marregui.comando.ui.kit.theme.Theme;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.swing.AbstractButton;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JToggleButton;
import javax.swing.JTree;
import javax.swing.LookAndFeel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.plaf.basic.BasicHTML;
import java.awt.Component;
import java.awt.Color;
import java.awt.Container;
import java.awt.HeadlessException;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
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
    void theColumnIsThreeTabsInOneRowAndHidesToARailWhoseButtonTogglesItBack() throws Exception {
        AtomicInteger toggles = new AtomicInteger();
        SessionsSidebar[] s = new SessionsSidebar[1];
        SwingUtilities.invokeAndWait(() -> s[0] = new SessionsSidebar(intent -> {
        }, NO_DIALOGS, toggles::incrementAndGet));
        SessionsSidebar sidebar = s[0];
        List<String> order = List.of("Sessions", "Connections", "Files");
        Map<String, Rectangle> at = new HashMap<>();
        Map<String, String> text = new HashMap<>();
        SwingUtilities.invokeAndWait(() -> {
            sidebar.component().setSize(420, 500);
            layout(sidebar.component());
            for (String t : order) {
                JToggleButton h = header(sidebar, t);
                at.put(t, SwingUtilities.convertRectangle(h.getParent(), h.getBounds(), sidebar.component()));
                text.put(t, h.getText());
            }
        });
        for (int i = 1; i < order.size(); i++) {
            assertThat(at.get(order.get(i)).y).as("one row").isEqualTo(at.get("Sessions").y);
            assertThat(at.get(order.get(i)).x).as("in the order " + order).isGreaterThan(at.get(order.get(i - 1)).x);
        }
        assertThat(text.values()).as("icons alone").containsOnly("");
        assertThat(header(sidebar, "Files").getClientProperty("JButton.buttonType")).as("drawn as a tab").isEqualTo("tab");
        assertThat(header(sidebar, "Files").getIcon()).as("the Files tab's own mark, not the tree's folder").isSameAs(Icons.get("files"));
        assertThat(at.get("Files").x + at.get("Files").width).as("packed: three icons in a narrow sidebar").isLessThan(160);
        for (String t : order) {
            JToggleButton h = header(sidebar, t);
            assertThat(h.getToolTipText()).as(t + " is named by its tooltip").startsWith(t + ": ").hasSizeLessThanOrEqualTo(80);
            assertThat(h.getAccessibleContext().getAccessibleName()).isEqualTo(t);
        }

        assertThat(search(sidebar.component(), JToggleButton.class, b -> "Panels".equals(b.getAccessibleContext().getAccessibleName())))
                .as("no Panels tab: a session and its panel are one row (record 91)").isNull();
        for (int tab : new int[] {SessionsSidebar.SESSIONS, SessionsSidebar.CONNECTIONS, SessionsSidebar.FILES}) {
            SwingUtilities.invokeAndWait(() -> sidebar.selectTab(tab));
            assertThat(sidebar.selectedTab()).isEqualTo(tab);
        }
        SwingUtilities.invokeAndWait(() -> header(sidebar, "Connections").doClick());
        assertThat(sidebar.selectedTab()).as("a click selects").isEqualTo(SessionsSidebar.CONNECTIONS);

        AbstractButton hide = button(sidebar.component(), KeyText.of("Hide the sidebar (⌘B)"));
        AbstractButton show = button(sidebar.component(), KeyText.of("Show the sidebar (⌘B)"));
        assertThat(sidebar.isShown()).isTrue();
        assertThat(shows(show, sidebar.component())).as("no rail beside the tabs").isFalse();
        for (int tab : new int[] {SessionsSidebar.SESSIONS, SessionsSidebar.CONNECTIONS, SessionsSidebar.FILES}) {
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
    void theFilesTabShowsOneRowPerFolderAndOpensAFileWithADoubleClick() throws Exception {
        List<Path> opened = new CopyOnWriteArrayList<>();
        SessionsSidebar.Dialogs dialogs = new Dialogs() {
            @Override
            public void openFile(Path file) {
                opened.add(file);
            }
        };
        List<Intent> intents = new CopyOnWriteArrayList<>();
        AppState state = AppState.initial(List.of(new PanelState(new PanelId("p"), "spec", "", "")), List.of(), List.of(),
                List.of(new FolderRoot("Scratchpad", "/s", "", List.of()), new FolderRoot("Notes", "/n", "", List.of())));
        SessionsSidebar[] s = new SessionsSidebar[1];
        SwingUtilities.invokeAndWait(() -> {
            s[0] = new SessionsSidebar(intents::add, dialogs, () -> {
            });
            s[0].render(state);
        });
        SessionsSidebar sidebar = s[0];
        SwingUtilities.invokeAndWait(() -> sidebar.selectTab(SessionsSidebar.FILES));
        assertThat(intents).as("no folder open: none is watched, and a tab switch reads nothing").isEmpty();

        JTree tree = find(sidebar.component(), JTree.class, t -> "Files".equals(t.getAccessibleContext().getAccessibleName()));
        assertThat(tree.isRootVisible()).isFalse();
        assertThat(tree.getRowCount()).as("one row per folder").isEqualTo(2);
        SwingUtilities.invokeAndWait(() -> tree.expandRow(0));
        assertThat(intents).last().isEqualTo(new Intent.WatchFolders(List.of("Scratchpad")));

        ViewSpec.TreeNode doc = new ViewSpec.TreeNode("READ-01-X.md", "1 kB", "comando-file:/s/2026-10-14/READ-01-X.md#0", List.of());
        ViewSpec.TreeNode read = new ViewSpec.TreeNode("Scratchpad", "/s", List.of(
                new ViewSpec.TreeNode("2026-10-14", "1 entry", List.of(doc)),
                new ViewSpec.TreeNode("PLAN.md", "2 kB", "comando-file:/s/PLAN.md#0", List.of())));
        SwingUtilities.invokeAndWait(() -> sidebar.showFolder(state.folder("Scratchpad").orElseThrow(), read));
        assertThat(tree.getRowCount()).as("Scratchpad, its day open with its file, PLAN.md, Notes").isEqualTo(5);

        SwingUtilities.invokeAndWait(() -> {
            tree.setSize(260, 400);
            tree.doLayout();
            Rectangle row = tree.getRowBounds(2);
            tree.dispatchEvent(new MouseEvent(tree, MouseEvent.MOUSE_CLICKED, 0, 0, row.x + 4, row.y + row.height / 2, 1, false,
                    MouseEvent.BUTTON1));
        });
        assertThat(opened).as("one click selects, opens nothing").isEmpty();
        SwingUtilities.invokeAndWait(() -> {
            Rectangle row = tree.getRowBounds(2);
            tree.dispatchEvent(new MouseEvent(tree, MouseEvent.MOUSE_CLICKED, 0, 0, row.x + 4, row.y + row.height / 2, 2, false,
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
        Rectangle row = cell(list, 0);
        assertThat(list.getToolTipText(event(list, new Rectangle(row.x + 30, row.y + 4, 4, 4), 0)))
                .as("over the text no tooltip: the row's card shows there, as a tab's").isNull();

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
    void tabMenusAddAndRowMenusEditCloneAndRemoveWithNoBarOfButtons() throws Exception {
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
        assertThat(search(root, AbstractButton.class, b -> b.getToolTipText() != null && b.getToolTipText().contains("selected")))
                .as("no bar of buttons over a list").isNull();
        SessionsSidebar sidebar = s[0];

        SwingUtilities.invokeAndWait(() -> {
            menuItem(sidebar.tabMenu(SessionsSidebar.SESSIONS), "New session…").doClick();
            menuItem(sidebar.tabMenu(SessionsSidebar.SESSIONS), "Import stored conversations…").doClick();
            menuItem(sidebar.tabMenu(SessionsSidebar.CONNECTIONS), "New connection…").doClick();
            menuItem(sidebar.sessionMenu(edge), "Edit…").doClick();
            menuItem(sidebar.connectionMenu(local), "Clone…").doClick();
        });
        assertThat(opened).containsExactly("new session", "import", "new connection", "edit session edge", "clone connection local");

        SwingUtilities.invokeAndWait(() -> menuItem(sidebar.connectionMenu(local), "Remove…").doClick());
        assertThat(asked).as("an open connection is disconnected first, or left")
                .containsExactly("Remove local: [Disconnect and remove, Leave it]");
        assertThat(intents).containsExactly(new Intent.RemoveConnection("local"));
        intents.clear();
        SwingUtilities.invokeAndWait(() -> menuItem(sidebar.sessionMenu(edge), "Remove…").doClick());
        assertThat(intents).containsExactly(new Intent.RemoveSession("edge"));
    }

    @Test
    void aRunningSessionsMenuHasItsPanelRewrittenAsADocumentAndNoClear() throws Exception {
        List<Intent> intents = new CopyOnWriteArrayList<>();
        PanelId notes = new PanelId("notes");
        PanelId strategy = new PanelId("strategy");
        PanelId other = new PanelId("other");
        Map<PanelId, String> texts = new HashMap<>(Map.of(strategy, "i am loving comando so far\n\nthe strategy is ..."));
        PanelTexts store = new PanelTexts() {
            @Override
            public String load(PanelId panel) {
                return texts.getOrDefault(panel, "");
            }

            @Override
            public void changed(PanelId panel, String text) {
            }

            @Override
            public void forget(PanelId panel) {
            }
        };
        SessionState stopped = SessionState.closed(SessionSpec.fresh("work", "/w"));
        SessionState running = stopped.withStatus(Status.OPEN, "");
        List<PanelState> panels = List.of(new PanelState(notes, "notes", "", "work"), new PanelState(strategy, "strategy", "", "work"),
                new PanelState(other, "other", "", ""));
        SessionsSidebar[] s = new SessionsSidebar[1];
        SwingUtilities.invokeAndWait(() -> {
            s[0] = new SessionsSidebar(intents::add, new Dialogs(), store, () -> {
            }, ResourceActions.Questions.MODAL);
            s[0].render(AppState.initial(panels, List.of(), List.of(stopped)));
        });
        SessionsSidebar sidebar = s[0];
        assertThat(on(() -> itemTexts(sidebar.sessionMenu(stopped)))).as("stopped: no session to ask")
                .noneMatch(t -> t.startsWith("Rewrite")).doesNotContain("Clear the conversation");

        SwingUtilities.invokeAndWait(() -> sidebar.render(AppState.initial(panels, List.of(), List.of(running)).withPanels(panels, strategy)));
        assertThat(on(() -> itemTexts(sidebar.sessionMenu(running)))).as("the checkpoint clears").doesNotContain("Clear the conversation");
        JMenuItem rewrite = on(() -> menuItem(sidebar.sessionMenu(running), "Rewrite strategy as a document"));
        assertThat(rewrite.isEnabled()).as("the focused panel, the session's").isTrue();
        assertThat(rewrite.getToolTipText()).isEqualTo(EditorPanel.POLISH_TIP);
        SwingUtilities.invokeAndWait(rewrite::doClick);
        assertThat(intents).singleElement().isInstanceOfSatisfying(Intent.RunText.class, r -> {
            assertThat(r.panel()).isEqualTo(strategy);
            assertThat(r.target()).as("to the panel's session, never to SQL").isEqualTo(Target.CLAUDE);
            assertThat(r.text()).startsWith("Rewrite the text below, the whole text of the comando panel \"strategy\"")
                    .contains("replace the panel's text with it (comando's ui_set_editor, panel \"strategy\")")
                    .endsWith("\n\ni am loving comando so far\n\nthe strategy is ...");
        });

        intents.clear();
        SwingUtilities.invokeAndWait(() -> sidebar.render(AppState.initial(panels, List.of(), List.of(running)).withPanels(panels, other)));
        JMenuItem first = on(() -> menuItem(sidebar.sessionMenu(running), "Rewrite notes as a document"));
        assertThat(first.isEnabled()).as("focus elsewhere: the session's first panel, empty, asks nothing").isFalse();
    }

    @Test
    void aSessionRowShowsTheWorkspacesCardOverItsTextAndTellsItWhenThePointerLeaves() throws Exception {
        List<String> told = new CopyOnWriteArrayList<>();
        SessionState work = SessionState.closed(SessionSpec.fresh("work", "/w/work")).withStatus(Status.OPEN, "");
        AppState state = AppState.initial(List.of(new PanelState(new PanelId("p"), "main", "", "work")), List.of(), List.of(work));
        SessionsSidebar[] s = new SessionsSidebar[1];
        SwingUtilities.invokeAndWait(() -> {
            s[0] = new SessionsSidebar(intent -> {
            }, new Dialogs(), () -> {
            });
            s[0].cards(new SessionsSidebar.Cards() {
                @Override
                public void show(String session, JComponent over, Rectangle row) {
                    told.add("show " + session + " " + row.y);
                }

                @Override
                public void left() {
                    told.add("left");
                }
            });
            s[0].render(state);
        });
        SessionsSidebar sidebar = s[0];
        JList<?> list = find(sidebar.component(), JList.class, l -> "Sessions".equals(l.getAccessibleContext().getAccessibleName()));
        SwingUtilities.invokeAndWait(() -> {
            list.setSize(260, 400);
            list.doLayout();
        });
        Rectangle row = cell(list, 0);
        SwingUtilities.invokeAndWait(() -> list.dispatchEvent(mouse(list, MouseEvent.MOUSE_MOVED, row.x + 60, row.y + 6, 0)));
        assertThat(sidebar.cardRow()).as("the wait for its card starts").isEqualTo("work");
        SwingUtilities.invokeAndWait(sidebar::showCardNow);
        assertThat(told).containsExactly("show work " + row.y);

        Rectangle power = bounds(sidebar, SessionRow.Control.POWER);
        SwingUtilities.invokeAndWait(() -> list.dispatchEvent(event(list, power, 0)));
        assertThat(told).as("onto a control: its own tip, the card told").containsExactly("show work " + row.y, "left");
        assertThat(sidebar.cardRow()).isNull();

        SwingUtilities.invokeAndWait(() -> list.dispatchEvent(mouse(list, MouseEvent.MOUSE_MOVED, row.x + 60, row.y + 6, 0)));
        SwingUtilities.invokeAndWait(sidebar::showCardNow);
        SwingUtilities.invokeAndWait(() -> list.dispatchEvent(mouse(list, MouseEvent.MOUSE_EXITED, row.x + 60, row.y + 6, 0)));
        assertThat(told).as("off the list").endsWith("show work " + row.y, "left");
    }

    private static List<String> itemTexts(JPopupMenu menu) {
        List<String> out = new ArrayList<>();
        for (Component c : menu.getComponents()) {
            if (c instanceof JMenuItem m) {
                out.add(m.getText());
            }
        }
        return out;
    }

    private static <T> T on(Callable<T> c) throws Exception {
        AtomicReference<T> out = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                out.set(c.call());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        return out.get();
    }

    private static JMenuItem menuItem(JPopupMenu menu, String text) {
        for (Component c : menu.getComponents()) {
            if (c instanceof JMenuItem m && text.equals(m.getText())) {
                return m;
            }
        }
        throw new AssertionError("no item " + text);
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
    void aRowDimsTheNameWhileItsOwnPanelIsNotOpenAndItsStateIconConnects() throws Exception {
        SessionState edge = SessionState.closed(SessionSpec.fresh("edge", "/w/edge"));
        SessionState work = SessionState.closed(SessionSpec.fresh("work", "/w/work")).withStatus(Status.OPEN, "");
        SessionState old = SessionState.closed(SessionSpec.fresh("old", "/w/old"));
        AppState state = AppState.initial(List.of(new PanelState(new PanelId("p"), "work", "", "work"),
                new PanelState(new PanelId("q"), "PLAN.md", "", "work", true)), List.of(), List.of(edge, work, old))
                .withClosed(List.of(new PanelState(new PanelId("e"), "edge", "", "edge")));
        SessionsSidebar[] s = new SessionsSidebar[1];
        SwingUtilities.invokeAndWait(() -> {
            s[0] = new SessionsSidebar(intent -> {
            }, new Dialogs(), () -> {
            });
            s[0].render(state);
        });
        JList<?> list = find(s[0].component(), JList.class, l -> "Sessions".equals(l.getAccessibleContext().getAccessibleName()));
        assertThat(rowText(list, 0)).as("running first; one of its panels has the focus")
                .contains("<b style='color:" + Theme.hex(Theme.tint().body) + "'>work</b>").doesNotContain("PLAN.md", "·");
        String dimmed = "<b style='color:" + Theme.hex(Theme.MUTED) + "'>";
        assertThat(rowText(list, 1)).as("its own panel closed").contains(dimmed + "edge</b>");
        assertThat(rowText(list, 2)).as("no panel").contains(dimmed + "old</b>");

        SessionRow row = (SessionRow) render(list, 0);
        assertThat(row.tip(new Rectangle(0, 0, 260, 60), new Point(0, 0))).isEmpty();
        assertThat(SessionRow.Control.values()).as("the state icon alone: the rest is in the menu")
                .containsExactly(SessionRow.Control.POWER);
        Rectangle power = s[0].controlBounds(0, SessionRow.Control.POWER);
        assertThat(list.getToolTipText(event(list, power, 0))).isEqualTo("Connected; click to disconnect (stops the session)");
    }

    @Test
    void sessionsListRunningFirstThenByNameUntilADragOrdersThemAndSortRestoresIt() throws Exception {
        List<Intent> intents = new CopyOnWriteArrayList<>();
        SessionState beta = SessionState.closed(SessionSpec.fresh("beta", "/w/b"));
        SessionState alpha = SessionState.closed(SessionSpec.fresh("Alpha", "/w/a"));
        SessionState gamma = SessionState.closed(SessionSpec.fresh("gamma", "/w/g")).withStatus(Status.OPEN, "");
        AppState state = AppState.initial(List.of(new PanelState(new PanelId("p"), "main", "", "")), List.of(), List.of(beta, alpha, gamma));
        SessionsSidebar[] s = new SessionsSidebar[1];
        SwingUtilities.invokeAndWait(() -> {
            s[0] = new SessionsSidebar(intents::add, new Dialogs(), () -> {
            });
            s[0].render(state);
        });
        SessionsSidebar sidebar = s[0];
        JList<?> list = find(sidebar.component(), JList.class, l -> "Sessions".equals(l.getAccessibleContext().getAccessibleName()));
        String sortItem = "Sort: running first, then by name";
        assertThat(names(list)).containsExactly("gamma", "Alpha", "beta");
        assertThat(on(() -> menuItem(sidebar.tabMenu(SessionsSidebar.SESSIONS), sortItem).isEnabled())).as("already sorted").isFalse();

        // beta dragged by its text above gamma
        SwingUtilities.invokeAndWait(() -> {
            list.setSize(260, 400);
            list.doLayout();
        });
        Rectangle from = cell(list, 2);
        Rectangle to = cell(list, 0);
        SwingUtilities.invokeAndWait(() -> {
            try {
                list.dispatchEvent(mouse(list, MouseEvent.MOUSE_PRESSED, from.x + 60, from.y + 6, MouseEvent.BUTTON1_DOWN_MASK));
            } catch (HeadlessException e) {
                // the list's own press handler asks the toolkit for the menu key; the row's drag saw the press first
            }
            list.dispatchEvent(mouse(list, MouseEvent.MOUSE_DRAGGED, from.x + 60, from.y - 10, MouseEvent.BUTTON1_DOWN_MASK));
            list.dispatchEvent(mouse(list, MouseEvent.MOUSE_DRAGGED, to.x + 60, to.y + 2, MouseEvent.BUTTON1_DOWN_MASK));
            list.dispatchEvent(mouse(list, MouseEvent.MOUSE_RELEASED, to.x + 60, to.y + 2, 0));
        });
        assertThat(intents).containsExactly(new Intent.OrderSessions(List.of("beta", "gamma", "Alpha")));
        assertThat(names(list)).containsExactly("beta", "gamma", "Alpha");
        assertThat(list.getSelectedValue()).isEqualTo(beta);
        assertThat(sidebar.manualOrder()).isTrue();
        assertThat(on(() -> menuItem(sidebar.tabMenu(SessionsSidebar.SESSIONS), sortItem).isEnabled())).isTrue();

        // the stored order comes back from the reducer and stands, a session starting or not
        SessionState alphaRunning = alpha.withStatus(Status.OPEN, "");
        SwingUtilities.invokeAndWait(() -> sidebar.render(state.withSessions(List.of(beta, gamma, alphaRunning))));
        assertThat(names(list)).containsExactly("beta", "gamma", "Alpha");

        SwingUtilities.invokeAndWait(() -> menuItem(sidebar.tabMenu(SessionsSidebar.SESSIONS), sortItem).doClick());
        assertThat(sidebar.manualOrder()).isFalse();
        assertThat(names(list)).containsExactly("Alpha", "gamma", "beta");

        intents.clear();
        SwingUtilities.invokeAndWait(() -> sidebar.drop(0, 1));
        assertThat(intents).as("dropped where it was: nothing moves").isEmpty();
    }

    @Test
    void aSessionsMenuHoldsWhatItsRowHeld() throws Exception {
        List<Intent> intents = new CopyOnWriteArrayList<>();
        List<String> copied = new CopyOnWriteArrayList<>();
        SessionState work = SessionState.closed(SessionSpec.fresh("work", "/w/work").withModel("opus").withEffort("high").resumable("conv-1"))
                .withStatus(Status.OPEN, "");
        AppState state = AppState.initial(List.of(new PanelState(new PanelId("p"), "main", "", "work")), List.of(), List.of(work));
        SessionsSidebar[] s = new SessionsSidebar[1];
        SwingUtilities.invokeAndWait(() -> {
            s[0] = new SessionsSidebar(intents::add, new Dialogs(), () -> {
            });
            s[0].clipboard = copied::add;
            s[0].render(state);
        });
        SessionsSidebar sidebar = s[0];
        assertThat(on(() -> itemTexts(sidebar.sessionMenu(work)))).as("running: compact and the checkpoint, no fresh start")
                .contains("Checkpoint", "Compact the conversation", "Model: opus", "Effort: high", "Copy details")
                .doesNotContain("Start a new conversation");
        SwingUtilities.invokeAndWait(() -> menuItem(sidebar.sessionMenu(work), "Compact the conversation").doClick());
        SwingUtilities.invokeAndWait(() -> menuItem(sidebar.sessionMenu(work), "Checkpoint").doClick());
        SwingUtilities.invokeAndWait(() -> menuItem(sidebar.sessionMenu(work), "Copy details").doClick());
        assertThat(intents).containsExactly(new Intent.SessionCommand("work", "/compact"), new Intent.Checkpoint("work"));
        assertThat(copied).singleElement().asString().startsWith("Session: work\nPath: /w/work\n");

        SessionState stopped = work.withStatus(Status.CLOSED, "");
        SwingUtilities.invokeAndWait(() -> sidebar.render(state.withSessions(List.of(stopped))));
        assertThat(on(() -> itemTexts(sidebar.sessionMenu(stopped)))).as("stopped: a fresh start, nothing to compact")
                .contains("Start a new conversation").doesNotContain("Compact the conversation");
        intents.clear();
        SwingUtilities.invokeAndWait(() -> menuItem(sidebar.sessionMenu(stopped), "Start a new conversation").doClick());
        assertThat(intents).containsExactly(new Intent.StartFresh("work"));
    }

    @Test
    void aSessionStartedUnderThePointerKeepsItsRowUntilThePointerLeaves() throws Exception {
        SessionState developer = SessionState.closed(SessionSpec.fresh("comando developer", "/w"));
        SessionState improvement = SessionState.closed(SessionSpec.fresh("comando improvement", "/w"));
        SessionState goose = SessionState.closed(SessionSpec.fresh("Goose", "/w"));
        AppState state = AppState.initial(List.of(new PanelState(new PanelId("p"), "main", "", "")), List.of(), List.of(developer, improvement, goose));
        SessionsSidebar[] s = new SessionsSidebar[1];
        SwingUtilities.invokeAndWait(() -> {
            s[0] = new SessionsSidebar(intent -> { }, new Dialogs(), () -> {
            });
            s[0].render(state);
        });
        SessionsSidebar sidebar = s[0];
        JList<?> list = find(sidebar.component(), JList.class, l -> "Sessions".equals(l.getAccessibleContext().getAccessibleName()));
        assertThat(names(list)).containsExactly("comando developer", "comando improvement", "Goose");

        // the second row started with the pointer on the list: it stays second, so the pointer is still on it
        AppState started = state.withSessions(List.of(developer, improvement.withStatus(Status.OPEN, ""), goose));
        SwingUtilities.invokeAndWait(() -> {
            list.dispatchEvent(mouse(list, MouseEvent.MOUSE_ENTERED, 10, 30, 0));
            sidebar.render(started);
        });
        assertThat(names(list)).containsExactly("comando developer", "comando improvement", "Goose");
        assertThat(((SessionState) list.getModel().getElementAt(1)).status()).as("with its new state").isEqualTo(Status.OPEN);

        // the pointer leaves: running first
        SwingUtilities.invokeAndWait(() -> list.dispatchEvent(mouse(list, MouseEvent.MOUSE_EXITED, -1, -1, 0)));
        assertThat(names(list)).containsExactly("comando improvement", "comando developer", "Goose");

        assertThat(SessionsSidebar.held(List.of(developer, improvement), List.of(goose, improvement)))
                .as("a removed session goes, a new one comes last").containsExactly(improvement, goose);
    }

    @Test
    void theDetailsNameWhatADecisionOnTheSessionNeeds() {
        long started = 1_760_000_000_000L;
        SessionState running = SessionState.closed(SessionSpec.fresh("edge", "/w/edge").withModel("opus")
                        .resumable("e41164bc-160c-4e3e-b43b-3218489da4e1"))
                .withStatus(Status.OPEN, "").withStarted(started).withBranch("main")
                .withUsage(0.0, 84_000L, 200_000L, started);
        String details = SessionRow.details(running, started + (3 * 60 + 5) * 60_000L, ZoneOffset.UTC, "");
        assertThat(details).isEqualTo("""
                Session: edge
                Path: /w/edge
                Branch: main
                Session id: e41164bc-160c-4e3e-b43b-3218489da4e1
                Status: running
                Uptime: 3 h 05 min, since 08:53
                Context: 42% (84.0k of 200.0k tokens)
                Model: opus, effort default
                """);
        assertThat(SessionRow.details(SessionState.closed(SessionSpec.fresh("new", "/w/new")), started, ZoneOffset.UTC, ""))
                .contains("Session id: none yet\n", "Status: closed\n", "Context: unknown until a turn ends\n").doesNotContain("Uptime");
    }

    @Test
    void aRowIsOneLineItsStateIconAndTheName() throws Exception {
        long started = 1_760_000_000_000L;
        SessionState running = SessionState.closed(SessionSpec.fresh("edge", "/w/edge").resumable("e41164bc-160c-4e3e-b43b-3218489da4e1"))
                .withStatus(Status.OPEN, "").withStarted(started).withUsage(0.0, 84_000L, 200_000L, started).withBranch("main");
        assertThat(SessionRow.titleHtml(running, true, false, 0, "")).isEqualTo("<html><nobr><b>edge</b></nobr></html>");
        assertThat(SessionRow.titleHtml(running, false, false, 2, "")).as("its own panel not open: dimmed; approvals waiting")
                .contains("<b style='color:" + Theme.hex(Theme.MUTED) + "'>edge</b>", "⚑2");
        assertThat(SessionRow.titleHtml(running.withReflection(new Reflection(true, false)), true, false, 0, ""))
                .as("the checkpoint due: the context in purple").contains("<span style='color:" + Theme.hex(Theme.THINKING) + "'>42%</span>");
        SessionRow[] row = new SessionRow[1];
        int[] height = new int[3];
        Icon[] icons = new Icon[2];
        SwingUtilities.invokeAndWait(() -> {
            row[0] = new SessionRow();
            row[0].show(running, true, false, 0, false, Color.BLACK, Color.WHITE, "");
            height[0] = row[0].getPreferredSize().height;
            icons[0] = row[0].stateIcon();
            row[0].show(SessionState.closed(SessionSpec.fresh("new", "/w/new")), false, false, 0, false, Color.BLACK, Color.WHITE, "");
            height[1] = row[0].getPreferredSize().height;
            icons[1] = row[0].stateIcon();
            height[2] = new JLabel("<html>x</html>").getPreferredSize().height;
        });
        System.out.println("session row height: running " + height[0] + " px, stopped " + height[1] + " px, a line " + height[2] + " px");
        assertThat(height[0]).isEqualTo(height[1]);
        assertThat(height[0]).as("one line").isLessThan(2 * height[2] + 2);
        assertThat(icons).containsExactly(Icons.get("claude"), Icons.get("claude-off"));
        assertThat(SessionIcons.of(Status.OPENING)).isEqualTo("claude-starting");
        assertThat(SessionIcons.of(Status.FAILED)).isEqualTo("claude-failed");
        assertThat(SessionRow.powerTip(running)).isEqualTo("Connected; click to disconnect (stops the session)");
        assertThat(SessionRow.powerTip(running.withTurns(1, started).withActivity("Bash", started)))
                .isEqualTo("Working: Bash; connected; click to disconnect (stops the session)");
    }

    @Test
    void aConnectionRowIsItsStateIconAndItsNameAsASessionsIs() throws Exception {
        ConnectionState open = ConnectionState.closed(new ConnectionSpec("live", "db.example", 8812, "qdb", "admin", "secret", true))
                .with(Status.OPEN, "");
        assertThat(ConnectionRow.titleHtml(open, false)).isEqualTo("<html><nobr><b>live</b>&nbsp;<span style='color:"
                + Theme.hex(Theme.DANGER) + "'>prod</span></nobr></html>");
        assertThat(ConnectionRow.titleHtml(open, true)).as("the focused panel uses it: the accent colour")
                .contains("<b style='color:" + Theme.hex(Theme.tint().body) + "'>live</b>");
        assertThat(List.of(ConnectionRow.icon(Status.OPEN), ConnectionRow.icon(Status.CLOSED), ConnectionRow.icon(Status.OPENING),
                ConnectionRow.icon(Status.FAILED))).containsExactly("database", "database-off", "database-starting", "database-failed");
        assertThat(ConnectionRow.powerTip(open)).isEqualTo("Connected; click to disconnect");
        assertThat(ConnectionRow.powerTip(open.with(Status.FAILED, "refused"))).isEqualTo("Failed: refused; click to connect");
        assertThat(ConnectionRow.details(open)).as("never the password").doesNotContain("secret")
                .isEqualTo("Connection: live\nHost: db.example:8812\nDatabase: qdb\nUser: admin\nTLS: prefer\nStatus: connected\n"
                        + "Production: Claude's writes are refused\n");
        assertThat(CardFacts.of(open, List.of("main", "notes")).text()).as("the card: facts alone, each once")
                .isEqualTo("State: connected\nDatabase: qdb on db.example:8812\nUser: admin\nTLS: prefer\n"
                        + "Production: yes; Claude's writes are refused\nPanels: main · notes");
        assertThat(CardFacts.of(open, List.of()).title()).isEqualTo("live");

        int[] height = new int[2];
        SwingUtilities.invokeAndWait(() -> {
            height[0] = new ConnectionRow().show(open, false, false, Color.BLACK, Color.WHITE).getPreferredSize().height;
            height[1] = new SessionRow().show(SessionState.closed(SessionSpec.fresh("new", "/w/new")), true, false, 0, false,
                    Color.BLACK, Color.WHITE, "").getPreferredSize().height;
        });
        assertThat(height[0]).as("as tall as a session row").isEqualTo(height[1]);
    }

    @Test
    void aConnectionRowsIconConnectsItsNameBindsAndShowsTheCardAndItsMenuCopies() throws Exception {
        List<String> told = new CopyOnWriteArrayList<>();
        List<Intent> intents = new CopyOnWriteArrayList<>();
        ConnectionState local = ConnectionState.closed(ConnectionSpec.questDbDefaults("local"));
        AppState state = AppState.initial(List.of(new PanelState(new PanelId("p"), "main", "", "")), List.of(local), List.of());
        SessionsSidebar[] s = new SessionsSidebar[1];
        SwingUtilities.invokeAndWait(() -> {
            s[0] = new SessionsSidebar(intents::add, new Dialogs(), () -> {
            });
            s[0].cards(new SessionsSidebar.Cards() {
                @Override
                public void show(String session, JComponent over, Rectangle row) {
                    told.add("session " + session);
                }

                @Override
                public void showConnection(String connection, JComponent over, Rectangle row) {
                    told.add("connection " + connection + " " + row.y);
                }

                @Override
                public void left() {
                    told.add("left");
                }
            });
            s[0].render(state);
        });
        SessionsSidebar sidebar = s[0];
        List<String> copied = new CopyOnWriteArrayList<>();
        sidebar.clipboard = copied::add;
        JList<?> list = find(sidebar.component(), JList.class, l -> "Connections".equals(l.getAccessibleContext().getAccessibleName()));
        SwingUtilities.invokeAndWait(() -> {
            list.setSize(260, 400);
            list.doLayout();
        });
        Rectangle row = cell(list, 0);
        Rectangle[] icon = new Rectangle[1];
        SwingUtilities.invokeAndWait(() -> icon[0] = sidebar.connectionIconBounds(0));
        assertThat(icon[0].width).as("the icon is laid out").isPositive();

        assertThat(on(() -> list.getToolTipText(event(list, icon[0], 0)))).isEqualTo("Disconnected; click to connect");
        assertThat(on(() -> list.getToolTipText(mouse(list, MouseEvent.MOUSE_MOVED, row.x + 80, row.y + 6, 0))))
                .as("over the name no tooltip: the card").isNull();

        click(list, icon[0], 1);
        assertThat(intents).as("a click on the icon connects").containsExactly(new Intent.OpenConnection("local"));
        SwingUtilities.invokeAndWait(() -> sidebar.render(state.withConnections(List.of(local.with(Status.OPEN, "")))));
        click(list, icon[0], 1);
        assertThat(intents).last().as("and disconnects").isEqualTo(new Intent.CloseConnection("local"));
        click(list, new Rectangle(row.x + 70, row.y, 20, row.height), 2);
        assertThat(intents).last().as("a double click on the name binds it").isEqualTo(new Intent.BindConnection(new PanelId("p"), "local"));

        SwingUtilities.invokeAndWait(() -> list.dispatchEvent(mouse(list, MouseEvent.MOUSE_MOVED, row.x + 80, row.y + 6, 0)));
        assertThat(sidebar.cardRow()).isEqualTo("local");
        SwingUtilities.invokeAndWait(sidebar::showCardNow);
        assertThat(told).containsExactly("connection local " + row.y);
        SwingUtilities.invokeAndWait(() -> list.dispatchEvent(event(list, icon[0], 0)));
        assertThat(told).as("onto the icon: its tip, the card told").containsExactly("connection local " + row.y, "left");

        SwingUtilities.invokeAndWait(() -> menuItem(sidebar.connectionMenu(local), "Copy details").doClick());
        assertThat(copied).singleElement().asString().startsWith("Connection: local\nHost: localhost:8812").doesNotContain("quest\n");
    }

    @Test
    void theModelAndEffortChipsSayWhatTheSessionRunsAt() {
        SessionState stopped = SessionState.closed(SessionSpec.fresh("edge", "/w/edge"));
        assertThat(List.of(SessionRow.modelName(stopped), SessionRow.effortName(stopped))).containsExactly("default", "default");
        SessionState running = stopped.withStatus(Status.OPEN, "")
                .withInfo(new SessionInfo("id", "claude-opus-5-5", "/w", "2.1", "default", List.of(), "high"), 0L);
        assertThat(List.of(SessionRow.modelName(running), SessionRow.effortName(running))).as("as reported, and from Claude Code's settings")
                .containsExactly("opus", "high");
        assertThat(SessionRow.modelTip(running)).startsWith("Model: claude-opus-5-5, as the session reported it");
        assertThat(SessionRow.effortTip(running)).isEqualTo("Effort: high, Claude Code's setting for this model; the base each turn starts at,"
                + " which a turn may raise; click to change");
        SessionState set = running.withSpec(running.spec().withEffort("low").withModel("haiku"));
        assertThat(SessionRow.effortName(set)).as("the session's own setting first").isEqualTo("low");
        assertThat(SessionRow.modelName(set)).as("the model it runs until it starts again").isEqualTo("opus");
        assertThat(SessionRow.modelName(set.withStatus(Status.CLOSED, ""))).isEqualTo("haiku");
    }

    @Test
    void aRowSaysWhichCliVersionItRunsAndWhenAnotherSessionRunsANewerOne() {
        long started = 1_760_000_000_000L;
        SessionState older = running("edge", "2.1.302", started);
        SessionState newer = running("docs", "2.1.303", started);
        String newest = SessionRow.newestCli(List.of(older, newer, SessionState.closed(SessionSpec.fresh("new", "/w/new"))));
        assertThat(newest).isEqualTo("2.1.303");

        assertThat(SessionRow.titleHtml(newer, true, false, 0, newest)).doesNotContain("↑");
        assertThat(SessionRow.titleHtml(older, true, false, 0, newest)).as("started before the update: an amber arrow after the name")
                .contains(">edge</b>&nbsp;<span style='color:" + Theme.hex(Theme.ATTENTION) + "'>↑</span>");
        assertThat(SessionRow.details(older, started, ZoneOffset.UTC, newest))
                .contains("Claude Code: 2.1.302, older than the 2.1.303 another session runs; start this one again to use it\n");
        assertThat(SessionRow.details(newer, started, ZoneOffset.UTC, newest)).contains("Claude Code: 2.1.303\n");
        assertThat(SessionRow.titleHtml(older.withStatus(Status.CLOSED, ""), true, false, 0, newest))
                .as("a stopped session runs no Claude Code").doesNotContain("↑");

        assertThat(SessionRow.compareVersions("2.1.303", "2.1.302")).isPositive();
        assertThat(SessionRow.compareVersions("2.2.0", "2.1.303")).isPositive();
        assertThat(SessionRow.compareVersions("2.1.10", "2.1.9")).as("by number, not text").isPositive();
        assertThat(SessionRow.compareVersions("", "2.1.303")).isNegative();
        assertThat(SessionRow.newestCli(List.of())).isEmpty();
    }

    private static SessionState running(String name, String cliVersion, long started) {
        return SessionState.closed(SessionSpec.fresh(name, "/w/" + name)).withStatus(Status.OPEN, "").withStarted(started)
                .withInfo(new SessionInfo("id-" + name, "claude-opus-5-5", "/w/" + name, cliVersion, "auto", List.of()), started);
    }

    private static List<String> names(JList<?> list) throws Exception {
        List<String> out = new CopyOnWriteArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            for (int i = 0; i < list.getModel().getSize(); i++) {
                out.add(((SessionState) list.getModel().getElementAt(i)).name());
            }
        });
        return out;
    }

    private static Rectangle cell(JList<?> list, int index) throws Exception {
        Rectangle[] r = new Rectangle[1];
        SwingUtilities.invokeAndWait(() -> r[0] = list.getCellBounds(index, index));
        return r[0];
    }

    private static MouseEvent mouse(Component c, int id, int x, int y, int modifiers) {
        return new MouseEvent(c, id, System.currentTimeMillis(), modifiers, x, y, id == MouseEvent.MOUSE_PRESSED || id == MouseEvent.MOUSE_RELEASED ? 1 : 0,
                false, id == MouseEvent.MOUSE_MOVED || id == MouseEvent.MOUSE_DRAGGED ? MouseEvent.NOBUTTON : MouseEvent.BUTTON1);
    }

    @Test
    void eachSessionKeepsItsOwnRowSoDrawingAnotherDoesNotParseItsLabelAgain() throws Exception {
        SessionState edge = SessionState.closed(SessionSpec.fresh("edge", "/w/edge"));
        SessionState work = SessionState.closed(SessionSpec.fresh("work", "/w/work")).withStatus(Status.OPEN, "");
        AppState state = AppState.initial(List.of(new PanelState(new PanelId("p"), "main", "", "work")), List.of(), List.of(edge, work));
        SessionsSidebar[] s = new SessionsSidebar[1];
        SwingUtilities.invokeAndWait(() -> {
            s[0] = new SessionsSidebar(intent -> {
            }, new Dialogs(), () -> {
            });
            s[0].render(state);
        });
        JList<?> list = find(s[0].component(), JList.class, l -> "Sessions".equals(l.getAccessibleContext().getAccessibleName()));
        Component first = render(list, 0);
        Component second = render(list, 1);
        assertThat(second).isNotSameAs(first);
        JLabel label = find(second, JLabel.class, l -> l.getText() != null && l.getText().startsWith("<html>"));
        Object parsed = label.getClientProperty(BasicHTML.propertyKey);
        assertThat(parsed).isNotNull();

        render(list, 0);
        assertThat(render(list, 1)).isSameAs(second);
        assertThat(label.getClientProperty(BasicHTML.propertyKey)).as("its HTML parsed once, not again after another row was drawn")
                .isSameAs(parsed);
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

    @Test
    void eachTabsMenuOffersItsOwnActionsThenTheWindowsThenHidingTheSidebar() throws Exception {
        SessionsSidebar[] s = new SessionsSidebar[1];
        SwingUtilities.invokeAndWait(() -> s[0] = new SessionsSidebar(intent -> {
        }, NO_DIALOGS, () -> {
        }));
        SessionsSidebar sidebar = s[0];
        sidebar.onTabMenu((tab, menu) -> menu.add(new JMenuItem("window command " + tab)));
        Map<Integer, List<String>> items = new HashMap<>();
        SwingUtilities.invokeAndWait(() -> {
            for (int tab : new int[] {SessionsSidebar.SESSIONS, SessionsSidebar.CONNECTIONS, SessionsSidebar.FILES}) {
                List<String> texts = new ArrayList<>();
                for (Component c : sidebar.tabMenu(tab).getComponents()) {
                    texts.add(c instanceof JMenuItem m ? m.getText() : "—");
                }
                items.put(tab, texts);
            }
        });
        assertThat(items.get(SessionsSidebar.SESSIONS)).containsExactly("New session…", "Import stored conversations…",
                "Sort: running first, then by name", "window command 0");
        assertThat(items.get(SessionsSidebar.CONNECTIONS)).containsExactly("New connection…", "window command 1");
        assertThat(items.get(SessionsSidebar.FILES)).as("the two configuration windows alone").containsExactly("window command 2");
    }

    /** The tab header named {@code title}: by its accessible name, which stays when a narrow row drops its text. */
    private static JToggleButton header(SessionsSidebar sidebar, String title) {
        return find(sidebar.component(), JToggleButton.class, b -> title.equals(b.getAccessibleContext().getAccessibleName()));
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
