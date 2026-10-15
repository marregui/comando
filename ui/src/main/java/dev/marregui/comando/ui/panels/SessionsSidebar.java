package dev.marregui.comando.ui.panels;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.port.IntentSink;
import dev.marregui.comando.core.resource.ConnectionKind;
import dev.marregui.comando.core.resource.SessionKind;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.ConnectionState;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.core.view.ViewSpec;
import dev.marregui.comando.ui.kit.action.ToolButton;
import dev.marregui.comando.ui.kit.icon.Icons;
import dev.marregui.comando.ui.kit.resource.ResourceActions;
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
import javax.swing.JTabbedPane;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.ListDataEvent;
import javax.swing.event.ListDataListener;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.FlowLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The left column, three tabs: Sessions (every session with its project, branch, state and pending approvals, and
 * controls to connect or disconnect it and change its model or effort), Connections, and Scratchpad (the scratchpad
 * as a tree). Above the sessions and the connections, the same {@link ResourceActions}: add (and, for sessions,
 * import) on the left; edit, clone and remove of the selected row on the right. Double-click on a session or a connection binds it to the focused panel. Hidden, the column
 * collapses to a rail whose button shows it again.
 */
public final class SessionsSidebar {

    /** What the sidebar's buttons open. */
    public interface Dialogs {
        /** The session dialog, on a new session, a stored one or an unsaved copy of one. */
        ResourceActions.Editor sessions();

        void importSessions();

        /** The connection dialog, as {@link #sessions()}. */
        ResourceActions.Editor connections();

        /** Shows the connection's schema explorer, as Panel ▸ Connection ▸ Explore the schema does. */
        void exploreSchema(String connection);

        /** Opens a file in a panel, or brings forward the panel that has it. */
        void openFile(Path file);
    }

    private final IntentSink intents;
    private final Dialogs dialogs;
    private final DefaultListModel<SessionState> sessionModel = new DefaultListModel<>();
    private final DefaultListModel<ConnectionState> connectionModel = new DefaultListModel<>();
    private final SessionRow sessionRow = new SessionRow();
    private final JList<SessionState> sessions = emptyText(new SessionList(sessionModel),
            "No sessions yet. + creates one; the import button brings in stored Claude Code conversations.");
    private final JList<ConnectionState> connections = emptyText(new JList<>(connectionModel),
            "No connections yet. + adds one.");
    public static final int SESSIONS = 0;
    public static final int CONNECTIONS = 1;
    public static final int SCRATCHPAD = 2;

    private final ResourceActions<SessionState> sessionActions;
    private final ResourceActions<ConnectionState> connectionActions;
    private final JTabbedPane tabs = new JTabbedPane();
    private final ScratchpadTab scratchpad;
    private final JPanel rail;
    private final JPanel root = new JPanel(new BorderLayout());
    private AppState state;
    /** The models and efforts the row's menus offer, as the palette and the session dialog do. */
    static final List<String> MODELS = List.of("fable", "opus", "sonnet", "haiku");
    static final List<String> EFFORTS = List.of("low", "medium", "high", "xhigh", "max");

    /** {@code toggle} hides or shows the sidebar, as View ▸ Sidebar does; the owner calls {@link #show}. */
    public SessionsSidebar(IntentSink intents, Dialogs dialogs, Runnable toggle) {
        this(intents, dialogs, toggle, ResourceActions.Questions.MODAL);
    }

