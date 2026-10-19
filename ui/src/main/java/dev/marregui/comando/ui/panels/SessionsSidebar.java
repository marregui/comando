package dev.marregui.comando.ui.panels;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.FolderRoot;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.RepoStatus;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.port.IntentSink;
import dev.marregui.comando.core.resource.ConnectionKind;
import dev.marregui.comando.core.resource.SessionKind;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.ConnectionState;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.core.view.Clip;
import dev.marregui.comando.core.view.ViewSpec;
import dev.marregui.comando.ui.PanelTexts;
import dev.marregui.comando.ui.kit.action.ToolButton;
import dev.marregui.comando.ui.kit.format.KeyText;
import dev.marregui.comando.ui.kit.icon.Icons;
import dev.marregui.comando.ui.kit.resource.ResourceActions;
import dev.marregui.comando.ui.kit.tabs.TabStrip;
import dev.marregui.comando.ui.kit.text.MarkdownHtml;
import dev.marregui.comando.ui.kit.text.Plain;
import dev.marregui.comando.ui.kit.theme.Theme;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.TransferHandler;
import javax.swing.event.ListDataEvent;
import javax.swing.event.ListDataListener;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Graphics;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * The left column, four tabs in two fixed rows (Panels and Files, then Sessions and Connections): Sessions (every
 * session with its project, branch, state, context, conversation and pending approvals, and controls to connect or
 * disconnect it, compact or clear its conversation and change its model or effort), Panels (comando's own panels, open
 * and closed), Connections, and Files (the folders the user chose, as trees: {@link FilesTab}). Above the panels, the
 * sessions and the connections, the same {@link ResourceActions}: add (and, for sessions, import and sort) on the left;
 * edit, clone and remove of the selected row on the right. Double-click on a session opens it in a panel
 * ({@link Intent.OpenSession}); on a connection, binds it to the focused panel. Sessions are listed running first, then
 * by name, until the user drags one: from then on in the order the user left them, until the sort button. Hidden, the
 * column collapses to a rail whose button shows it again.
 */
public final class SessionsSidebar {

    /** What the sidebar's buttons open. */
    public interface Dialogs {
        /** The session dialog, on a new session, a stored one or an unsaved copy of one. */
        ResourceActions.Editor sessions();

        void importSessions();

        /** The connection dialog, as {@link #sessions()}. */
        ResourceActions.Editor connections();

        /** Shows the connection's schema explorer, as Explore the schema (⌘⇧E) does. */
        void exploreSchema(String connection);

        /** Opens a file in a panel, or brings forward the panel that has it. */
        void openFile(Path file);

        /** As {@link #openFile}, showing the file's text to edit rather than rendered. */
        default void editFile(Path file) {
            openFile(file);
        }
    }

    /** Panel texts kept nowhere, for a sidebar built without the store. */
    private static final PanelTexts NO_TEXTS = new PanelTexts() {
        @Override
        public String load(PanelId panel) {
            return "";
        }

        @Override
        public void changed(PanelId panel, String text) {
        }

        @Override
        public void forget(PanelId panel) {
        }
    };

    private final IntentSink intents;
    private final Dialogs dialogs;
    private final DefaultListModel<SessionState> sessionModel = new DefaultListModel<>();
    private final DefaultListModel<ConnectionState> connectionModel = new DefaultListModel<>();
    /**
     * One row component per session: a row's HTML label is parsed again only when its own text changes, where one shared
     * renderer, filled with each row in turn, parsed every row on every paint of the list.
     */
    private final Map<String, SessionRow> rows = new HashMap<>();
    private final JList<SessionState> sessions = emptyText(new SessionList(sessionModel),
            "No sessions yet. + creates one; the import button brings in stored Claude Code conversations.");
    private final JList<ConnectionState> connections = emptyText(new JList<>(connectionModel),
            "No connections yet. + adds one.");
    public static final int SESSIONS = 0;
    public static final int CONNECTIONS = 1;
    public static final int PANELS = 2;
    public static final int FILES = 3;

