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
import dev.marregui.comando.ui.kit.async.Debouncer;
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
import javax.swing.event.AncestorEvent;
import javax.swing.event.AncestorListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The sidebar's Instances tab (record 124): what the other comando instances are doing, from their presence on the
 * comando Edge alone, and the questions asked and answered. A click on a row shows its card: an instance's role,
 * version, kit, uptime and whether it started the Edge; a session's state, what its turn is doing and for how long, its
 * model, context, folder and tasks, and the first line of its last prompt and reply. A double click does nothing. An
 * instance that left stays, greyed, as last seen, until the user forgets it. The user asks an instance and refuses
 * one from its menu, and sets this instance's role from its own; everything shown comes from
 * {@link AppState#instances()}.
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
    private final Supplier<SessionsSidebar.Cards> cards;
    /** The row whose card shows, or null. */
    private Row carded;
    /**
     * After a click, as in the Sessions tab: the card follows the pointer onto the other rows, each row's card after a
     * rest of {@link SessionsSidebar#FOLLOW_MILLIS}, until it closes otherwise.
     */
    private boolean following;
    /** The row the pointer rests on, whose card waits. */
    private Row resting;
    private final Debouncer cardWait = new Debouncer(SessionsSidebar.FOLLOW_MILLIS, this::showResting);
    private volatile Edge edge = Edge.NONE;
    private final JPanel root = new JPanel(new BorderLayout());
    /**
     * Why the tab has no instances to show; clipped rather than widening the sidebar: a label's minimum is its whole
     * text, and the sidebar's minimum, the widest of its tabs', bounds the window's divider.
     */
    private final JLabel status = new JLabel() {
        @Override
        public Dimension getMinimumSize() {
            return new Dimension(0, super.getMinimumSize().height);
        }
    };
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

    InstancesTab(IntentSink intents, Supplier<InstancesPort> port, Supplier<SessionsSidebar.Cards> cards) {
        this.intents = intents;
        this.port = port;
        this.cards = cards;
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
                // a click shows the row's card, which then follows the pointer; a double click does nothing more
                if (e.getClickCount() == 1 && SwingUtilities.isLeftMouseButton(e)) {
                    Row r = rowAt(e);
                    if (r != null) {
                        following = true;
                        cardWait.stop();
                        card(r);
                    }
                }
            }

            @Override
            public void mouseExited(MouseEvent e) {
                // off the rows the card closes, unless the pointer goes on into it
                cardWait.stop();
                resting = null;
                if (carded != null && cards.get().showing(key(carded))) {
                    cards.get().left();
                }
            }
        });
        tree.addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                restOn(rowUnder(e.getX(), e.getY()));
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
        refreshCard();
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

    /** The header says something only while this instance is not connected: why, and the Edge's start. */
    private void header() {
        InstancesState i = shown == null ? InstancesState.NONE : shown;
        Edge e = edge;
        String text;
        if (i.connected()) {
            text = "";
        } else if (e.busy()) {
            text = "The comando Edge is starting or stopping…";
        } else if (e.running()) {
            text = "Connecting to the comando Edge…";
        } else {
            text = "The comando Edge is not running: instances reach each other only through it";
        }
        if (!text.equals(status.getText())) {
            status.setText(text);
            status.setToolTipText(text.isEmpty() ? null : text);
        }
        if (status.isVisible() == text.isEmpty()) {
            status.setVisible(!text.isEmpty());
            root.revalidate();
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

    /**
     * A row's menu: another instance present, ask it, refuse its questions; one that left, forget it; this instance,
     * set its role; then copy the id. A session's row has none: the tab shows, and asking is the instance's.
     */
    JPopupMenu menuFor(Row r) {
        Instance in = r.instance();
        JPopupMenu m = new JPopupMenu();
        if (r.member() != null) {
            return m;
        }
        if (r.self()) {
            JMenuItem role = new JMenuItem("Set the role…");
            role.setToolTipText("What this instance is for, as the others see it: instance.json in its data root");
            role.addActionListener(a -> setRole(in));
            m.add(role);
            m.addSeparator();
        } else if (!in.present()) {
            JMenuItem forget = new JMenuItem("Forget " + in.name());
            forget.setToolTipText("List it no more; it comes back when it is present again");
            forget.addActionListener(a -> port.get().forget(in.slug()));
            m.add(forget);
            m.addSeparator();
        } else {
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
        copy.setName("instances-copy-id");
        copy.addActionListener(a -> Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(in.slug()), null));
        m.add(copy);
        return m;
    }

    /** Asks the user what this instance is for, and sets it. */
    private void setRole(Instance in) {
        Object typed = JOptionPane.showInputDialog(root, "What " + in.name() + " is for, as the other instances see it:", "Set the role",
                JOptionPane.PLAIN_MESSAGE, null, null, in.role());
        if (typed instanceof String text) {
            port.get().role(text.strip());
        }
    }

    /** The row under {@code (x, y)}, its icon or text, or null; the selection stays. */
    private Row rowUnder(int x, int y) {
        TreePath path = tree.getPathForLocation(x, y);
        return path != null && path.getLastPathComponent() instanceof DefaultMutableTreeNode n && n.getUserObject() instanceof Row r ? r : null;
    }

    /**
     * The pointer is over {@code over}, or over no row. Back on the card's row, the card stays; on another row while the
     * card follows, that row's card shows after the rest; off every row, the card closes unless the pointer goes on into
     * it.
     */
    private void restOn(Row over) {
        if (carded != null && !cards.get().showing(key(carded))) {
            // closed otherwise: its ✕, a click on it, the pointer leaving it for elsewhere
            carded = null;
            following = false;
        }
        if (over == null) {
            cardWait.stop();
            resting = null;
            if (carded != null) {
                cards.get().left();
            }
            return;
        }
        if (carded != null && same(carded, over)) {
            cardWait.stop();
            resting = null;
            cards.get().stay();
            return;
        }
        if (following && (resting == null || !same(resting, over))) {
            resting = over;
            cardWait.request();
        }
    }

    private void showResting() {
        if (resting != null && following) {
            card(resting);
        }
    }

    private static String key(Row r) {
        return "instance:" + r.instance().slug() + (r.member() == null ? "" : "/" + r.member().name());
    }

    private static boolean same(Row a, Row b) {
        return b != null && a.instance().slug().equals(b.instance().slug()) && a.self() == b.self()
                && Objects.equals(a.member() == null ? null : a.member().name(), b.member() == null ? null : b.member().name());
    }

    /** {@code r}'s card, beside the rows at its row's height, as the Sessions tab shows its rows' cards. */
    private void card(Row r) {
        Rectangle bounds = boundsOf(r);
        if (bounds == null) {
            return;
        }
        carded = r;
        resting = null;
        Card c = cardOf(r);
        cards.get().showFacts(key(r), c.title(), c.facts(), c.heading(), c.text(), tree, bounds, beside());
    }

    private Card cardOf(Row r) {
        return cardOf(r, shown == null ? "" : shown.self().version(), shown != null && shown.denied().contains(r.instance().slug()),
                System.currentTimeMillis(), ZoneId.systemDefault());
    }

    /** {@code r}'s row in the tree as it is now, or null when it is gone. */
    private Rectangle boundsOf(Row r) {
        for (int i = 0; i < tree.getRowCount(); i++) {
            TreePath path = tree.getPathForRow(i);
            if (path.getLastPathComponent() instanceof DefaultMutableTreeNode n && n.getUserObject() instanceof Row x && same(r, x)) {
                return tree.getPathBounds(path);
            }
        }
        return null;
    }

    /** Where a card beside the rows starts: after the widest row shown, reaching {@link SessionsSidebar#OVERLAP} back. */
    int beside() {
        int widest = 0;
        for (int i = 0; i < tree.getRowCount(); i++) {
            Rectangle b = tree.getRowBounds(i);
            widest = Math.max(widest, b.x + b.width);
        }
        Rectangle seen = tree.getVisibleRect();
        int right = seen.isEmpty() ? tree.getWidth() : seen.x + seen.width;
        return Math.max(0, Math.min(widest, right) - SessionsSidebar.OVERLAP);
    }

    /** The card shown, with what the presences say now; its row gone, it stays as it was until it closes. */
    private void refreshCard() {
        if (carded == null || !cards.get().showing(key(carded))) {
            return;
        }
        Row now = null;
        for (int i = 0; i < tree.getRowCount(); i++) {
            if (tree.getPathForRow(i).getLastPathComponent() instanceof DefaultMutableTreeNode n && n.getUserObject() instanceof Row x
                    && same(carded, x)) {
                now = x;
            }
        }
        if (now != null) {
            carded = now;
            Card c = cardOf(now);
            cards.get().updateFacts(key(now), c.title(), c.facts(), c.heading(), c.text());
        }
    }

    /** What a row's card says: its title, its facts, and under a heading the last exchange of a session. */
    record Card(String title, List<Map.Entry<String, String>> facts, String heading, String text) {
    }

    static Card cardOf(Row r, String myVersion, boolean denied, long now, ZoneId zone) {
        Instance in = r.instance();
        List<Map.Entry<String, String>> f = new ArrayList<>();
        if (r.member() != null) {
            Member m = r.member();
            f.add(Map.entry("State", memberState(m, now)));
            if (!m.model().isEmpty() || !m.effort().isEmpty()) {
                f.add(Map.entry("Model", (m.model().isEmpty() ? "default" : m.model()) + (m.effort().isEmpty() ? "" : " · effort " + m.effort())));
            }
            if (m.context() >= 0) {
                f.add(Map.entry("Context", m.context() + "% used"));
            }
            if (!m.folder().isEmpty()) {
                f.add(Map.entry("Folder", m.folder() + (m.branch().isEmpty() ? "" : " · " + m.branch())));
            }
            if (!m.tasks().isEmpty()) {
                f.add(Map.entry("Background", String.join("; ", m.tasks())));
            }
            f.add(Map.entry("Instance", in.name()));
            StringBuilder text = new StringBuilder();
            if (!m.prompt().isEmpty()) {
                text.append("Asked: ").append(m.prompt());
            }
            if (!m.reply().isEmpty()) {
                text.append(text.isEmpty() ? "" : "\n\n").append("Said: ").append(m.reply());
            }
            return new Card(m.name(), List.copyOf(f), "Last exchange", text.toString());
        }
        f.add(Map.entry("State", instanceState(in, r.self(), now, zone)));
        if (!in.role().isEmpty()) {
            f.add(Map.entry("Role", in.role()));
        }
        f.add(Map.entry("comando", (in.version().isEmpty() ? "?" : in.version())
                + (!r.self() && !in.version().equals(myVersion) ? " · this one runs " + myVersion : "")));
        f.add(Map.entry("Kit", in.kit().isEmpty() ? "?" : in.kit()));
        String up = since(in.started(), Instant.ofEpochMilli(now));
        if (in.present() && !up.isEmpty()) {
            f.add(Map.entry("Up", up));
        }
        if (in.edge()) {
            f.add(Map.entry("Edge", "started the comando Edge, and only it stops it"));
        }
        f.add(Map.entry("Id", in.slug()));
        if (denied) {
            f.add(Map.entry("Questions", "refused here"));
        }
        return new Card(in.name() + (r.self() ? " (this one)" : ""), List.copyOf(f), "", "");
    }

    /** An instance's state: busy and how many of its sessions run a turn, idle, or when it was last seen. */
    static String instanceState(Instance in, boolean self, long now, ZoneId zone) {
        if (!in.present()) {
            return self ? "not connected to the comando Edge"
                    : in.lastSeen() > 0 ? "left; last seen " + when(in.lastSeen(), now, zone) : "not reachable: this instance is not on the Edge";
        }
        long busy = in.sessions().stream().filter(Member::busy).count();
        int all = in.sessions().size();
        return (busy > 0 ? "busy: " + busy + " of " + all + " session" + (all == 1 ? "" : "s") + " running a turn"
                : "idle: no session running a turn" + (all == 0 ? "" : ", " + all + " session" + (all == 1 ? "" : "s")));
    }

    /** A session's state: what its turn is doing and for how long, or idle, or its process's state. */
    static String memberState(Member m, long now) {
        if (m.busy()) {
            return "busy" + (m.activity().isEmpty() ? "" : ": " + m.activity()) + (m.turnStarted() > 0 ? " · " + span(Duration.ofMillis(now - m.turnStarted())) : "");
        }
        return m.status().isEmpty() || m.status().equals("OPEN") ? "idle" : m.status().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * A session's dot, as the Sessions tab colours its icon: Claude's orange while a turn runs, green while it runs idle,
     * grey while it is stopped, red when it failed.
     */
    static Color dot(Member m) {
        if (m.busy()) {
            return Theme.CLAUDE;
        }
        return switch (m.status()) {
            case "", "OPEN" -> Theme.OK;
            case "FAILED" -> Theme.DANGER;
            default -> Theme.MUTED;
        };
    }

    /** {@code at} as the time of day when it is today, else with its date. */
    static String when(long at, long now, ZoneId zone) {
        ZonedDateTime t = Instant.ofEpochMilli(at).atZone(zone);
        LocalDate today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
        return t.format(DateTimeFormatter.ofPattern(t.toLocalDate().equals(today) ? "HH:mm" : "yyyy-MM-dd HH:mm"));
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

    /**
     * One line for an instance: name, what it is for, its version when it differs from this one's, and for one that
     * left, when it was last seen.
     */
    static String line(Instance in, boolean self, String myVersion, long now, ZoneId zone) {
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
        if (!self && !in.present()) {
            b.append(in.lastSeen() > 0 ? " · left " + when(in.lastSeen(), now, zone) : " · not reachable");
        }
        return b.toString();
    }

    /** One line for a session: its name, and what its turn is doing when it runs one. */
    static String line(Member m) {
        return m.name() + (m.busy() && !m.activity().isEmpty() ? " · " + m.activity() : "");
    }

    /** One line for a question: its direction, the peer, who asked, where it stands, how long, its first words. */
    static String line(Consult c, long now) {
        String who = c.session().isEmpty() ? "you" : c.session();
        String first = c.question().lines().findFirst().orElse("").strip();
        long end = c.endedAt() > 0 ? c.endedAt() : now;
        return (c.direction() == InstancesState.Direction.ASKED ? "→ " + c.peerName() + " · " + who : "← " + c.peerName() + " · " + who)
                + " · " + label(c.status()) + " · " + span(Duration.ofMillis(end - c.askedAt())) + " — " + first;
    }

    /**
     * A dot in the colour of busy, idle or refused beside an icon, none for an instance that left; then the Edge's
     * antenna when the instance started the comando Edge.
     */
    record Dot(Icon icon, Color color, Icon mark) implements Icon {
        @Override
        public void paintIcon(Component c, Graphics g0, int x, int y) {
            if (icon != null) {
                icon.paintIcon(c, g0, x, y);
            }
            int at = icon == null ? 4 : icon.getIconWidth() + 3;
            if (color != null) {
                Graphics2D g = (Graphics2D) g0.create();
                try {
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g.setColor(color);
                    g.fillOval(x + at, y + getIconHeight() / 2 - 3, 6, 6);
                } finally {
                    g.dispose();
                }
            }
            if (mark != null) {
                mark.paintIcon(c, g0, x + at + 8, y + (getIconHeight() - mark.getIconHeight()) / 2);
            }
        }

        @Override
        public int getIconWidth() {
            return (icon == null ? 4 : icon.getIconWidth() + 3) + 8 + (mark == null ? 0 : mark.getIconWidth() + 2);
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
            // the card, at a click, says what the row only marks
            setToolTipText(null);
            if (r.member() != null) {
                setText(line(r.member()));
                setIcon(new Dot(Icons.get("session"), dot(r.member()), null));
            } else {
                Instance in = r.instance();
                setText(line(in, r.self(), mine, System.currentTimeMillis(), ZoneId.systemDefault()));
                Color dot = !in.present() ? null : denied ? Theme.DANGER : in.busy() ? Theme.CLAUDE : Theme.OK;
                setIcon(new Dot(Icons.get("instances"), dot, in.edge() ? Icons.get("edge") : null));
            }
            if (!sel) {
                setForeground(r.member() == null && !r.self() && !r.instance().present() ? Theme.MUTED
                        : !r.self() && r.member() == null && !r.instance().version().equals(mine) ? Theme.ATTENTION : SidebarLook.TEXT);
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