    SessionsSidebar(IntentSink intents, Dialogs dialogs, Runnable toggle, ResourceActions.Questions questions) {
        this.intents = intents;
        this.dialogs = dialogs;
        sessions.getAccessibleContext().setAccessibleName("Sessions");
        connections.getAccessibleContext().setAccessibleName("Connections");
        sessions.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        connections.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        sessions.setCellRenderer((list, s, index, selected, focus) -> sessionRow.show(s, boundText(s), boundToFocused(s),
                state == null ? 0 : state.pendingApprovals(s.name()), selected,
                selected ? list.getSelectionBackground() : list.getBackground(), selected ? list.getSelectionForeground() : list.getForeground()));
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
                    intents.dispatch(new Intent.BindSession(state.focused(), s.name()));
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
        scratchpad = new ScratchpadTab(dialogs::openFile);
        sessionActions = new ResourceActions<>(sessions,
                new ResourceActions.Rows<>(SessionKind.INSTANCE, SessionState::name, SessionState::status, "Stop"),
                () -> state, dialogs.sessions(), name -> intents.dispatch(new Intent.RemoveSession(name)), questions,
                ToolButton.of("import", "Import stored conversations…", dialogs::importSessions));
        connectionActions = new ResourceActions<>(connections,
                new ResourceActions.Rows<>(ConnectionKind.INSTANCE, ConnectionState::name, ConnectionState::status, "Disconnect"),
                () -> state, dialogs.connections(), name -> intents.dispatch(new Intent.RemoveConnection(name)), questions);
        tabs.addTab("Sessions", section(new JScrollPane(sessions), sessionActions.component()));
        tabs.addTab("Connections", section(new JScrollPane(connections), connectionActions.component()));
        JPanel refresh = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        refresh.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        refresh.add(ToolButton.of("refresh", "Read the scratchpad again", () -> intents.dispatch(new Intent.BrowseScratchpad())));
        tabs.addTab("Scratchpad", section(scratchpad.component(), refresh));
        tabs.setToolTipTextAt(SCRATCHPAD, "~/.claude/SCRATCHPAD; click a file to open it in a panel");
        // the hide button ends the tab strip, so it shows whichever tab is in front
        tabs.putClientProperty("JTabbedPane.trailingComponent", ToolButton.of("more", "Hide the sidebar (⌘B)", toggle));
        // one row that never wraps: a wrapped strip moves the selected tab's row, so tabs would jump on a click
        tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        tabs.putClientProperty("JTabbedPane.scrollButtonsPolicy", "asNeededSingle");
        tabs.putClientProperty("JTabbedPane.tabInsets", new Insets(4, 6, 4, 6));
        tabs.putClientProperty("JTabbedPane.tabAreaInsets", new Insets(0, 2, 0, 0));
        tabs.getAccessibleContext().setAccessibleName("Sidebar");
        rail = new JPanel(new BorderLayout());
        rail.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, Theme.CARD_BORDER),
                BorderFactory.createEmptyBorder(4, 1, 0, 1)));
        rail.add(ToolButton.of("more", "Show the sidebar (⌘B)", toggle), BorderLayout.NORTH);
        rail.setVisible(false);
        root.add(tabs, BorderLayout.CENTER);
        root.add(rail, BorderLayout.WEST);
    }

    /** The tabs, or the rail while hidden; the owner sizes it to {@link #railWidth} then. */
    public JComponent component() {
        return root;
    }

    public boolean isShown() {
        return tabs.isVisible();
    }

    /** Shows the tabs, or hides them behind the rail. */
    public void show(boolean shown) {
        tabs.setVisible(shown);
        rail.setVisible(!shown);
        root.revalidate();
        root.repaint();
    }

    /** The tab in front: {@link #SESSIONS}, {@link #CONNECTIONS} or {@link #SCRATCHPAD}. */
    public int selectedTab() {
        return tabs.getSelectedIndex();
    }

    public void selectTab(int tab) {
        if (tab >= 0 && tab < tabs.getTabCount()) {
            tabs.setSelectedIndex(tab);
        }
    }

    /** The scratchpad as read; a refresh keeps the tree as the user left it. */
    public void showScratchpad(ViewSpec.TreeNode root) {
        scratchpad.show(root);
    }

    /** The width the hidden sidebar takes: the rail's. */
    public int railWidth() {
        return rail.getPreferredSize().width;
    }

    public void render(AppState s) {
        state = s;
        sync(sessionModel, s.sessions(), sessions);
        sync(connectionModel, s.connections(), connections);
    }

    /** The row control under a mouse event on {@code s}'s cell, if any. */
    private Optional<SessionRow.Control> control(SessionState s, MouseEvent e) {
        int i = sessions.locationToIndex(e.getPoint());
        Rectangle cell = sessions.getCellBounds(i, i);
        layOutRow(s, i);
        return sessionRow.hit(cell, e.getPoint());
    }

    /** Fills the shared renderer with the row at {@code index}, so its controls sit as they were painted. */
    private void layOutRow(SessionState s, int index) {
        sessions.getCellRenderer().getListCellRendererComponent(sessions, s, index, sessions.isSelectedIndex(index), false);
    }

    /** Where a row's control is, in the list's coordinates; for tests. */
    Rectangle controlBounds(int index, SessionRow.Control control) {
        layOutRow(sessionModel.get(index), index);
        return sessionRow.bounds(sessions.getCellBounds(index, index), control);
    }

    /** What a click on a row's control does. */
    private void act(SessionState s, SessionRow.Control control) {
        int i = sessionModel.indexOf(s);
        Rectangle cell = sessions.getCellBounds(i, i);
        switch (control) {
            case POWER -> intents.dispatch(s.status().isUsable() ? new Intent.StopSession(s.name()) : new Intent.StartSession(s.name()));
            case MODEL, EFFORT -> {
                layOutRow(s, i);
                Rectangle at = sessionRow.bounds(cell, control);
                JPopupMenu m = new JPopupMenu();
                (control == SessionRow.Control.MODEL ? modelItems(s) : effortItems(s)).forEach(m::add);
                m.show(sessions, at.x, at.y + at.height);
            }
        }
    }

    /** One item per model, the session's own checked; a choice applies now to a running session and is saved. */
    List<JMenuItem> modelItems(SessionState s) {
        String current = SessionRow.modelName(s);
        return MODELS.stream().map(m -> choice(m, m.equals(current), () -> intents.dispatch(new Intent.SetSessionModel(s.name(), m)))).toList();
    }

    List<JMenuItem> effortItems(SessionState s) {
        String current = SessionRow.effortName(s);
        return EFFORTS.stream().map(e -> choice(e, e.equals(current), () -> intents.dispatch(new Intent.SetSessionEffort(s.name(), e)))).toList();
    }

    private static JMenuItem choice(String text, boolean current, Runnable r) {
        JCheckBoxMenuItem i = new JCheckBoxMenuItem(text, current);
        i.addActionListener(e -> r.run());
        return i;
    }

    /** The session list, whose tooltips name the control under the mouse, else the session's folder and conversation. */
    private final class SessionList extends JList<SessionState> {
        SessionList(DefaultListModel<SessionState> model) {
            super(model);
            setToolTipText("");
        }

        @Override
        public String getToolTipText(MouseEvent e) {
            int i = locationToIndex(e.getPoint());
            if (i < 0 || !getCellBounds(i, i).contains(e.getPoint())) {
                return null;
            }
            SessionState s = getModel().getElementAt(i);
            layOutRow(s, i);
            return Plain.text(sessionRow.tip(getCellBounds(i, i), e.getPoint())
                    .orElse(s.spec().workDir() + (s.spec().claudeSessionId().isEmpty() ? "" : " · " + s.spec().claudeSessionId())));
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
                + "<br><span style='color:" + Theme.hex(Theme.MUTED_STRONG) + "'>" + MarkdownHtml.escape(clip(c.spec().label(), 36)) + "</span></html>";
    }

    private JPopupMenu sessionMenu(SessionState s) {
        JPopupMenu m = new JPopupMenu();
        m.add(item("Bind to the focused panel", "claude", () -> intents.dispatch(new Intent.BindSession(state.focused(), s.name()))));
        m.addSeparator();
        if (s.status().isUsable()) {
            m.add(item("Stop", "stop", () -> intents.dispatch(new Intent.StopSession(s.name()))));
            if (s.isBusy()) {
                m.add(item("Interrupt the turn", "interrupt", () -> intents.dispatch(new Intent.InterruptSession(s.name()))));
            }
        } else {
            m.add(item("Start", "play", () -> intents.dispatch(new Intent.StartSession(s.name()))));
        }
        JMenu models = new JMenu("Model");
        modelItems(s).forEach(models::add);
        m.add(models);
        JMenu efforts = new JMenu("Effort");
        effortItems(s).forEach(efforts::add);
        m.add(efforts);
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

    /** Where a session is bound: the titles of its panels, the focused one first; "headless" when it has none. */
    private String boundText(SessionState s) {
        if (state == null) {
            return "headless";
        }
        List<String> titles = state.panels().stream().filter(p -> p.session().equals(s.name()))
                .sorted((a, b) -> Boolean.compare(b.id().equals(state.focused()), a.id().equals(state.focused())))
                .map(p -> p.title()).toList();
        return titles.isEmpty() ? "headless" : String.join(", ", titles);
    }

    private boolean boundToFocused(SessionState s) {
        return state != null && state.focusedPanel().session().equals(s.name());
    }

    private static <T> T at(JList<T> list, MouseEvent e) {
        int i = list.locationToIndex(e.getPoint());
        return i >= 0 && list.getCellBounds(i, i).contains(e.getPoint()) ? list.getModel().getElementAt(i) : null;
    }

    /** A tab's content: its bar of buttons at the top, then the content. */
    private static JPanel section(JComponent content, JComponent actions) {
        if (content instanceof JScrollPane sp) {
            sp.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
            sp.setBorder(null);
        }
        JPanel p = new JPanel(new BorderLayout());
        p.add(actions, BorderLayout.NORTH);
        p.add(content, BorderLayout.CENTER);
        return p;
    }

    private static JMenuItem item(String text, String icon, Runnable r) {
        JMenuItem i = new JMenuItem(text, Icons.get(icon));
        i.addActionListener(e -> r.run());
        return i;
    }

    private static String clip(String s, int max) {
        return s.length() <= max ? s : "…" + s.substring(s.length() - max + 1);
    }
}
