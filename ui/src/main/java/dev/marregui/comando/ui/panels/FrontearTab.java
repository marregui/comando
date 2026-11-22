package dev.marregui.comando.ui.panels;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.HierarchyEvent;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.ListSelectionModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import dev.marregui.comando.core.port.LabPort;
import dev.marregui.comando.core.port.LabPort.Frame;
import dev.marregui.comando.core.port.LabPort.Kind;
import dev.marregui.comando.core.port.LabPort.Link;
import dev.marregui.comando.core.port.LabPort.Node;
import dev.marregui.comando.core.port.LabPort.Packet;
import dev.marregui.comando.ui.kit.text.Plain;
import dev.marregui.comando.ui.kit.theme.Theme;
import dev.marregui.comando.ui.panels.lab.HealthRows;
import dev.marregui.comando.ui.panels.lab.HealthRows.Health;
import dev.marregui.comando.ui.panels.lab.HealthRows.Status;
import dev.marregui.comando.ui.panels.lab.LabTable;
import dev.marregui.comando.ui.panels.lab.LabTable.Bar;
import dev.marregui.comando.ui.panels.lab.LabTable.Column;
import dev.marregui.comando.ui.panels.lab.LabTable.Flag;
import dev.marregui.comando.ui.panels.lab.PayloadText;
import dev.marregui.comando.ui.panels.lab.TrafficRows;
import dev.marregui.comando.ui.panels.lab.TrafficRows.Row;

/**
 * The lower panel's frontear tab (record 187): this instance's node in two views, each with a line above its table
 * saying what a row is and a tooltip on each column header saying what the column means. Health: the node and only
 * the faults it counts, a row per link to a peer with the worst trouble it shows, worst first, and beside the table
 * the selected link's every figure and the step that resolves its trouble. Traffic: the packets, filtered by topic and
 * kind, frontear's own control traffic hidden unless asked for, a run of one origin's presences as one row unless every
 * presence is asked for; one row selected at a time, and beside the log what the selected packet is, the steps of a
 * publish on the links, and its payload (indented when it is JSON). The tab opens the port when it is first shown (the
 * application opens it at launch, so the node is watched before); then the port's listener refreshes it. EDT only.
 */
final class FrontearTab {

