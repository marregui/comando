package dev.marregui.comando.ui.panels;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.port.InstancesPort;
import dev.marregui.comando.core.port.IntentSink;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.InstancesState;
import dev.marregui.comando.core.state.InstancesState.Consult;
import dev.marregui.comando.core.state.InstancesState.Instance;
import dev.marregui.comando.core.state.InstancesState.Member;
import dev.marregui.comando.core.view.Placement;
import dev.marregui.comando.core.view.ViewSpec;
import dev.marregui.comando.ui.kit.icon.Icons;
import dev.marregui.comando.ui.kit.theme.FocusRing;
import dev.marregui.comando.ui.kit.theme.SidebarLook;
import dev.marregui.comando.ui.kit.theme.Theme;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.ToolTipManager;
import javax.swing.event.AncestorEvent;
import javax.swing.event.AncestorListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The sidebar's Instances tab (record 124): the comando Edge and this instance's link to it, the instances on it with
 * their sessions, and the questions asked and answered. The user asks an instance and refuses one from a row's menu;
 * everything shown comes from {@link AppState#instances()}.
 */
final class InstancesTab {

    /** The comando Edge's toggle, as far as the tab needs it: whether it runs, and a start. */
    interface Edge {
        boolean running();

        boolean busy();

        void start();

        Edge NONE = new Edge() {
            @Override
            public boolean running() {
                return false;
            }

            @Override
            public boolean busy() {
                return false;
            }

            @Override
            public void start() {
            }
        };
    }

    /** A tree node's value: an instance (this one or another) or one of its sessions. */
    record Row(Instance instance, Member member, boolean self) {
    }

    private final IntentSink intents;
    private final Supplier<InstancesPort> port;
    private volatile Edge edge = Edge.NONE;
    private final JPanel root = new JPanel(new BorderLayout());
    private final JLabel status = new JLabel();
    private final JButton start = new JButton("Start the Edge");
    private final DefaultMutableTreeNode top = new DefaultMutableTreeNode();
    private final DefaultTreeModel model = new DefaultTreeModel(top);
    private final JTree tree = new JTree(model);
    private final DefaultListModel<Consult> consultModel = new DefaultListModel<>();
    private final JList<Consult> consults = new JList<>(consultModel);
    /** Refreshes the header while shown: the Edge starts and stops without a change of state. */
    private final Timer header = new Timer(1_000, e -> header());
    private AppState state;
    private InstancesState shown;

    InstancesTab(IntentSink intents, Supplier<InstancesPort> port) {
        this.intents = intents;
        this.port = port;
        SidebarLook.ground(root);
        JPanel head = new JPanel(new BorderLayout());
        head.setOpaque(false);
        head.setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_XS, Theme.SPACE_S, Theme.SPACE_XS, Theme.SPACE_S));
        status.setForeground(Theme.MUTED_STRONG);
        status.putClientProperty("html.disable", Boolean.TRUE);
        start.setIcon(Icons.get("edge-start"));
        start.setToolTipText("Start the comando Edge, one per machine; every instance connects to it");
        start.addActionListener(e -> edge.start());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        buttons.setOpaque(false);
        buttons.add(start);
        head.add(status, BorderLayout.CENTER);
        head.add(buttons, BorderLayout.SOUTH);

        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setCellRenderer(new RowRenderer());
        tree.setToggleClickCount(0);
        ToolTipManager.sharedInstance().registerComponent(tree);
        SidebarLook.tree(tree);
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                menu(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                menu(e);
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                Row r = rowAt(e);
                if (r != null && e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e) && !r.self()) {
                    ask(r.instance());
                }
            }
        });

        consults.setCellRenderer(new ConsultRenderer());
        SidebarLook.list(consults);
        consults.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int i = consults.locationToIndex(e.getPoint());
                if (e.getClickCount() == 2 && i >= 0 && consults.getCellBounds(i, i).contains(e.getPoint())) {
                    open(consultModel.get(i));
                }
            }
        });

        JPanel lower = new JPanel(new BorderLayout());
        SidebarLook.ground(lower);
        lower.add(SidebarLook.header("Questions"), BorderLayout.NORTH);
        lower.add(scroll(consults), BorderLayout.CENTER);
        JPanel upper = new JPanel(new BorderLayout());
        SidebarLook.ground(upper);
        upper.add(head, BorderLayout.NORTH);
        upper.add(scroll(tree), BorderLayout.CENTER);
        // the instances over the questions, 3 to 2; no split pane: the window finds its own panes by orientation
        JPanel both = new JPanel(new GridBagLayout());
        both.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.fill = GridBagConstraints.BOTH;
        c.weightx = 1;
        c.weighty = 0.6;
        both.add(upper, c);
        c.weighty = 0.4;
        both.add(lower, c);
        root.add(both, BorderLayout.CENTER);
        root.addAncestorListener(new AncestorListener() {
            @Override
            public void ancestorAdded(AncestorEvent e) {
                header();
                header.start();
            }

            @Override
            public void ancestorRemoved(AncestorEvent e) {
                header.stop();
            }

            @Override
            public void ancestorMoved(AncestorEvent e) {
            }
        });
        header();
    }

    JComponent component() {
        return root;
    }

    /** The Edge's toggle, set once the window has it. */
    void edge(Edge e) {
        edge = e == null ? Edge.NONE : e;
        header();
    }

    void render(AppState s) {
        state = s;
        InstancesState i = s.instances();
        if (i.equals(shown)) {
            return;
        }
        shown = i;
        rebuild(i);
        List<Consult> newest = i.consults().reversed();
        if (!newest.equals(Collections.list(consultModel.elements()))) {
            consultModel.clear();
            newest.forEach(consultModel::addElement);
        }
        header();
    }

    /** The tree again, keeping the instances the user opened open and the selected one selected. */
    private void rebuild(InstancesState i) {
        Set<String> open = new HashSet<>();
        String selected = null;
        Enumeration<TreePath> expanded = tree.getExpandedDescendants(new TreePath(top));
        while (expanded != null && expanded.hasMoreElements()) {
            if (expanded.nextElement().getLastPathComponent() instanceof DefaultMutableTreeNode n && n.getUserObject() instanceof Row r) {
                open.add(r.instance().slug());
            }
        }
        if (tree.getLastSelectedPathComponent() instanceof DefaultMutableTreeNode n && n.getUserObject() instanceof Row r) {
            selected = r.instance().slug() + (r.member() == null ? "" : "/" + r.member().name());
        }
        top.removeAllChildren();
        if (!i.self().slug().isEmpty()) {
            top.add(node(i.self(), true));
        }
        i.others().forEach(o -> top.add(node(o, false)));
        model.reload();
        for (int k = 0; k < top.getChildCount(); k++) {
            DefaultMutableTreeNode n = (DefaultMutableTreeNode) top.getChildAt(k);
            Row r = (Row) n.getUserObject();
            TreePath path = new TreePath(n.getPath());
            if (open.contains(r.instance().slug())) {
                tree.expandPath(path);
            }
            if (selected != null) {
                if (selected.equals(r.instance().slug())) {
                    tree.setSelectionPath(path);
                }
                for (int m = 0; m < n.getChildCount(); m++) {
                    DefaultMutableTreeNode c = (DefaultMutableTreeNode) n.getChildAt(m);
                    if (selected.equals(r.instance().slug() + "/" + ((Row) c.getUserObject()).member().name())) {
                        tree.setSelectionPath(new TreePath(c.getPath()));
                    }
                }
            }
        }
    }

    private static DefaultMutableTreeNode node(Instance in, boolean self) {
        DefaultMutableTreeNode n = new DefaultMutableTreeNode(new Row(in, null, self));
        in.sessions().forEach(m -> n.add(new DefaultMutableTreeNode(new Row(in, m, self))));
        return n;
    }

    private void header() {
        InstancesState i = shown == null ? InstancesState.NONE : shown;
        Edge e = edge;
        String text;
        if (i.connected()) {
            int n = i.others().size();
            text = "Connected as " + i.self().name() + " · " + (n == 0 ? "no other instance" : n == 1 ? "1 other instance" : n + " other instances");
        } else if (e.busy()) {
            text = "The comando Edge is starting or stopping…";
        } else if (e.running()) {
            text = "Connecting to the comando Edge…";
        } else {
            text = "The comando Edge is not running: instances reach each other only through it";
        }
        if (!text.equals(status.getText())) {
            status.setText(text);
            status.setToolTipText(text);
        }
        boolean offer = !i.connected() && !e.running() && !e.busy() && e != Edge.NONE;
        if (start.isVisible() != offer) {
            start.setVisible(offer);
            root.revalidate();
        }
    }

    private Row rowAt(MouseEvent e) {
        TreePath path = tree.getPathForLocation(e.getX(), e.getY());
        if (path == null || !(path.getLastPathComponent() instanceof DefaultMutableTreeNode n) || !(n.getUserObject() instanceof Row r)) {
            return null;
        }
        tree.setSelectionPath(path);
        return r;
    }

    private void menu(MouseEvent e) {
        if (!e.isPopupTrigger()) {
            return;
        }
        Row r = rowAt(e);
        if (r == null) {
            return;
        }
        menuFor(r).show(tree, e.getX(), e.getY());
    }

    /** A row's menu: ask the instance, refuse its questions, copy its id; this instance's own row only copies. */
    JPopupMenu menuFor(Row r) {
        Instance in = r.instance();
        JPopupMenu m = new JPopupMenu();
        if (!r.self()) {
            JMenuItem ask = new JMenuItem("Ask " + in.name() + "…");
            ask.addActionListener(a -> ask(in));
            m.add(ask);
            boolean denied = shown != null && shown.denied().contains(in.slug());
            JCheckBoxMenuItem refuse = new JCheckBoxMenuItem("Refuse questions from " + in.name(), denied);
            refuse.setToolTipText("agents.json peers.deny in this instance's data root");
            refuse.addActionListener(a -> port.get().deny(in.slug(), refuse.isSelected()));
            m.add(refuse);
            m.addSeparator();
        }
        JMenuItem copy = new JMenuItem("Copy the instance id (" + in.slug() + ")");
        copy.addActionListener(a -> Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(in.slug()), null));
        m.add(copy);
        return m;
    }

    /** Asks {@code in} a question the user types: it lands in that instance's {@code consult-<this>} session. */
    private void ask(Instance in) {
        JTextArea text = new JTextArea(8, 48);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        JPanel p = new JPanel(new BorderLayout(0, Theme.SPACE_XS));
        p.add(new JLabel("Your question to " + in.name() + (in.role().isEmpty() ? "" : " (" + in.role() + ")")
                + "; it answers in its session consult-" + (shown == null ? "" : shown.self().slug()) + ":"), BorderLayout.NORTH);
        p.add(new JScrollPane(text), BorderLayout.CENTER);
        SwingUtilities.invokeLater(text::requestFocusInWindow);
        int chosen = JOptionPane.showConfirmDialog(root, p, "Ask " + in.name(), JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (chosen == JOptionPane.OK_OPTION && !text.getText().isBlank()) {
            port.get().ask(in.slug(), text.getText().strip());
        }
    }

    /**
     * A question asked: the question and its reply in a side view. A question answered: the session here that answered
     * it, in its panel.
     */
    void open(Consult c) {
        if (c.direction() == InstancesState.Direction.ANSWERED && state != null && state.session(c.session()).isPresent()) {
            intents.dispatch(new Intent.OpenSession(c.session(), state.focused(), PanelId.random()));
            return;
        }
        String md = "## " + (c.direction() == InstancesState.Direction.ASKED ? "Question to " : "Question from ") + c.peerName() + "\n\n"
                + "*" + (c.session().isEmpty() ? "you, from the Instances tab" : "session " + c.session()) + " · " + label(c.status()) + "*\n\n"
                + c.question() + "\n\n## Reply\n\n" + (c.reply().isEmpty() ? "(none yet)" : c.reply()) + "\n";
        intents.dispatch(new Intent.ShowView("", "Question to " + c.peerName(), new ViewSpec.Markdown(md), Placement.SIDE));
    }

    private static JScrollPane scroll(JComponent c) {
        JScrollPane sp = new JScrollPane(c);
        sp.setBorder(BorderFactory.createEmptyBorder());
        FocusRing.install(sp);
        SidebarLook.ground(sp);
        return sp;
    }

    static String label(InstancesState.Status s) {
        return switch (s) {
            case WAITING -> "waiting";
            case OK -> "answered";
            case REFUSED -> "refused";
            case TIMEOUT -> "timed out";
            case ERROR -> "failed";
        };
    }

    /** How long since {@code started} (ISO-8601), as "3 min", "2 h", "1 d"; empty when unreadable. */
    static String since(String started, Instant now) {
        try {
            return span(Duration.between(Instant.parse(started), now));
        } catch (DateTimeParseException | NullPointerException e) {
            return "";
        }
    }

    static String span(Duration d) {
        long s = Math.max(0, d.toSeconds());
        return s < 60 ? s + " s" : s < 3_600 ? s / 60 + " min" : s < 86_400 ? s / 3_600 + " h" : s / 86_400 + " d";
    }

    /** One line for an instance: name, what it is for, its version when it differs from this one's. */
    static String line(Instance in, boolean self, String myVersion) {
        StringBuilder b = new StringBuilder(in.name());
        if (self) {
            b.append(" (this one)");
        }
        if (!in.role().isEmpty()) {
            b.append(" · ").append(in.role());
        }
        if (!self && !in.version().equals(myVersion)) {
            b.append(" · comando ").append(in.version().isEmpty() ? "?" : in.version());
        }
        return b.toString();
    }

    static String tip(Instance in, boolean self, String myVersion, boolean denied) {
        StringBuilder b = new StringBuilder(in.name()).append(" (instance ").append(in.slug()).append(')');
        b.append("\ncomando ").append(in.version().isEmpty() ? "?" : in.version());
        if (!self && !in.version().equals(myVersion)) {
            b.append(" (this one runs ").append(myVersion).append(')');
        }
        b.append("\nkit ").append(in.kit().isEmpty() ? "?" : in.kit());
        String up = since(in.started(), Instant.now());
        if (!up.isEmpty()) {
            b.append("\nup ").append(up);
        }
        b.append('\n').append(in.busy() ? "busy" : "idle").append(" · ").append(in.sessions().size()).append(" session")
                .append(in.sessions().size() == 1 ? "" : "s");
        if (denied) {
            b.append("\nits questions are refused here");
        }
        if (!self) {
            b.append("\ndouble-click to ask it a question; right-click for more");
        }
        return b.toString();
    }

    /** One line for a question: its direction, the peer, who asked, where it stands, how long, its first words. */
    static String line(Consult c, long now) {
        String who = c.session().isEmpty() ? "you" : c.session();
        String first = c.question().lines().findFirst().orElse("").strip();
        long end = c.endedAt() > 0 ? c.endedAt() : now;
        return (c.direction() == InstancesState.Direction.ASKED ? "→ " + c.peerName() + " · " + who : "← " + c.peerName() + " · " + who)
                + " · " + label(c.status()) + " · " + span(Duration.ofMillis(end - c.askedAt())) + " — " + first;
    }

    /** A dot in the colour of busy or idle, beside an icon. */
    private record Dot(Icon icon, Color color) implements Icon {
        @Override
        public void paintIcon(Component c, Graphics g0, int x, int y) {
            if (icon != null) {
                icon.paintIcon(c, g0, x, y);
            }
            Graphics2D g = (Graphics2D) g0.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(color);
                int at = icon == null ? 4 : icon.getIconWidth() + 3;
                g.fillOval(x + at, y + getIconHeight() / 2 - 3, 6, 6);
            } finally {
                g.dispose();
            }
        }

        @Override
        public int getIconWidth() {
            return (icon == null ? 4 : icon.getIconWidth() + 3) + 8;
        }

        @Override
        public int getIconHeight() {
            return icon == null ? 16 : Math.max(16, icon.getIconHeight());
        }
    }

    private final class RowRenderer extends DefaultTreeCellRenderer {
        @Override
        public Component getTreeCellRendererComponent(JTree t, Object value, boolean sel, boolean expanded, boolean leaf, int row, boolean focus) {
            super.getTreeCellRendererComponent(t, value, sel, expanded, leaf, row, focus);
            setBackgroundNonSelectionColor(Theme.ground());
            if (!(value instanceof DefaultMutableTreeNode n) || !(n.getUserObject() instanceof Row r)) {
                return this;
            }
            String mine = shown == null ? "" : shown.self().version();
            boolean denied = shown != null && shown.denied().contains(r.instance().slug());
            if (r.member() != null) {
                setText(r.member().name());
                setIcon(new Dot(Icons.get("session"), r.member().busy() ? Theme.CLAUDE : Theme.OK));
                setToolTipText(r.member().name() + (r.member().busy() ? " · busy" : " · idle"));
            } else {
                setText(line(r.instance(), r.self(), mine));
                setIcon(new Dot(Icons.get(r.self() ? "comando-folder" : "instances"), denied ? Theme.DANGER : r.instance().busy() ? Theme.CLAUDE : Theme.OK));
                setToolTipText(tip(r.instance(), r.self(), mine, denied));
            }
            if (!sel) {
                setForeground(!r.self() && r.member() == null && !r.instance().version().equals(mine) ? Theme.ATTENTION : SidebarLook.TEXT);
            }
            return this;
        }
    }

    private static final class ConsultRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean sel, boolean focus) {
            super.getListCellRendererComponent(list, value, index, sel, focus);
            if (value instanceof Consult c) {
                setText(line(c, System.currentTimeMillis()));
                setToolTipText(c.question().length() > 400 ? c.question().substring(0, 400) + "…" : c.question());
                if (!sel) {
                    setForeground(switch (c.status()) {
                        case WAITING -> Theme.INFO;
                        case OK -> SidebarLook.TEXT;
                        case REFUSED, TIMEOUT, ERROR -> Theme.DANGER;
                    });
                }
            }
            setBorder(BorderFactory.createEmptyBorder(2, Theme.SPACE_S, 2, Theme.SPACE_S));
            return this;
        }
    }
}
