package dev.marregui.comando.ui.panels;

import dev.marregui.comando.ui.kit.text.Plain;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.port.IntentSink;
import dev.marregui.comando.core.resource.ConnectionKind;
import dev.marregui.comando.core.resource.SessionKind;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.ConnectionState;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.ui.kit.format.Labels;
import dev.marregui.comando.ui.kit.icon.Icons;
import dev.marregui.comando.ui.kit.text.MarkdownHtml;
import dev.marregui.comando.ui.kit.theme.Theme;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.ListSelectionModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Objects;

/**
 * The switch surface: every session with its project, branch, state, cost, last activity and pending
 * approvals, and every connection. Double-click binds it to the focused panel.
 */
public final class SessionsSidebar {

    /** What the sidebar's buttons open. */
    public interface Dialogs {
        void sessions(String select);

        void importSessions();

        void connections(String select);

        /** Shows the connection's schema explorer, as Connections ▸ Explore the schema does. */
        void exploreSchema(String connection);
    }

    private final IntentSink intents;
    private final Dialogs dialogs;
    private final DefaultListModel<SessionState> sessionModel = new DefaultListModel<>();
    private final DefaultListModel<ConnectionState> connectionModel = new DefaultListModel<>();
    private final JList<SessionState> sessions = emptyText(new JList<>(sessionModel),
            "No sessions yet. + creates one (⌘⇧K); the import button brings in stored Claude Code conversations.");
    private final JList<ConnectionState> connections = emptyText(new JList<>(connectionModel),
            "No connections yet. + adds one (⌘⇧O).");
    private final JSplitPane root;
    private AppState state;
    private long now = System.currentTimeMillis();