    /** The packets the tab keeps, as many as the port's window keeps (record 187). */
    static final int KEPT = 10_000;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.ROOT);
    private static final String EVERY_KIND = "every kind";
    private static final String HEALTH =
            "One row per link to a peer, the worst first: its trouble, its rate, and what waits for it";
    private static final String TRAFFIC =
            "One row per packet event on this node: a publish here, or one step of it on a link to a peer";

    private final JTabbedPane views = new JTabbedPane();
    private final JLabel node = label("frontear.node");
    private final JLabel faults = label("frontear.faults");
    private final LabTable health = new LabTable("frontear.health",
            new Column("peer", "The peer node the link goes to; name@machine is on another machine"),
            new Column("status", "The link's worst trouble, or ok; the pane beside says how to resolve it"),
            new Column("msg/s", "Messages the peer acknowledged per second, over the last 5 s"),
            new Column("queued", "Messages written to the peer's log and not yet acknowledged"),
            new Column("delivered / written", "Records of the peer's log it acknowledged, of those written"),
            new Column("last change", "When the link last connected or disconnected, local time"));
    private final JTextArea linkDetail = new JTextArea();
    private final HealthRows healthRows = new HealthRows();
    private final JTextField filter = new JTextField(24);
    private final JComboBox<String> kinds = new JComboBox<>();
    private final JToggleButton control = Plain.only(new JToggleButton());
    private final JToggleButton everyPresence = Plain.only(new JToggleButton());
    private final JToggleButton pause = Plain.only(new JToggleButton());
    private final JLabel missedLabel = label("frontear.missed");
    private final LabTable traffic = new LabTable("frontear.traffic",
            new Column("time", "When the node saw the event, local time"),
            new Column("kind", "Published: a client published here; else one step of a message on a link"),
            new Column("origin / link", "The client that published it, or the peer the step's link goes to"),
            new Column("topic", "The MQTT topic"),
            new Column("QoS", "The publish's quality of service: 0, 1 or 2"),
            new Column("retain", "Whether the broker keeps it as the topic's retained message"),
            new Column("bytes", "The payload's size in bytes"),
            new Column("hops", "0: published on this node; 1: came over a link from a peer"),
            new Column("correlation", "Correlation data in hex: pairs a question with its reply"));
    private final JTextArea head = new JTextArea();
    private final ArrayDeque<Packet> packets = new ArrayDeque<>();
    private LabPort port = LabPort.NONE;
    private boolean shown;
    private boolean opened;
    /** The highest packet read. */
    private long last;
    /** The packets the window let go of before the tab read them. */
    private long missed;
    private List<Row> rows = List.of();
    /** The row the user selected, by its first packet; -1 for none. */
    private long selectedSeq = -1L;
    private List<Health> links = List.of();
    /** The link the user selected, by its peer; "" for none. */
    private String selectedPeer = "";
    /** The Health rows of a refresh that came while the mouse was down on the table; null for none. */
    private List<Health> heldLinks;
    /** Whether a refresh came while the mouse was down on the log, so the log is shown again on release. */
    private boolean trafficHeld;
    /** Set while the tab sets the selection itself, so the listener does not take it for the user's. */
    private boolean selecting;

    FrontearTab() {
        views.setName("frontear.views");
        views.addTab("Health", health());
        views.addTab("Traffic", traffic());
        views.addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && views.isShowing()) {
                shown();
            }
        });
    }

    JComponent component() {
        return views;
    }

    /** Reads {@code lab} from now on; it opens once the tab is shown. */
    void lab(final LabPort lab) {
        port.listen(() -> { });
        port = lab;
        opened = false;
        last = 0L;
        missed = 0L;
        packets.clear();
        port.listen(this::refresh);
        openWhenShown();
    }

    /** The tab came on screen: the port is opened, when not yet, and the tab follows it from now on. */
    void shown() {
        shown = true;
        openWhenShown();
    }

    /** Reads the node and the new packets, unless paused, and shows them. */
    void refresh() {
        final Frame f = port.read(last, pause.isSelected() ? 0 : KEPT);
        missed += f.missed();
        for (final Packet p : f.packets()) {
            packets.addLast(p);
            if (packets.size() > KEPT) {
                packets.removeFirst();
            }
        }
        last = f.last();
        showNode(f.node());
        if (!f.packets().isEmpty()) {
            showTraffic();
        }
    }

    private void openWhenShown() {
        if (shown && !opened) {
            opened = true;
            port.open();
            refresh();
        }
    }

    private JComponent health() {
        final JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_S, Theme.SPACE_S, Theme.SPACE_S, Theme.SPACE_S));
        top.add(node);
        top.add(faults);
        final JTable t = health.table();
        t.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        t.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && !selecting) {
                final int i = t.getSelectedRow();
                selectedPeer = i >= 0 && i < links.size() ? links.get(i).link().id() : "";
                if (heldLinks != null) {
                    // the rows a refresh held while the mouse was down, now that the click is known
                    final List<Health> held = heldLinks;
                    heldLinks = null;
                    showLinks(held);
                } else {
                    selectLink();
                }
            }
        });
        detailArea(linkDetail, "frontear.health.detail");
        final JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(t),
                new JScrollPane(linkDetail));
        split.setResizeWeight(0.6);
        final JPanel p = new JPanel(new BorderLayout());
        p.add(top, BorderLayout.NORTH);
        p.add(captioned("health", HEALTH, split), BorderLayout.CENTER);
        return p;
    }

    private JComponent traffic() {
        filter.setName("frontear.filter");
        filter.putClientProperty("JTextField.placeholderText", "topic contains");
        filter.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(final DocumentEvent e) {
                showTraffic();
            }

            @Override
            public void removeUpdate(final DocumentEvent e) {
                showTraffic();
            }

            @Override
            public void changedUpdate(final DocumentEvent e) {
                showTraffic();
            }
        });
        kinds.setName("frontear.kind");
        kinds.addItem(EVERY_KIND);
        for (final Kind k : Kind.values()) {
            kinds.addItem(kindName(k));
        }
        kinds.addActionListener(_ -> showTraffic());
        control.setName("frontear.control");
        control.setText("show control");
        control.setToolTipText("Show frontear's own topics: the links' checks, and node state and presence");
        control.addActionListener(_ -> showTraffic());
        everyPresence.setName("frontear.presence");
        everyPresence.setText("every presence");
        everyPresence.addActionListener(_ -> showTraffic());
        pause.setName("frontear.pause");
        pause.setText("pause");
        pause.addActionListener(_ -> refresh());
        final JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEADING, Theme.GAP, Theme.SPACE_XS));
        bar.add(filter);
        bar.add(kinds);
        bar.add(control);
        bar.add(everyPresence);
        bar.add(pause);
        bar.add(missedLabel);
        final JTable t = traffic.table();
        // one row at a time: a publish's steps are listed beside the log, not selected in it
        t.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        t.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && !selecting) {
                final int i = t.getSelectedRow();
                selectedSeq = i >= 0 && i < rows.size() ? rows.get(i).firstSeq() : -1L;
                if (trafficHeld) {
                    // the packets a refresh held while the mouse was down, now that the click is known
                    trafficHeld = false;
                    showTraffic();
                } else {
                    select();
                }
            }
        });
        detailArea(head, "frontear.head");
        final JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(t), new JScrollPane(head));
        split.setResizeWeight(0.75);
        final JPanel p = new JPanel(new BorderLayout());
        p.add(bar, BorderLayout.NORTH);
        p.add(captioned("traffic", TRAFFIC, split), BorderLayout.CENTER);
        return p;
    }

    private void showNode(final Node n) {
        final LabPort.Totals b = n.totals();
        node.setText(n.up() ? "node " + n.name() + " on port " + n.port() + ", at " + time(n.atMillis())
                + " · sessions " + b.sessions() + " · connections " + b.connections() + " · retained "
                + b.retained() + " (" + b.retainedBytes() + " bytes) · cdb written " + n.counts().sinkWritten()
                : "no frontear node runs in this instance");
        final String fault = HealthRows.faults(n, missed);
        faults.setText(fault.isEmpty() ? "no faults" : "faults: " + fault);
        faults.setForeground(fault.isEmpty() ? Theme.OK : Theme.DANGER);
        final long lost = missed + n.counts().ringMissed();
        missedLabel.setText(lost == 0L ? "" : "missed " + lost);
        // read at every refresh, so the queues' growth and the drops are seen however the table is
        final List<Health> read = healthRows.read(n);
        if (health.table().getSelectionModel().getValueIsAdjusting()) {
            // the mouse is down on the table: new rows now would put back the row it is leaving
            heldLinks = read;
        } else {
            showLinks(read);
        }
    }

    /** Shows {@code read} in the table, the user's link selected again. */
    private void showLinks(final List<Health> read) {
        links = read;
        final List<Object[]> cells = new ArrayList<>(links.size());
        for (final Health h : links) {
            final Link l = h.link();
            final boolean up = l.lastUpMillis() >= l.lastDownMillis();
            final long changeMillis = Math.max(l.lastUpMillis(), l.lastDownMillis());
            cells.add(new Object[]{l.id(), new Flag(h.label(), h.status() != Status.OK), rate(l.messagesPerSecond()),
                l.queued(), new Bar(h.log().cursor(), h.log().tail()),
                changeMillis <= 0L ? "" : (up ? "up " : "down ") + time(changeMillis)});
        }
        selecting = true;
        try {
            health.rows(cells);
        } finally {
            selecting = false;
        }
        selectLink();
    }

    /** Selects the user's link again, by its peer, and shows its figures beside the table. */
    private void selectLink() {
        int at = -1;
        for (int i = 0; i < links.size() && !selectedPeer.isEmpty(); i++) {
            if (links.get(i).link().id().equals(selectedPeer)) {
                at = i;
                break;
            }
        }
        final JTable t = health.table();
        selecting = true;
        try {
            if (at >= 0) {
                t.setRowSelectionInterval(at, at);
            } else {
                t.clearSelection();
            }
        } finally {
            selecting = false;
        }
        final String text = at >= 0 ? HealthRows.detail(links.get(at), FrontearTab::time)
                : links.isEmpty() ? "No links: this node has no peer yet." : "Select a link to see its figures.";
        if (!text.equals(linkDetail.getText())) {
            linkDetail.setText(text);
            linkDetail.setCaretPosition(0);
        }
    }

    private void showTraffic() {
        if (traffic.table().getSelectionModel().getValueIsAdjusting()) {
            // the mouse is down on the log: new rows now would put back the row it is leaving
            trafficHeld = true;
            return;
        }
        final int k = kinds.getSelectedIndex();
        final Kind kind = k <= 0 ? null : Kind.values()[k - 1];
        rows = TrafficRows.of(packets, filter.getText(), kind, control.isSelected(),
                everyPresence.isSelected());
        final List<Object[]> cells = new ArrayList<>(rows.size());
        for (final Row r : rows) {
            final Packet p = r.packet();
            final boolean step = p.kind() != Kind.PUBLISHED;
            final String name = r.count() > 1 ? kindName(p.kind()) + " (" + r.count() + ")" : kindName(p.kind());
            cells.add(new Object[]{time(p.atMillis()), name, step ? p.link() : p.origin(), p.topic(),
                step ? "" : p.qos(), step ? "" : p.retain(), p.bytes(), step ? "" : p.hops(), p.correlation()});
        }
        selecting = true;
        try {
            traffic.rows(cells);
        } finally {
            selecting = false;
        }
        select();
    }

    /** Selects the user's row and shows what it is: its title, a publish's steps on the links, its payload. */
    private void select() {
        int at = -1;
        for (int i = 0; i < rows.size() && selectedSeq >= 0; i++) {
            if (rows.get(i).firstSeq() == selectedSeq) {
                at = i;
                break;
            }
        }
        final JTable t = traffic.table();
        selecting = true;
        try {
            if (at >= 0) {
                t.setRowSelectionInterval(at, at);
            } else {
                t.clearSelection();
            }
        } finally {
            selecting = false;
        }
        final String text = at < 0 ? "Select a packet to see what it is." : describe(rows, at);
        if (!text.equals(head.getText())) {
            head.setText(text);
            head.setCaretPosition(0);
        }
    }

    /**
     * The row at {@code at} for the detail pane: what it is, a publish's steps on the links (or that it has none yet),
     * its payload under a heading, indented when it is JSON, and a step's reason.
     */
    static String describe(final List<Row> rows, final int at) {
        final Row row = rows.get(at);
        final Packet p = row.packet();
        final StringBuilder b = new StringBuilder(TrafficRows.describe(p));
        if (row.count() > 1) {
            b.append(" (the latest of ").append(row.count()).append(')');
        }
        if (p.kind() == Kind.PUBLISHED) {
            final List<Integer> path = TrafficRows.path(rows, at);
            b.append("\n\nOn the links:");
            if (path.isEmpty()) {
                b.append("\n  none: no peer took it (yet), or the steps are filtered out");
            }
            for (final int i : path) {
                b.append("\n  ").append(TrafficRows.step(p, rows.get(i).packet()));
            }
        }
        if (!p.detail().isEmpty()) {
            b.append("\n\nReason: ").append(p.detail());
        }
        final String payload = PayloadText.shown(p.payload().isEmpty() ? p.head() : p.payload());
        if (!payload.isEmpty()) {
            b.append("\n\nPayload:\n").append(payload);
        }
        return b.toString();
    }

    /** A kind as the log names it, a word: Published, Appended, Sent, Arrived, Dropped, Connected, Disconnected. */
    static String kindName(final Kind k) {
        final String name = k.name();
        return name.charAt(0) + name.substring(1).toLowerCase(Locale.ROOT);
    }

    /** {@code view} under a line saying what its rows are, named {@code frontear.caption.<name>}. */
    private static JComponent captioned(final String name, final String caption, final JComponent view) {
        final JLabel line = label("frontear.caption." + name);
        line.setText(caption);
        line.setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_XS, Theme.SPACE_S, Theme.SPACE_XS, Theme.SPACE_S));
        final JPanel p = new JPanel(new BorderLayout());
        p.add(line, BorderLayout.NORTH);
        p.add(view, BorderLayout.CENTER);
        return p;
    }

    /** {@code area} as a read-only detail pane named {@code name}: monospaced, its long lines wrapped at words. */
    private static void detailArea(final JTextArea area, final String name) {
        area.setName(name);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFont(Theme.mono());
    }

    private static JLabel label(final String name) {
        final JLabel l = Plain.only(new JLabel());
        l.setName(name);
        return l;
    }

    private static String time(final long atMillis) {
        return atMillis <= 0L ? "" : TIME.format(Instant.ofEpochMilli(atMillis).atZone(ZoneId.systemDefault()));
    }

    private static String rate(final double perSecond) {
        return String.format(Locale.ROOT, "%.1f", perSecond);
    }
}
