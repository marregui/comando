package dev.marregui.comando.ui.panels;

import com.formdev.flatlaf.FlatDarkLaf;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.ConnectionSpec;
import dev.marregui.comando.core.model.FolderRoot;
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
import javax.swing.LookAndFeel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.plaf.basic.BasicHTML;
import java.awt.Component;
import java.awt.Container;
import java.awt.HeadlessException;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.time.ZoneOffset;
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
            for (String t : List.of("Sessions", "Panels", "Connections", "Files")) {
                JToggleButton h = find(sidebar.component(), JToggleButton.class, b -> t.equals(b.getText()));
                at.put(t, SwingUtilities.convertRectangle(h.getParent(), h.getBounds(), sidebar.component()));
            }
        });
        assertThat(at.get("Panels").y).as("Panels and Files on the top row").isEqualTo(at.get("Files").y)
                .isLessThan(at.get("Sessions").y);
        assertThat(at.get("Sessions").y).as("Sessions and Connections below").isEqualTo(at.get("Connections").y);
        assertThat(at.get("Panels").x).isLessThan(at.get("Files").x).isEqualTo(at.get("Sessions").x);
        assertThat(at.get("Sessions").x).isLessThan(at.get("Connections").x);

        for (int tab : new int[] {SessionsSidebar.SESSIONS, SessionsSidebar.PANELS, SessionsSidebar.CONNECTIONS, SessionsSidebar.FILES}) {
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
        for (int tab : new int[] {SessionsSidebar.SESSIONS, SessionsSidebar.PANELS, SessionsSidebar.CONNECTIONS, SessionsSidebar.FILES}) {
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
                .as("over the text: the details as Copy details copies them").isEqualTo(SessionRow.detailsTip(SessionRow.details(edge, 0L, ZoneOffset.UTC)));

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
        assertThat(rowText(list, 0)).as("running first").contains("· main, notes");
        assertThat(rowText(list, 1)).contains("· headless").doesNotContain("this panel");

        SessionRow row = (SessionRow) render(list, 0);
        assertThat(row.tip(new Rectangle(0, 0, 260, 60), new Point(0, 0))).isEmpty();
        assertThat(SessionRow.Control.values()).as("no edit control: the button above the list edits")
                .containsExactly(SessionRow.Control.POWER, SessionRow.Control.FRESH, SessionRow.Control.CHECKPOINT,
                        SessionRow.Control.COMPACT, SessionRow.Control.CLEAR, SessionRow.Control.MODEL, SessionRow.Control.EFFORT,
                        SessionRow.Control.COPY);
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
        AbstractButton sort = button(sidebar.component(), "Sort the sessions");
        assertThat(names(list)).containsExactly("gamma", "Alpha", "beta");
        assertThat(sort.isEnabled()).as("already sorted").isFalse();

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
        assertThat(sort.isEnabled()).isTrue();

        // the stored order comes back from the reducer and stands, a session starting or not
        SessionState alphaRunning = alpha.withStatus(Status.OPEN, "");
        SwingUtilities.invokeAndWait(() -> sidebar.render(state.withSessions(List.of(beta, gamma, alphaRunning))));
        assertThat(names(list)).containsExactly("beta", "gamma", "Alpha");

        SwingUtilities.invokeAndWait(sort::doClick);
        assertThat(sidebar.manualOrder()).isFalse();
        assertThat(names(list)).containsExactly("Alpha", "gamma", "beta");

        intents.clear();
        SwingUtilities.invokeAndWait(() -> sidebar.drop(0, 1));
        assertThat(intents).as("dropped where it was: nothing moves").isEmpty();
    }

    @Test
    void aHoveredRowCopiesItsDetailsAndARunningOneCompactsOrClears() throws Exception {
        List<Intent> intents = new CopyOnWriteArrayList<>();
        List<String> copied = new CopyOnWriteArrayList<>();
        SessionState work = SessionState.closed(SessionSpec.fresh("work", "/w/work")).withStatus(Status.OPEN, "");
        AppState state = AppState.initial(List.of(new PanelState(new PanelId("p"), "main", "", "work")), List.of(), List.of(work));
        SessionsSidebar[] s = new SessionsSidebar[1];
        SwingUtilities.invokeAndWait(() -> {
            s[0] = new SessionsSidebar(intents::add, new Dialogs(), () -> {
            });
            s[0].clipboard = copied::add;
            s[0].render(state);
        });
        SessionsSidebar sidebar = s[0];
        JList<?> list = find(sidebar.component(), JList.class, l -> "Sessions".equals(l.getAccessibleContext().getAccessibleName()));
        SwingUtilities.invokeAndWait(() -> {
            list.setSize(260, 400);
            list.doLayout();
        });
        Rectangle[] copy = new Rectangle[1];
        SwingUtilities.invokeAndWait(() -> copy[0] = sidebar.controlBounds(0, SessionRow.Control.COPY));
        assertThat(copy[0].width).as("no copy button away from the mouse").isZero();

        Rectangle row = cell(list, 0);
        SwingUtilities.invokeAndWait(() -> list.dispatchEvent(mouse(list, MouseEvent.MOUSE_MOVED, row.x + 60, row.y + 6, 0)));
        Rectangle copyButton = bounds(sidebar, SessionRow.Control.COPY);
        assertThat(copyButton.x + copyButton.width).as("at the right").isGreaterThan(row.x + row.width - 30);
        click(list, copyButton, 1);
        assertThat(copied).singleElement().asString().startsWith("Session: work\nPath: /w/work\n");

        click(list, bounds(sidebar, SessionRow.Control.COMPACT), 1);
        click(list, bounds(sidebar, SessionRow.Control.CLEAR), 1);
        assertThat(intents).containsExactly(new Intent.SessionCommand("work", "/compact"), new Intent.SessionCommand("work", "/clear"));
        Rectangle compact = bounds(sidebar, SessionRow.Control.COMPACT);
        Rectangle model = bounds(sidebar, SessionRow.Control.MODEL);
        assertThat(compact.x).as("the session's controls at the left, its settings at the right").isLessThan(model.x);
        assertThat(model.x + model.width + bounds(sidebar, SessionRow.Control.EFFORT).width).isGreaterThan(row.width - 80);

        SwingUtilities.invokeAndWait(() -> sidebar.render(state.withSessions(List.of(work.withStatus(Status.CLOSED, "")))));
        Rectangle[] stopped = new Rectangle[1];
        SwingUtilities.invokeAndWait(() -> stopped[0] = sidebar.controlBounds(0, SessionRow.Control.CLEAR));
        assertThat(stopped[0].width).as("a stopped session has nothing to compact or clear").isZero();
    }

    @Test
    void theDetailsNameWhatADecisionOnTheSessionNeeds() {
        long started = 1_760_000_000_000L;
        SessionState running = SessionState.closed(SessionSpec.fresh("edge", "/w/edge").withModel("opus")
                        .resumable("e41164bc-160c-4e3e-b43b-3218489da4e1"))
                .withStatus(Status.OPEN, "").withStarted(started).withBranch("main")
                .withUsage(0.0, 84_000L, 200_000L, started);
        String details = SessionRow.details(running, started + (3 * 60 + 5) * 60_000L, ZoneOffset.UTC);
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
        assertThat(SessionRow.details(SessionState.closed(SessionSpec.fresh("new", "/w/new")), started, ZoneOffset.UTC))
                .contains("Session id: none yet\n", "Status: closed\n", "Context: unknown until a turn ends\n").doesNotContain("Uptime");
        assertThat(SessionRow.detailsTip("Session: a<b>\nPath: /w\n")).as("the same lines, as text")
                .isEqualTo("<html>Session: a&lt;b&gt;<br>Path: /w</html>");
    }

    @Test
    void aRowSaysCtxAndUptimeUnderItsFolderAndLeavesTheIdToTheTooltip() {
        long started = 1_760_000_000_000L;
        SessionState running = SessionState.closed(SessionSpec.fresh("edge", "/w/edge").resumable("e41164bc-160c-4e3e-b43b-3218489da4e1"))
                .withStatus(Status.OPEN, "").withStarted(started).withUsage(0.0, 84_000L, 200_000L, started);
        String[] lines = SessionRow.bodyHtml(running, started + 65 * 60_000L).split("<br>");
        assertThat(lines).hasSize(2);
        assertThat(lines[1]).contains(">ctx 42%<", "up 1 h 05 min").doesNotContain("context", "e41164bc");
        String[] fresh = SessionRow.bodyHtml(SessionState.closed(SessionSpec.fresh("new", "/w/new")), started).split("<br>");
        assertThat(fresh).as("the same height before anything is known").hasSize(2);
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
