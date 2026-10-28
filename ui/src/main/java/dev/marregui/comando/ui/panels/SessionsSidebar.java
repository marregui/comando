package dev.marregui.comando.ui.panels;

import dev.marregui.comando.core.dispatch.Target;
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
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.core.view.ViewSpec;
import dev.marregui.comando.ui.PanelTexts;
import dev.marregui.comando.ui.kit.action.ToolButton;
import dev.marregui.comando.ui.kit.format.KeyText;
import dev.marregui.comando.ui.kit.icon.Icons;
import dev.marregui.comando.ui.kit.icon.SpinnerIcon;
import dev.marregui.comando.ui.kit.resource.ResourceActions;
import dev.marregui.comando.ui.kit.tabs.TabStrip;
import dev.marregui.comando.ui.kit.text.MarkdownHtml;
import dev.marregui.comando.ui.kit.text.Plain;
import dev.marregui.comando.ui.kit.theme.SidebarLook;
import dev.marregui.comando.ui.kit.theme.Theme;
import dev.marregui.comando.ui.kit.vcs.OpenedFiles;

import javax.swing.BorderFactory;
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
import javax.swing.Timer;
import javax.swing.TransferHandler;
import javax.swing.event.ListDataEvent;
import javax.swing.event.ListDataListener;
import java.awt.BorderLayout;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * The left column, three tabs in one row of icons: Sessions (every session as one row, its state icon and its name,
 * the rest in its card and its menu; under them, the panels of comando's own that belong to no session:
 * {@link PanelsWithoutSession}), Connections, and Files (the folders the user chose, as trees: {@link FilesTab}). No tab has a bar of buttons: a tab's menu adds (and, for sessions, imports
 * and sorts), and a row's menu edits, clones and removes, through the same {@link ResourceActions}. Double-click on a
 * session opens it in a panel ({@link Intent.OpenSession}); on a connection, binds it to the focused panel. A session
 * row and a connection row are alike: the state icon, which a click starts or stops (a session) or connects or
 * disconnects (a connection), the name, a card over the
 * name (a connection's has no summary) and everything else in the row's menu. Sessions are
 * listed running first, then by name, until the user drags one: from then on in the order the user left them, until
 * the tab menu's Sort. Hidden, the column collapses to a rail whose button shows it again.
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

        /** The session dialog on a new session named {@code name}: a panel without a session's "New session ‹title›…". */
        default void newSession(String name) {
        }

        /** As {@link #openFile}, showing the file's text to edit rather than rendered. */
        default void editFile(Path file) {
            openFile(file);
        }
    }

    /**
     * The card a session row shows in place of a tooltip: the workspace's, the one a tab shows. {@code show} puts it under
     * {@code row}, a rectangle of {@code over}, or beside the rows with its left edge at {@code beside}, in the same
     * coordinates; {@code left} says the pointer left the row, so the card closes unless the pointer goes on into it.
     */
    public interface Cards {
        Cards NONE = new Cards() {
            @Override
            public void show(String session, JComponent over, Rectangle row) {
            }

            @Override
            public void left() {
            }
        };

        void show(String session, JComponent over, Rectangle row);

        /** As {@link #show(String, JComponent, Rectangle)}, beside the rows from {@code beside} when there is room. */
        default void show(String session, JComponent over, Rectangle row, int beside) {
            show(session, over, row);
        }

        /** A connection row's card, of its facts alone (record 92). */
        default void showConnection(String connection, JComponent over, Rectangle row) {
        }

        /** As {@link #showConnection(String, JComponent, Rectangle)}, beside the rows from {@code beside} when there is room. */
        default void showConnection(String connection, JComponent over, Rectangle row, int beside) {
            showConnection(connection, over, row);
        }

        void left();

        /**
         * {@code r} hears a row's card close: with the point on the screen the pointer left the card for, or null when it
         * closed otherwise (its ✕, a click on it, the pointer off its row).
         */
        default void onClose(Consumer<Point> r) {
        }
    }

    /** The row the pointer rests on over its text: a session's, or a connection's. */
    private record Rest(boolean connection, String name) {
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
            "No sessions yet. Right-click the tab to add one or import stored Claude Code conversations.");
    /** One row for every connection, painted for each in turn: the list is short, and a row parses little. */
    private final ConnectionRow connectionRow = new ConnectionRow();
    private final JList<ConnectionState> connections = emptyText(new ConnectionList(connectionModel),
            "No connections yet. Right-click the tab to add one.");
    public static final int SESSIONS = 0;
    public static final int CONNECTIONS = 1;
    public static final int FILES = 2;

    private final ResourceActions<SessionState> sessionActions;
    private final ResourceActions<ConnectionState> connectionActions;
    private final PanelsWithoutSession loosePanels;
    /** The panels' texts, which a session's menu has the session rewrite. */
    private final PanelTexts texts;
    /** The card the session rows show; none until the shell gives the workspace's. */
    private Cards cards = Cards.NONE;
    /** The row the pointer rests on over its text, whose card shows when the timer fires; null when none. */
    private Rest cardRow;
    /** Whether the card shown is a session row's, so leaving the rows tells it. */
    private boolean cardShown;
    /**
     * A click on a row's name opened its card, which has not closed since: the pointer resting on another row's name
     * shows that row's card, without a click.
     */
    private boolean following;
    /**
     * The wait before the card follows the pointer onto another row: long enough for the pointer to cross the next row's
     * edge on its way into the card shown, which hangs just under its row.
     */
    static final int FOLLOW_MILLIS = 150;
    /**
     * How far the card beside the rows reaches back over them: the widest row's right padding, so the pointer leaving
     * the card toward the names lands on a row, never on a gap between the two.
     */
    static final int OVERLAP = 4;
    private final Timer cardWait = new Timer(FOLLOW_MILLIS, e -> showCard());
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
        this.texts = texts;
        this.loosePanels = new PanelsWithoutSession(intents, texts, dialogs::newSession, questions);
        cardWait.setRepeats(false);
        sessions.getAccessibleContext().setAccessibleName("Sessions");
        connections.getAccessibleContext().setAccessibleName("Connections");
        sessions.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        connections.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        sessions.setCellRenderer((list, s, index, selected, focus) -> row(s).show(s, state != null && state.homePanel(s.name()).isPresent(),
                boundToFocused(s),
                state == null ? 0 : state.pendingApprovals(s.name()), selected,
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
        // one line, as a session's (record 92): the state icon, which connects and disconnects, and the name
        connections.setCellRenderer((list, c, index, selected, focus) -> connectionRow.show(c, usedByFocused(c), selected,
                selected ? list.getSelectionBackground() : list.getBackground(), selected ? list.getSelectionForeground() : list.getForeground()));
        // ⌘C, and Edit ▸ Copy, which forwards it, copy the selected connection's details
        connections.setTransferHandler(new TransferHandler() {
            @Override
            public int getSourceActions(JComponent c) {
                return COPY;
            }

            @Override
            protected Transferable createTransferable(JComponent c) {
                ConnectionState x = connections.getSelectedValue();
                return x == null ? null : new StringSelection(ConnectionRow.details(x));
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
                } else if (e.getClickCount() == 1) {
                    openCard(new Rest(false, s.name()));
                } else if (e.getClickCount() == 2) {
                    leaveCard();
                    intents.dispatch(new Intent.OpenSession(s.name(), state.focused(), PanelId.random()));
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                popup(e);
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                pointerOnList = true;
            }

            @Override
            public void mouseExited(MouseEvent e) {
                pointerOnList = false;
                restOn(null);
                // the order held while the pointer was on the list takes effect now
                if (state != null) {
                    sync(sessionModel, ordered(state.sessions(), manualOrder), sessions);
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                // a press acts on the row: its click decides the card
                cardWait.stop();
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
                boolean onControl = s != null && control(s, e).isPresent();
                sessions.setCursor(onControl ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : null);
                // over a row's text its card, once a click opened one; over a control the control's own tip
                restOn(s == null || onControl ? null : new Rest(false, s.name()));
            }
        });
        connections.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                ConnectionState c = at(connections, e);
                if (c == null || !SwingUtilities.isLeftMouseButton(e)) {
                    return;
                }
                if (onIcon(c, e)) {
                    if (e.getClickCount() == 1) {
                        intents.dispatch(c.status().isUsable() ? new Intent.CloseConnection(c.name()) : new Intent.OpenConnection(c.name()));
                    }
                } else if (e.getClickCount() == 1) {
                    openCard(new Rest(true, c.name()));
                } else if (e.getClickCount() == 2) {
                    leaveCard();
                    intents.dispatch(new Intent.BindConnection(state.focused(), c.name()));
                }
            }

            @Override
            public void mouseExited(MouseEvent e) {
                restOn(null);
            }

            @Override
            public void mousePressed(MouseEvent e) {
                // a press acts on the row: its click decides the card
                cardWait.stop();
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
        connections.addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                ConnectionState c = at(connections, e);
                boolean onIcon = c != null && onIcon(c, e);
                connections.setCursor(onIcon ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : null);
                restOn(c == null || onIcon ? null : new Rest(true, c.name()));
            }
        });
        files = new FilesTab(intents, dialogs::openFile, dialogs::editFile, questions);
        sessionActions = new ResourceActions<>(sessions,
                ResourceActions.Rows.of(SessionKind.INSTANCE, SessionState::name, SessionState::status, "Stop", () -> state),
                dialogs.sessions(), name -> intents.dispatch(new Intent.RemoveSession(name)), questions);
        order(false);
        connectionActions = new ResourceActions<>(connections,
                ResourceActions.Rows.of(ConnectionKind.INSTANCE, ConnectionState::name, ConnectionState::status, "Disconnect", () -> state),
                dialogs.connections(), name -> intents.dispatch(new Intent.RemoveConnection(name)), questions);
        // one row of icons (Miguel, 2026-10-19): the resources, then the documents; a click moves nothing. No bar of
        // buttons: a tab's menu adds, a row's menu edits, clones and removes. A session and its own panel are one row,
        // so the panels have no tab of their own: those without a session sit under the sessions (record 91)
        JPanel sessionsTab = section(new JScrollPane(sessions));
        sessionsTab.add(loosePanels.component(), BorderLayout.SOUTH);
        tabs.add("Sessions", "session", "Claude Code sessions and panels; right-click to add, import or sort", sessionsTab);
        tabs.add("Connections", "database", "databases; right-click to add one", section(new JScrollPane(connections)));
        // the sidebar's look (record 98): the transcript's ground, ivory names, the tint for headers and the selection
        SidebarLook.list(sessions);
        SidebarLook.list(connections);
        tabs.add("Files", "files", "your folders and their git changes; double-click a file to open it",
                section(files.component()));
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

    /** The tab in front: {@link #SESSIONS}, {@link #CONNECTIONS} or {@link #FILES}. */
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

    /** The files the user opens: a new file opened once is no longer marked in the Files tab. */
    public void watchOpened(OpenedFiles opened) {
        files.watchOpened(opened);
    }

    /** Adds the window's own commands to each tab's menu: {@code more} gets the tab and the menu built so far. */
    public void onTabMenu(BiConsumer<Integer, JPopupMenu> more) {
        moreForTab = more;
    }

    /** What a folder row's menu in the Files tab gains from the window, after its own items. */
    public void onFolderMenu(BiConsumer<FolderRoot, JPopupMenu> extra) {
        files.onFolderMenu(extra);
    }

    /**
     * What a tab's header offers on a right click: the tab's own actions (a new session or connection, importing
     * conversations, sorting the sessions while a drag ordered them by hand), then the window's commands for it, with
     * their keys (a new or reopened panel; the Files tab's two configuration windows). Hiding the sidebar is ⌘B or ⋯.
     */
    JPopupMenu tabMenu(int tab) {
        JPopupMenu menu = new JPopupMenu();
        switch (tab) {
            case SESSIONS -> {
                menu.add(menuItem("New session…", "add", sessionActions::addNew));
                menu.add(menuItem("Import stored conversations…", "import", dialogs::importSessions));
                JMenuItem sorting = menuItem(SORT_TIP, "sort", () -> order(false));
                sorting.setEnabled(manualOrder); // sorted already: nothing to do until a drag orders them by hand
                menu.add(sorting);
            }
            case CONNECTIONS -> menu.add(menuItem("New connection…", "add", connectionActions::addNew));
            default -> {
            }
        }
        moreForTab.accept(tab, menu);
        return menu;
    }

    private static JMenuItem menuItem(String text, String icon, Runnable r) {
        JMenuItem i = new JMenuItem(text, Icons.get(icon));
        i.addActionListener(a -> r.run());
        return i;
    }

    /** The panels without a session under the sessions; for tests. */
    PanelsWithoutSession loosePanels() {
        return loosePanels;
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
        sync(sessionModel, pointerOnList ? held(shownSessions(), s.sessions()) : ordered(s.sessions(), manualOrder), sessions);
        spinWhileWorking();
        // a session reporting a newer CLI version changes the other rows too: theirs is older now
        String newest = newestCli();
        if (!newest.equals(paintedNewestCli)) {
            paintedNewestCli = newest;
            sessions.repaint();
        }
        rows.keySet().retainAll(s.sessions().stream().map(SessionState::name).toList());
        sync(connectionModel, s.connections(), connections);
        loosePanels.render(s);
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
        if (state != null) {
            sync(sessionModel, ordered(state.sessions(), manual), sessions);
        }
    }

    /**
     * Whether the pointer is on the session list: a session that starts or stops keeps its row until the pointer leaves,
     * so the row under it is still the one it was on and a second click does not land on another session (record 90).
     */
    private boolean pointerOnList;

    private List<SessionState> shownSessions() {
        return Collections.list(sessionModel.elements());
    }

    /**
     * {@code stored} in the order of {@code shown}, the rows as they are: each kept session where it is, with its new
     * state; a new session last; a removed one gone.
     */
    static List<SessionState> held(List<SessionState> shown, List<SessionState> stored) {
        Map<String, SessionState> byName = new LinkedHashMap<>();
        stored.forEach(x -> byName.put(x.name(), x));
        List<SessionState> out = new ArrayList<>();
        for (SessionState x : shown) {
            SessionState now = byName.remove(x.name());
            if (now != null) {
                out.add(now);
            }
        }
        out.addAll(byName.values());
        return out;
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


    /** The row control under a mouse event on {@code s}'s cell, if any. */
    private Optional<SessionRow.Control> control(SessionState s, MouseEvent e) {
        int i = sessions.locationToIndex(e.getPoint());
        Rectangle cell = sessions.getCellBounds(i, i);
        return layOutRow(s, i).hit(cell, e.getPoint());
    }

    private SessionRow row(SessionState s) {
        return rows.computeIfAbsent(s.name(), name -> {
            SessionRow r = new SessionRow();
            r.working(spinner);
            return r;
        });
    }

    /** The working sessions' icon, one for every row, turned while any session works. */
    private final SpinnerIcon spinner = new SpinnerIcon(16);
    private final Timer spin = new Timer(SpinnerIcon.STEP_MILLIS, e -> {
        spinner.advance();
        sessions.repaint();
    });

    /** Turns the working icon while a session works, and stops it when none does. */
    private void spinWhileWorking() {
        boolean working = state != null && state.sessions().stream().anyMatch(SessionState::isBusy);
        if (working && !spin.isRunning()) {
            spin.start();
        } else if (!working && spin.isRunning()) {
            spin.stop();
        }
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
        switch (control) {
            case POWER -> intents.dispatch(s.status().isUsable() ? new Intent.StopSession(s.name()) : new Intent.StartSession(s.name()));
        }
    }

    private static JMenuItem tipped(JMenuItem i, String tip) {
        i.setToolTipText(Plain.text(tip));
        return i;
    }

    /** One item per model, the session's own checked; a choice applies now to a running session and is saved. */
    List<JMenuItem> modelItems(SessionState s) {
        return modelItems(s, intents);
    }

    List<JMenuItem> effortItems(SessionState s) {
        return effortItems(s, intents);
    }

    /** The model menu's items, the sidebar's and the session bar's. */
    static List<JMenuItem> modelItems(SessionState s, IntentSink intents) {
        String current = SessionRow.modelName(s);
        return SessionSpec.MODELS.stream().map(m -> choice(m, m.equals(current), () -> intents.dispatch(new Intent.SetSessionModel(s.name(), m)))).toList();
    }

    /** The effort menu's items, the sidebar's and the session bar's. */
    static List<JMenuItem> effortItems(SessionState s, IntentSink intents) {
        String current = SessionRow.effortName(s);
        return SessionSpec.EFFORTS.stream().map(e -> choice(e, e.equals(current), () -> intents.dispatch(new Intent.SetSessionEffort(s.name(), e)))).toList();
    }

    private static JMenuItem choice(String text, boolean current, Runnable r) {
        JCheckBoxMenuItem i = new JCheckBoxMenuItem(text, current);
        i.addActionListener(e -> r.run());
        return i;
    }

    /**
     * The session list: its tooltip names what the state icon does, and the row's card shows over its text; a row
     * dragged by its text moves, a line marking where it will land.
     */
    private final class SessionList extends JList<SessionState> {
        /** How far the mouse moves with the button down before a press becomes a drag. */
        private static final int DRAG_SLOP = 5;
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

        /** As wide as the tab: a row's line clips at its edge. */
        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
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
            // the report's bar down the left edge of the selected row (record 98)
            for (int i : getSelectedIndices()) {
                Rectangle r = getCellBounds(i, i);
                if (r != null) {
                    SidebarLook.bar(g, r);
                }
            }
            int n = getModel().getSize();
            if (gap < 0 || n == 0) {
                return;
            }
            Rectangle r = getCellBounds(Math.min(gap, n - 1), Math.min(gap, n - 1));
            int y = gap < n ? r.y : r.y + r.height;
            g.setColor(Theme.tint().body);
            g.fillRect(0, Math.max(0, y - 1), getWidth(), 2);
        }

        @Override
        public String getToolTipText(MouseEvent e) {
            int i = locationToIndex(e.getPoint());
            if (i < 0 || !getCellBounds(i, i).contains(e.getPoint())) {
                return null;
            }
            SessionState s = getModel().getElementAt(i);
            // over a control its tip; over the text no tooltip: the row's card shows there, as a tab's
            return layOutRow(s, i).tip(getCellBounds(i, i), e.getPoint()).map(Plain::text).orElse(null);
        }
    }

    /** The workspace's card, which the session rows show in place of a tooltip. */
    public void cards(Cards c) {
        cards = c;
        c.onClose(this::cardClosed);
    }

    /** A click on {@code row}'s name: its card shows now, and follows the pointer onto other rows' names until it closes. */
    private void openCard(Rest row) {
        following = true;
        cardRow = row;
        showCardNow();
    }

    /** The row acts (a double click): its card closes, as when the pointer leaves the row, and follows no more. */
    private void leaveCard() {
        following = false;
        cardWait.stop();
        if (cardShown) {
            cardShown = false;
            cards.left();
        }
    }

    /**
     * The pointer rests over {@code row}'s text, or over no row's text when null. While a card follows the pointer, a new
     * row starts the wait for its card, which takes the place of the one shown; off every row's name, the card shown is
     * told, and closes unless the pointer goes on into it.
     */
    private void restOn(Rest row) {
        if (Objects.equals(row, cardRow)) {
            return;
        }
        cardRow = row;
        if (row != null && following) {
            cardWait.restart();
        } else {
            cardWait.stop();
        }
        if (row == null && cardShown) {
            cardShown = false;
            cards.left();
        }
    }

    /**
     * The row's card closed. Left by the pointer for {@code at}, a point on the screen over another row's name, it goes
     * on following: that row's card shows after the wait. Closed any other way, a click opens the next.
     */
    private void cardClosed(Point at) {
        cardShown = false;
        cardRow = null;
        cardWait.stop();
        following = at != null && (nameAt(sessions, at) != null || nameAt(connections, at) != null);
    }

    /** The row whose name is at {@code screen}, a point on the screen, in {@code list}; null off its names. */
    private Rest nameAt(JList<?> list, Point screen) {
        if (!list.isShowing()) {
            return null;
        }
        Point p = new Point(screen);
        SwingUtilities.convertPointFromScreen(p, list);
        int i = list.locationToIndex(p);
        if (i < 0 || !list.getCellBounds(i, i).contains(p)) {
            return null;
        }
        Rectangle cell = list.getCellBounds(i, i);
        if (list == sessions) {
            SessionState s = sessionModel.get(i);
            return layOutRow(s, i).hit(cell, p).isPresent() ? null : new Rest(false, s.name());
        }
        ConnectionState c = connectionModel.get(i);
        return layOutConnection(c, i).hit(cell, p) ? null : new Rest(true, c.name());
    }

    /** The wait is over: the card of the row the pointer rests on shows under the row. */
    private void showCard() {
        if (cardRow == null || state == null) {
            return;
        }
        if (cardRow.connection()) {
            for (int i = 0; i < connectionModel.size(); i++) {
                if (connectionModel.get(i).name().equals(cardRow.name())) {
                    cardShown = true;
                    cards.showConnection(cardRow.name(), connections, connections.getCellBounds(i, i), beside(connections));
                    return;
                }
            }
            return;
        }
        for (int i = 0; i < sessionModel.size(); i++) {
            if (sessionModel.get(i).name().equals(cardRow.name())) {
                cardShown = true;
                cards.show(cardRow.name(), sessions, sessions.getCellBounds(i, i), beside(sessions));
                return;
            }
        }
    }

    /**
     * Where a card beside {@code list}'s rows starts, in its coordinates: after the widest row's icon, name and marks,
     * reaching {@link #OVERLAP} back over them, and within the rows shown.
     */
    int beside(JList<?> list) {
        int widest = 0;
        for (int i = 0; i < list.getModel().getSize(); i++) {
            IconRow r = list == sessions ? layOutRow(sessionModel.get(i), i) : layOutConnection(connectionModel.get(i), i);
            widest = Math.max(widest, r.getPreferredSize().width);
        }
        // the rows shown; a list laid out in no window shows its whole width
        Rectangle shown = list.getVisibleRect();
        int right = shown.isEmpty() ? list.getWidth() : shown.x + shown.width;
        return Math.max(0, Math.min(widest, right) - OVERLAP);
    }

    /** Shows the card of the row the pointer rests on now, without the wait. */
    void showCardNow() {
        cardWait.stop();
        showCard();
    }

    /** The name of the row whose card waits or shows, a session's or a connection's; for tests. */
    String cardRow() {
        return cardRow == null ? null : cardRow.name();
    }

    /** Whether the card follows the pointer onto other rows' names; for tests. */
    boolean following() {
        return following;
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

    JPopupMenu sessionMenu(SessionState s) {
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
        if (SessionBar.showsFresh(s)) {
            m.add(tipped(item("Start a new conversation", "fresh", () -> intents.dispatch(new Intent.StartFresh(s.name()))), SessionRow.freshTip(s)));
        }
        if (SessionBar.showsCheckpoint(s)) {
            m.add(tipped(item(s.reflection().due() ? "Checkpoint, due" : "Checkpoint", SessionBar.checkpointIcon(s),
                    () -> intents.dispatch(new Intent.Checkpoint(s.name()))), SessionBar.checkpointTip(s)));
        }
        if (s.status().isUsable()) {
            m.add(tipped(item("Compact the conversation", "compact", () -> intents.dispatch(new Intent.SessionCommand(s.name(), SessionSpec.COMPACT_COMMAND))),
                    SessionRow.COMPACT_TIP));
            rewriteItem(s).ifPresent(m::add);
        }
        m.addSeparator();
        // the menus name what the session runs at, as the row's chips did (record 89)
        JMenu models = new JMenu("Model: " + SessionRow.modelName(s));
        models.setToolTipText(SessionRow.modelTip(s));
        modelItems(s).forEach(models::add);
        m.add(models);
        JMenu efforts = new JMenu("Effort: " + SessionRow.effortName(s));
        efforts.setToolTipText(SessionRow.effortTip(s));
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

    /**
     * Has the running session rewrite its panel's whole text as a professional Markdown document, put in place of the
     * text: the focused panel when it is the session's, else the first of its panels. None for a session without an open
     * panel; disabled while that panel's text is blank.
     */
    private Optional<JMenuItem> rewriteItem(SessionState s) {
        List<PanelState> bound = state.panelsBoundToSession(s.name());
        if (bound.isEmpty()) {
            return Optional.empty();
        }
        PanelState panel = bound.stream().filter(p -> p.id().equals(state.focused())).findFirst().orElse(bound.getFirst());
        String text = texts.load(panel.id());
        JMenuItem i = item(Plain.text("Rewrite " + panel.title() + " as a document"), "markdown", () -> {
            String now = texts.load(panel.id());
            if (!now.isBlank()) {
                intents.dispatch(new Intent.RunText(panel.id(), EditorPanel.polishPrompt(panel.title(), now), Target.CLAUDE));
            }
        });
        i.setToolTipText(EditorPanel.POLISH_TIP);
        i.setEnabled(!text.isBlank());
        return Optional.of(i);
    }

    JPopupMenu connectionMenu(ConnectionState c) {
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
        m.add(item("Copy details", "copy", () -> clipboard.accept(ConnectionRow.details(c))));
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
        hint.setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_S, Theme.SPACE_M, Theme.SPACE_S, Theme.SPACE_M));
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


    private boolean boundToFocused(SessionState s) {
        return state != null && state.focusedPanel().session().equals(s.name());
    }

    private boolean usedByFocused(ConnectionState c) {
        return state != null && state.focusedPanel().connection().equals(c.name());
    }

    /** Whether a mouse event on {@code c}'s cell is on its state icon. */
    private boolean onIcon(ConnectionState c, MouseEvent e) {
        int i = connections.locationToIndex(e.getPoint());
        return layOutConnection(c, i).hit(connections.getCellBounds(i, i), e.getPoint());
    }

    /** The connection row at {@code index}, filled as it was painted, so its icon sits where it was drawn. */
    private ConnectionRow layOutConnection(ConnectionState c, int index) {
        return (ConnectionRow) connections.getCellRenderer().getListCellRendererComponent(connections, c, index, connections.isSelectedIndex(index), false);
    }

    /** Where a connection row's state icon is, in the list's coordinates; for tests. */
    Rectangle connectionIconBounds(int index) {
        return layOutConnection(connectionModel.get(index), index).iconBounds(connections.getCellBounds(index, index));
    }

    /** The connection list: over a row's icon the icon's tip; over its text no tooltip, the row's card shows there. */
    private final class ConnectionList extends JList<ConnectionState> {
        ConnectionList(DefaultListModel<ConnectionState> model) {
            super(model);
            setToolTipText("");
        }

        /** The report's bar down the left edge of the selected row (record 98). */
        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            for (int i : getSelectedIndices()) {
                Rectangle r = getCellBounds(i, i);
                if (r != null) {
                    SidebarLook.bar(g, r);
                }
            }
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public String getToolTipText(MouseEvent e) {
            int i = locationToIndex(e.getPoint());
            if (i < 0 || !getCellBounds(i, i).contains(e.getPoint())) {
                return null;
            }
            return layOutConnection(getModel().getElementAt(i), i).tip(getCellBounds(i, i), e.getPoint()).map(Plain::text).orElse(null);
        }
    }

    private static <T> T at(JList<T> list, MouseEvent e) {
        int i = list.locationToIndex(e.getPoint());
        return i >= 0 && list.getCellBounds(i, i).contains(e.getPoint()) ? list.getModel().getElementAt(i) : null;
    }

    /** A tab's content, filling it, on the sidebar's ground. */
    private static JPanel section(JComponent content) {
        if (content instanceof JScrollPane sp) {
            sp.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
            sp.setBorder(null);
            SidebarLook.ground(sp);
        }
        JPanel p = new JPanel(new BorderLayout());
        SidebarLook.ground(p);
        p.add(content, BorderLayout.CENTER);
        return p;
    }

    private static JMenuItem item(String text, String icon, Runnable r) {
        JMenuItem i = new JMenuItem(text, Icons.get(icon));
        i.addActionListener(e -> r.run());
        return i;
    }
}