    public SessionsSidebar(IntentSink intents, Dialogs dialogs) {
        this.intents = intents;
        this.dialogs = dialogs;
        sessions.getAccessibleContext().setAccessibleName("Sessions");
        connections.getAccessibleContext().setAccessibleName("Connections");
        sessions.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        connections.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        sessions.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
                JLabel l = (JLabel) super.getListCellRendererComponent(list, "", index, selected, focus);
                SessionState s = (SessionState) value;
                l.setText(sessionHtml(s));
                l.setIcon(Icons.get("claude"));
                l.setBorder(BorderFactory.createEmptyBorder(5, 6, 5, 6));
                l.setToolTipText(s.spec().workDir() + (s.spec().claudeSessionId().isEmpty() ? "" : " · " + s.spec().claudeSessionId()));
                return l;
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
                if (s != null && e.getClickCount() == 2) {
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
        JPanel top = section("Sessions", sessions,
                button("add", "New or edit sessions", () -> dialogs.sessions(selectedSession())),
                button("import", "Import stored conversations", dialogs::importSessions));
        JPanel bottom = section("Connections", connections,
                button("add", "New or edit connections", () -> dialogs.connections(selectedConnection())));
        root = new JSplitPane(JSplitPane.VERTICAL_SPLIT, top, bottom);
        root.setResizeWeight(0.7);
        root.setBorder(null);
    }

    public JComponent component() {
        return root;
    }

    public void render(AppState s) {
        state = s;
        now = System.currentTimeMillis();
        sync(sessionModel, s.sessions(), sessions);
        sync(connectionModel, s.connections(), connections);
    }

    /** Repaints relative times without a state change. */
    public void tick() {
        now = System.currentTimeMillis();
        sessions.repaint();
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

    private String sessionHtml(SessionState s) {
        String muted = Theme.hex(Theme.MUTED);
        String dot = "<span style='color:" + Theme.hex(EditorPanel.color(s.status())) + "'>" + EditorPanel.dot(s.status()) + "</span>";
        int approvals = state == null ? 0 : state.pendingApprovals(s.name());
        String badge = approvals == 0 ? "" : "&nbsp;<span style='color:" + Theme.hex(Theme.WARN) + "'>⚑" + approvals + "</span>";
        String activity = s.isBusy() ? (s.activity().isEmpty() ? "working" : MarkdownHtml.escape(clip(s.activity(), 30))) : Labels.ago(s.lastActivityMillis(), now);
        String branch = s.branch().isEmpty() ? "" : " ⎇ " + MarkdownHtml.escape(s.branch());
        String bound = boundText(s);
        String where = bound.isEmpty() ? "" : "&nbsp;<span style='color:" + Theme.hex(bound.equals("this panel") ? Theme.ACCENT : Theme.MUTED_STRONG)
                + "'>· " + MarkdownHtml.escape(clip(bound, 24)) + "</span>";
        return "<html>" + dot + "&nbsp;<b>" + MarkdownHtml.escape(s.name()) + "</b>" + where + badge
                + "&nbsp;&nbsp;<span style='color:" + muted + "'>" + (s.costUsd() > 0 ? Labels.usd(s.costUsd()) + " · " : "") + activity + "</span>"
                + "<br><span style='color:" + Theme.hex(Theme.MUTED_STRONG) + "'>" + MarkdownHtml.escape(clip(Labels.shortPath(s.spec().workDir()), 34))
                + branch + "</span></html>";
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
        m.addSeparator();
        m.add(item("Edit…", "edit", () -> dialogs.sessions(s.name())));
        m.add(item("Remove…", "close", () -> {
            if (confirmRemove(s.name(), SessionKind.INSTANCE.removalQuestion(state, s.name()))) {
                intents.dispatch(new Intent.RemoveSession(s.name()));
            }
        }));
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
        m.add(item("Edit…", "edit", () -> dialogs.connections(c.name())));
        m.add(item("Remove…", "close", () -> {
            if (confirmRemove(c.name(), ConnectionKind.INSTANCE.removalQuestion(state, c.name()))) {
                intents.dispatch(new Intent.RemoveConnection(c.name()));
            }
        }));
        return m;
    }

    /** Remove asks first, naming what it does; Cancel is the default. */
    private boolean confirmRemove(String name, String question) {
        Object[] options = {"Remove", "Cancel"};
        return JOptionPane.showOptionDialog(root, Plain.text(question), "Remove " + Plain.text(name), JOptionPane.DEFAULT_OPTION,
                JOptionPane.WARNING_MESSAGE, null, options, options[1]) == 0;
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
        list.getModel().addListDataListener(new javax.swing.event.ListDataListener() {
            @Override
            public void intervalAdded(javax.swing.event.ListDataEvent e) {
                overlay.setVisible(list.getModel().getSize() == 0);
            }

            @Override
            public void intervalRemoved(javax.swing.event.ListDataEvent e) {
                overlay.setVisible(list.getModel().getSize() == 0);
            }

            @Override
            public void contentsChanged(javax.swing.event.ListDataEvent e) {
                overlay.setVisible(list.getModel().getSize() == 0);
            }
        });
        return list;
    }

    /** Where a session is bound: "this panel" for the focused one, else the panels' titles; empty when unbound. */
    private String boundText(SessionState s) {
        if (state == null) {
            return "";
        }
        if (state.focusedPanel().session().equals(s.name())) {
            return "this panel";
        }
        List<String> titles = state.panels().stream().filter(p -> p.session().equals(s.name())).map(p -> p.title()).toList();
        return titles.isEmpty() ? "" : String.join(", ", titles);
    }

    private String selectedSession() {
        SessionState s = sessions.getSelectedValue();
        return s == null ? "" : s.name();
    }

    private String selectedConnection() {
        ConnectionState c = connections.getSelectedValue();
        return c == null ? "" : c.name();
    }

    private static <T> T at(JList<T> list, MouseEvent e) {
        int i = list.locationToIndex(e.getPoint());
        return i >= 0 && list.getCellBounds(i, i).contains(e.getPoint()) ? list.getModel().getElementAt(i) : null;
    }

    private static JPanel section(String title, JList<?> list, JButton... buttons) {
        JPanel header = new JPanel(new BorderLayout());
        header.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 4));
        JLabel label = new JLabel(title.toUpperCase());
        label.setForeground(Theme.MUTED);
        label.setFont(label.getFont().deriveFont(label.getFont().getSize2D() - 1f));
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        for (JButton b : buttons) {
            actions.add(b);
        }
        header.add(label, BorderLayout.WEST);
        header.add(actions, BorderLayout.EAST);
        JPanel p = new JPanel(new BorderLayout());
        JScrollPane sp = new JScrollPane(list);
        sp.setBorder(null);
        p.add(header, BorderLayout.NORTH);
        p.add(sp, BorderLayout.CENTER);
        return p;
    }

    private static JButton button(String icon, String tip, Runnable r) {
        JButton b = new JButton(Icons.get(icon));
        b.setToolTipText(tip);
        b.getAccessibleContext().setAccessibleName(tip);
        b.putClientProperty("JButton.buttonType", "toolBarButton");
        b.setFocusable(false);
        b.addActionListener(e -> r.run());
        return b;
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