    private final ResourceActions<SessionState> sessionActions;
    private final ResourceActions<ConnectionState> connectionActions;
    private final PanelsTab panelsTab;
    private final TabStrip tabs = new TabStrip("Sidebar");
    /** Adds the window's own commands to a tab's menu, before Hide the sidebar. */
    private BiConsumer<Integer, JPopupMenu> moreForTab = (tab, menu) -> {
    };
    private final Runnable toggle;
    private final FilesTab files;
    private final JPanel rail;
    private final JPanel root = new JPanel(new BorderLayout());
    private AppState state;
    /** The sessions are listed in their stored order, which a drag sets, rather than running first, then by name. */
    private boolean manualOrder;
    private final JButton sort;
    /** Where copied details go; the system clipboard, or a test's list. */
    Consumer<String> clipboard = text -> Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);

    /** {@code toggle} hides or shows the sidebar, as ⌘B does; the owner calls {@link #show}. */
    public SessionsSidebar(IntentSink intents, Dialogs dialogs, PanelTexts texts, Runnable toggle) {
        this(intents, dialogs, texts, toggle, ResourceActions.Questions.MODAL);
    }

    SessionsSidebar(IntentSink intents, Dialogs dialogs, Runnable toggle) {
        this(intents, dialogs, NO_TEXTS, toggle, ResourceActions.Questions.MODAL);
    }

    SessionsSidebar(IntentSink intents, Dialogs dialogs, Runnable toggle, ResourceActions.Questions questions) {
        this(intents, dialogs, NO_TEXTS, toggle, questions);
    }

    SessionsSidebar(IntentSink intents, Dialogs dialogs, PanelTexts texts, Runnable toggle, ResourceActions.Questions questions) {
        this.intents = intents;
        this.dialogs = dialogs;
        this.toggle = toggle;
        this.panelsTab = new PanelsTab(intents, texts, questions);
        sessions.getAccessibleContext().setAccessibleName("Sessions");
        connections.getAccessibleContext().setAccessibleName("Connections");
        sessions.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        connections.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        sessions.setCellRenderer((list, s, index, selected, focus) -> row(s).show(s, boundText(s), boundToFocused(s),
                state == null ? 0 : state.pendingApprovals(s.name()), selected, index == ((SessionList) list).hover,
                selected ? list.getSelectionBackground() : list.getBackground(), selected ? list.getSelectionForeground() : list.getForeground(),
                newestCli()));
        // ⌘C, and Edit ▸ Copy, which forwards it, copy the selected session's details
        sessions.setTransferHandler(new TransferHandler() {
            @Override
            public int getSourceActions(JComponent c) {
                return COPY;
            }

            @Override
            protected Transferable createTransferable(JComponent c) {
                SessionState s = sessions.getSelectedValue();
                return s == null ? null : new StringSelection(details(s));
            }
        });
        connections.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
                JLabel l = (JLabel) super.getListCellRendererComponent(list, "", index, selected, focus);
                ConnectionState c = (ConnectionState) value;
                l.setText(connectionHtml(c));
                l.setIcon(Icons.get("database"));
                l.setBorder(BorderFactory.createEmptyBorder(5, 6, 5, 6));
                l.setToolTipText(Plain.text(c.error().isEmpty() ? c.spec().label() : c.error()));
                return l;
            }
        });
        sessions.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                SessionState s = at(sessions, e);
                if (s == null || !SwingUtilities.isLeftMouseButton(e)) {
                    return;
                }
                Optional<SessionRow.Control> control = control(s, e);
                if (control.isPresent()) {
                    if (e.getClickCount() == 1) {
                        act(s, control.get());
                    }
                } else if (e.getClickCount() == 2) {
                    intents.dispatch(new Intent.OpenSession(s.name(), state.focused(), PanelId.random()));
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                popup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                popup(e);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                ((SessionList) sessions).hover(-1);
            }

            private void popup(MouseEvent e) {
                SessionState s = at(sessions, e);
                if (e.isPopupTrigger() && s != null) {
                    sessions.setSelectedValue(s, false);
                    sessionMenu(s).show(sessions, e.getX(), e.getY());
                }
            }
        });
        sessions.addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                SessionState s = at(sessions, e);
                ((SessionList) sessions).hover(s == null ? -1 : sessions.locationToIndex(e.getPoint()));
                sessions.setCursor(s != null && control(s, e).isPresent() ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : null);
            }
        });
        connections.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                ConnectionState c = at(connections, e);
                if (c != null && e.getClickCount() == 2) {
                    intents.dispatch(new Intent.BindConnection(state.focused(), c.name()));
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                popup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                popup(e);
            }

            private void popup(MouseEvent e) {
                ConnectionState c = at(connections, e);
                if (e.isPopupTrigger() && c != null) {
                    connections.setSelectedValue(c, false);
                    connectionMenu(c).show(connections, e.getX(), e.getY());
                }
            }
        });
        files = new FilesTab(intents, dialogs::openFile, dialogs::editFile, questions);
        sort = ToolButton.of("sort", SORT_TIP, () -> order(false));
        sort.getAccessibleContext().setAccessibleName("Sort the sessions");
        sessionActions = new ResourceActions<>(sessions,
                ResourceActions.Rows.of(SessionKind.INSTANCE, SessionState::name, SessionState::status, "Stop", () -> state),
                dialogs.sessions(), name -> intents.dispatch(new Intent.RemoveSession(name)), questions,
                ToolButton.of("import", "Import stored conversations…", dialogs::importSessions), sort);
        order(false);
        connectionActions = new ResourceActions<>(connections,
                ResourceActions.Rows.of(ConnectionKind.INSTANCE, ConnectionState::name, ConnectionState::status, "Disconnect", () -> state),
                dialogs.connections(), name -> intents.dispatch(new Intent.RemoveConnection(name)), questions);
        // one row of icons (Miguel, 2026-10-19): the resources, then the documents; a click moves nothing
        tabs.add("Sessions", "session", "Claude Code sessions; right-click to add, import or sort", section(new JScrollPane(sessions), sessionActions.component()));
        tabs.add("Connections", "database", "databases; right-click to add one", section(new JScrollPane(connections), connectionActions.component()));
        tabs.add("Panels", "text", "every panel, open or closed; double-click one to open it",
                section(new JScrollPane(panelsTab.list()), panelsTab.actions()));
        tabs.add("Files", "folder", "your folders and their git changes; double-click a file to open it",
                section(files.component(), null));
        tabs.trailing(ToolButton.of("more", KeyText.of("Hide the sidebar (⌘B)"), toggle));
        tabs.onMenu((tab, e) -> tabMenu(tab).show(e.getComponent(), e.getX(), e.getY()));
        rail = new JPanel(new BorderLayout());
        rail.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, Theme.CARD_BORDER),
                BorderFactory.createEmptyBorder(4, 1, 0, 1)));
        rail.add(ToolButton.of("more", KeyText.of("Show the sidebar (⌘B)"), toggle), BorderLayout.NORTH);
        rail.setVisible(false);
        root.add(tabs.component(), BorderLayout.CENTER);
        root.add(rail, BorderLayout.WEST);
    }

    /** The tabs, or the rail while hidden; the owner sizes it to {@link #railWidth} then. */
    public JComponent component() {
        return root;
    }

    public boolean isShown() {
        return tabs.component().isVisible();
    }

    /** Shows the tabs, or hides them behind the rail. */
    public void show(boolean shown) {
        tabs.component().setVisible(shown);
        rail.setVisible(!shown);
        root.revalidate();
        root.repaint();
    }

    /** The tab in front: {@link #SESSIONS}, {@link #PANELS}, {@link #CONNECTIONS} or {@link #FILES}. */
    public int selectedTab() {
        return tabs.selectedIndex();
    }

    public void selectTab(int tab) {
        tabs.select(tab);
    }

    /** A folder of the Files tab as read; a later read keeps the tree as the user left it. */
    public void showFolder(FolderRoot folder, ViewSpec.TreeNode tree) {
        files.show(folder, tree);
    }

    /** The watched git repositories as read: the Files tab colours their files. */
    public void showRepos(List<RepoStatus> repos) {
        files.showRepos(repos);
    }

    /** Adds the window's own commands to each tab's menu: {@code more} gets the tab and the menu built so far. */
    public void onTabMenu(BiConsumer<Integer, JPopupMenu> more) {
        moreForTab = more;
    }

    /**
     * What a tab's header offers on a right click: the tab's own actions (a new session or connection, importing
     * conversations, sorting the sessions; closing every folder), the window's commands for it (with their keys), then
     * hiding the sidebar.
     */
    JPopupMenu tabMenu(int tab) {
        JPopupMenu menu = new JPopupMenu();
        switch (tab) {
            case SESSIONS -> {
                menu.add(menuItem("New session…", "add", sessionActions::addNew));
                menu.add(menuItem("Import stored conversations…", "import", dialogs::importSessions));
                menu.add(menuItem("Sort: running first, then by name", "sort", () -> order(false)));
            }
            case CONNECTIONS -> menu.add(menuItem("New connection…", "add", connectionActions::addNew));
            case FILES -> menu.add(menuItem("Close every folder", "tree", files::collapseAll));
            default -> {
            }
        }
        moreForTab.accept(tab, menu);
        menu.addSeparator();
        menu.add(menuItem(KeyText.of("Hide the sidebar (⌘B)"), "more", toggle));
        return menu;
    }

    private static JMenuItem menuItem(String text, String icon, Runnable r) {
        JMenuItem i = new JMenuItem(text, Icons.get(icon));
        i.addActionListener(a -> r.run());
        return i;
    }

    /** Puts the keyboard in the Files tab's filter. */
    public void focusFilesFilter() {
        files.filter().requestFocusInWindow();
    }

    /** The width the hidden sidebar takes: the rail's. */
    public int railWidth() {
        return rail.getPreferredSize().width;
    }

    /** The newest Claude Code version the rows were last painted against. */
    private String paintedNewestCli = "";

    public void render(AppState s) {
        state = s;
        sync(sessionModel, ordered(s.sessions(), manualOrder), sessions);
        // a session reporting a newer CLI version changes the other rows too: theirs is older now
        String newest = newestCli();
        if (!newest.equals(paintedNewestCli)) {
            paintedNewestCli = newest;
            sessions.repaint();
        }
        rows.keySet().retainAll(s.sessions().stream().map(SessionState::name).toList());
        sync(connectionModel, s.connections(), connections);
        panelsTab.render(s);
        files.render(s);
    }

    private static final String SORT_TIP = "Sort: running first, then by name";

    /** Whether the user's order stands (a drag set it) rather than running first, then by name; kept across restarts by the owner. */
    public boolean manualOrder() {
        return manualOrder;
    }

    /** Lists the sessions in their stored order, or running first, then by name. */
    public void manualOrder(boolean manual) {
        order(manual);
    }

    private void order(boolean manual) {
        manualOrder = manual;
        sort.setEnabled(manual);
        sort.setToolTipText(manual ? SORT_TIP + "; drag to order by hand again" : "Sorted: running first, then by name; drag to reorder");
        if (state != null) {
            sync(sessionModel, ordered(state.sessions(), manual), sessions);
        }
    }

    /** {@code stored} as the list shows it: as stored, or running first, then by name. */
    static List<SessionState> ordered(List<SessionState> stored, boolean manual) {
        return manual ? stored : stored.stream()
                .sorted(Comparator.comparing((SessionState x) -> !x.status().isUsable())
                        .thenComparing(SessionState::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /**
     * The session at {@code from} dropped into the gap before {@code gap} (the list's size for after the last): the list
     * as shown, with that move, becomes the stored order, and the user's order stands from then on.
     */
    void drop(int from, int gap) {
        if (from < 0 || from >= sessionModel.size() || gap < 0 || gap > sessionModel.size() || gap == from || gap == from + 1) {
            return;
        }
        List<SessionState> shown = new ArrayList<>(Collections.list(sessionModel.elements()));
        SessionState moved = shown.remove(from);
        shown.add(gap > from ? gap - 1 : gap, moved);
        order(true);
        // shown at once; the stored order follows when the state comes back
        sync(sessionModel, shown, sessions);
        sessions.setSelectedValue(moved, true);
        intents.dispatch(new Intent.OrderSessions(shown.stream().map(SessionState::name).toList()));
    }

    /** What a session's copy puts on the clipboard. */
    private String details(SessionState s) {
        return SessionRow.details(s, System.currentTimeMillis(), ZoneId.systemDefault(), newestCli());
    }

    /** The newest Claude Code version a running session reported; empty before any has. */
    private String newestCli() {
        return state == null ? "" : SessionRow.newestCli(state.sessions());
    }

    /** The uptimes the session rows last showed, so the shell's one-second tick repaints them only when one moved on. */
    private String uptimes = "";

    /** Once a second, from the shell: the running sessions' uptimes, which move on by the minute. */
    public void tick() {
        if (state == null) {
            return;
        }
        long now = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder();
        for (SessionState x : state.sessions()) {
            sb.append(SessionRow.uptimeText(x, now)).append('\n');
        }
        String next = sb.toString();
        if (!next.equals(uptimes)) {
            uptimes = next;
            sessions.repaint();
        }
    }

    /** The row control under a mouse event on {@code s}'s cell, if any. */
    private Optional<SessionRow.Control> control(SessionState s, MouseEvent e) {
        int i = sessions.locationToIndex(e.getPoint());
        Rectangle cell = sessions.getCellBounds(i, i);
        return layOutRow(s, i).hit(cell, e.getPoint());
    }

    private SessionRow row(SessionState s) {
        return rows.computeIfAbsent(s.name(), name -> new SessionRow());
    }

    /** The row at {@code index}, filled as it was painted, so its controls sit where they were drawn. */
    private SessionRow layOutRow(SessionState s, int index) {
        return (SessionRow) sessions.getCellRenderer().getListCellRendererComponent(sessions, s, index, sessions.isSelectedIndex(index), false);
    }

    /** Where a row's control is, in the list's coordinates; for tests. */
    Rectangle controlBounds(int index, SessionRow.Control control) {
        return layOutRow(sessionModel.get(index), index).bounds(sessions.getCellBounds(index, index), control);
    }

    /** What a click on a row's control does. */
    private void act(SessionState s, SessionRow.Control control) {
        int i = sessionModel.indexOf(s);
        Rectangle cell = sessions.getCellBounds(i, i);
        switch (control) {
            case POWER -> intents.dispatch(s.status().isUsable() ? new Intent.StopSession(s.name()) : new Intent.StartSession(s.name()));
            case FRESH -> intents.dispatch(new Intent.StartFresh(s.name()));
            case CHECKPOINT -> intents.dispatch(new Intent.Checkpoint(s.name()));
            case COMPACT -> intents.dispatch(new Intent.SessionCommand(s.name(), SessionSpec.COMPACT_COMMAND));
            case CLEAR -> intents.dispatch(new Intent.SessionCommand(s.name(), SessionSpec.CLEAR_COMMAND));
            case COPY -> clipboard.accept(details(s));
            case MODEL, EFFORT -> {
                Rectangle at = layOutRow(s, i).bounds(cell, control);
                JPopupMenu m = new JPopupMenu();
                (control == SessionRow.Control.MODEL ? modelItems(s) : effortItems(s)).forEach(m::add);
                m.show(sessions, at.x, at.y + at.height);
            }
        }
    }

    /** One item per model, the session's own checked; a choice applies now to a running session and is saved. */
    List<JMenuItem> modelItems(SessionState s) {
        String current = SessionRow.modelName(s);
        return SessionSpec.MODELS.stream().map(m -> choice(m, m.equals(current), () -> intents.dispatch(new Intent.SetSessionModel(s.name(), m)))).toList();
    }

    List<JMenuItem> effortItems(SessionState s) {
        String current = SessionRow.effortName(s);
        return SessionSpec.EFFORTS.stream().map(e -> choice(e, e.equals(current), () -> intents.dispatch(new Intent.SetSessionEffort(s.name(), e)))).toList();
    }

    private static JMenuItem choice(String text, boolean current, Runnable r) {
        JCheckBoxMenuItem i = new JCheckBoxMenuItem(text, current);
        i.addActionListener(e -> r.run());
        return i;
    }

    /**
     * The session list: its tooltips name the control under the mouse, else give the session's details as copied; it
     * knows the row under the mouse, which shows its copy button; and a row dragged by its text moves, a line marking
     * where it will land.
     */
    private final class SessionList extends JList<SessionState> {
        /** How far the mouse moves with the button down before a press becomes a drag. */
        private static final int DRAG_SLOP = 5;
        int hover = -1;
        private int pressed = -1;
        private Point pressedAt;
        /** The gap a drag would drop into, -1 while no drag runs. */
        private int gap = -1;
        /** A drop just ended: the click the platform may send for the same press and release is not a click on a control. */
        private boolean dropped;

        SessionList(DefaultListModel<SessionState> model) {
            super(model);
            setToolTipText("");
        }

        /** As wide as the tab: a row's lines clip at its edge, and its right-hand controls stay in view. */
        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        void hover(int index) {
            if (index != hover) {
                repaintRow(hover);
                hover = index;
                repaintRow(hover);
            }
        }

        private void repaintRow(int index) {
            if (index >= 0 && index < getModel().getSize()) {
                Rectangle r = getCellBounds(index, index);
                if (r != null) {
                    repaint(r);
                }
            }
        }

        @Override
        protected void processMouseEvent(MouseEvent e) {
            if (e.getID() == MouseEvent.MOUSE_CLICKED && dropped) {
                dropped = false;
                e.consume();
                return;
            }
            if (e.getID() == MouseEvent.MOUSE_PRESSED) {
                dropped = false;
                SessionState s = at(this, e);
                boolean onText = s != null && SwingUtilities.isLeftMouseButton(e) && !e.isPopupTrigger() && control(s, e).isEmpty();
                pressed = onText ? locationToIndex(e.getPoint()) : -1;
                pressedAt = e.getPoint();
            } else if (e.getID() == MouseEvent.MOUSE_RELEASED) {
                int from = pressed;
                int to = gap;
                pressed = -1;
                if (to >= 0) {
                    endDrag();
                    dropped = true;
                    drop(from, to);
                }
            }
            // a release reaches the list after a drop too: its press left the selection adjusting
            super.processMouseEvent(e);
        }

        @Override
        protected void processMouseMotionEvent(MouseEvent e) {
            if (e.getID() == MouseEvent.MOUSE_DRAGGED && pressed >= 0) {
                if (gap < 0 && e.getPoint().distance(pressedAt) < DRAG_SLOP) {
                    return;
                }
                int next = gapAt(e.getPoint().y);
                if (next != gap) {
                    gap = next;
                    setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                    repaint();
                }
                // the list's own drag would move the selection along: the drag is the row's
                return;
            }
            super.processMouseMotionEvent(e);
        }

        private void endDrag() {
            gap = -1;
            setCursor(null);
            repaint();
        }

        /** The gap nearest {@code y}: before the row whose upper half holds it, after the last row below them all. */
        private int gapAt(int y) {
            int n = getModel().getSize();
            for (int i = 0; i < n; i++) {
                Rectangle r = getCellBounds(i, i);
                if (y < r.y + r.height / 2) {
                    return i;
                }
            }
            return n;
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            int n = getModel().getSize();
            if (gap < 0 || n == 0) {
                return;
            }
            Rectangle r = getCellBounds(Math.min(gap, n - 1), Math.min(gap, n - 1));
            int y = gap < n ? r.y : r.y + r.height;
            g.setColor(Theme.ACCENT);
            g.fillRect(0, Math.max(0, y - 1), getWidth(), 2);
        }

        @Override
        public String getToolTipText(MouseEvent e) {
            int i = locationToIndex(e.getPoint());
            if (i < 0 || !getCellBounds(i, i).contains(e.getPoint())) {
                return null;
            }
            SessionState s = getModel().getElementAt(i);
            // over a control its tip; over the text what Copy details copies, line for line
            return layOutRow(s, i).tip(getCellBounds(i, i), e.getPoint()).map(Plain::text)
                    .orElseGet(() -> SessionRow.detailsTip(details(s)));
        }
    }

    private static <T> void sync(DefaultListModel<T> model, List<T> items, JList<T> list) {
        T selected = list.getSelectedValue();
        if (model.size() != items.size()) {
            model.clear();
            model.addAll(items);
        } else {
            for (int i = 0; i < items.size(); i++) {
                if (!Objects.equals(model.get(i), items.get(i))) {
                    model.set(i, items.get(i));
                }
            }
        }
        if (selected != null) {
            for (int i = 0; i < model.size(); i++) {
                if (name(model.get(i)).equals(name(selected))) {
                    list.setSelectedIndex(i);
                    break;
                }
            }
        }
    }

    private static String name(Object o) {
        return o instanceof SessionState s ? s.name() : o instanceof ConnectionState c ? c.name() : String.valueOf(o);
    }

    private String connectionHtml(ConnectionState c) {
        String muted = Theme.hex(Theme.MUTED);
        String dot = "<span style='color:" + Theme.hex(EditorPanel.color(c.status())) + "'>" + EditorPanel.dot(c.status()) + "</span>";
        String prod = c.spec().prod() ? "&nbsp;<span style='color:" + Theme.hex(Theme.DANGER) + "'>prod</span>" : "";
        return "<html>" + dot + "&nbsp;<b>" + MarkdownHtml.escape(c.name()) + "</b>" + prod
                + "<br><span style='color:" + Theme.hex(Theme.MUTED_STRONG) + "'>" + MarkdownHtml.escape(Clip.keepEnd(c.spec().label(), 36)) + "</span></html>";
    }

    private JPopupMenu sessionMenu(SessionState s) {
        JPopupMenu m = new JPopupMenu();
        m.add(item("Open in a panel", "claude", () -> intents.dispatch(new Intent.OpenSession(s.name(), state.focused(), PanelId.random()))));
        m.addSeparator();
        if (s.status().isUsable()) {
            m.add(item("Stop", "stop", () -> intents.dispatch(new Intent.StopSession(s.name()))));
            if (s.isBusy()) {
                m.add(item("Interrupt the turn", "interrupt", () -> intents.dispatch(new Intent.InterruptSession(s.name()))));
            }
        } else {
            m.add(item("Start", "play", () -> intents.dispatch(new Intent.StartSession(s.name()))));
        }
        if (s.status().isUsable()) {
            m.add(item("Compact the conversation", "compact", () -> intents.dispatch(new Intent.SessionCommand(s.name(), SessionSpec.COMPACT_COMMAND))));
            m.add(item("Clear the conversation", "clear-conversation", () -> intents.dispatch(new Intent.SessionCommand(s.name(), SessionSpec.CLEAR_COMMAND))));
        }
        JMenu models = new JMenu("Model");
        modelItems(s).forEach(models::add);
        m.add(models);
        JMenu efforts = new JMenu("Effort");
        effortItems(s).forEach(efforts::add);
        m.add(efforts);
        m.addSeparator();
        m.add(item("Copy details", "copy", () -> clipboard.accept(details(s))));
        m.addSeparator();
        m.add(item("Edit…", "edit", () -> dialogs.sessions().edit(s.name())));
        m.add(item("Clone…", "copy", () -> dialogs.sessions().cloneOf(s.name())));
        m.add(item("Remove…", "close", () -> sessionActions.remove(s)));
        return m;
    }

    private JPopupMenu connectionMenu(ConnectionState c) {
        JPopupMenu m = new JPopupMenu();
        m.add(item("Bind to the focused panel", "database", () -> intents.dispatch(new Intent.BindConnection(state.focused(), c.name()))));
        m.addSeparator();
        if (c.status().isUsable()) {
            m.add(item("Disconnect", "unplug", () -> intents.dispatch(new Intent.CloseConnection(c.name()))));
        } else {
            m.add(item("Connect", "plug", () -> intents.dispatch(new Intent.OpenConnection(c.name()))));
        }
        m.add(item("Test", "check", () -> intents.dispatch(new Intent.TestConnection(c.spec()))));
        m.add(item("Explore the schema", "tree", () -> dialogs.exploreSchema(c.name())));
        m.addSeparator();
        m.add(item("Edit…", "edit", () -> dialogs.connections().edit(c.name())));
        m.add(item("Clone…", "copy", () -> dialogs.connections().cloneOf(c.name())));
        m.add(item("Remove…", "close", () -> connectionActions.remove(c)));
        return m;
    }

    /** A list that says what to do while it is empty. */
    private static <T> JList<T> emptyText(JList<T> list, String text) {
        JLabel hint = new JLabel("<html><div style='width:180px'>" + MarkdownHtml.escape(text) + "</div></html>");
        hint.setForeground(Theme.MUTED_STRONG);
        hint.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        JPanel overlay = new JPanel(new BorderLayout());
        overlay.setOpaque(false);
        overlay.add(hint, BorderLayout.NORTH);
        list.setLayout(new BorderLayout());
        list.add(overlay, BorderLayout.CENTER);
        list.getModel().addListDataListener(new ListDataListener() {
            @Override
            public void intervalAdded(ListDataEvent e) {
                overlay.setVisible(list.getModel().getSize() == 0);
            }

            @Override
            public void intervalRemoved(ListDataEvent e) {
                overlay.setVisible(list.getModel().getSize() == 0);
            }

            @Override
            public void contentsChanged(ListDataEvent e) {
                overlay.setVisible(list.getModel().getSize() == 0);
            }
        });
        return list;
    }

    /** Where a session is bound: its panel's title, "(closed)" after it when closed; "headless" when it has none. */
    private String boundText(SessionState s) {
        if (state == null) {
            return "headless";
        }
        List<String> titles = state.panels().stream().filter(p -> p.session().equals(s.name()))
                .sorted((a, b) -> Boolean.compare(b.id().equals(state.focused()), a.id().equals(state.focused())))
                .map(p -> p.title()).toList();
        if (!titles.isEmpty()) {
            return String.join(", ", titles);
        }
        return state.closedPanelOfSession(s.name()).map(p -> p.title() + " (closed)").orElse("headless");
    }

    private boolean boundToFocused(SessionState s) {
        return state != null && state.focusedPanel().session().equals(s.name());
    }

    private static <T> T at(JList<T> list, MouseEvent e) {
        int i = list.locationToIndex(e.getPoint());
        return i >= 0 && list.getCellBounds(i, i).contains(e.getPoint()) ? list.getModel().getElementAt(i) : null;
    }

    /** A tab's content: its bar of buttons at the top, when it has one, then the content. */
    private static JPanel section(JComponent content, JComponent actions) {
        if (content instanceof JScrollPane sp) {
            sp.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
            sp.setBorder(null);
        }
        JPanel p = new JPanel(new BorderLayout());
        if (actions != null) {
            p.add(actions, BorderLayout.NORTH);
        }
        p.add(content, BorderLayout.CENTER);
        return p;
    }

    private static JMenuItem item(String text, String icon, Runnable r) {
        JMenuItem i = new JMenuItem(text, Icons.get(icon));
        i.addActionListener(e -> r.run());
        return i;
    }
}
