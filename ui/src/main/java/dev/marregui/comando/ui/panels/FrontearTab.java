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
import dev.marregui.comando.core.port.LabPort.Log;
import dev.marregui.comando.core.port.LabPort.Node;
import dev.marregui.comando.core.port.LabPort.Packet;
import dev.marregui.comando.ui.kit.text.Plain;
import dev.marregui.comando.ui.kit.theme.Theme;
import dev.marregui.comando.ui.panels.lab.LabTable;
import dev.marregui.comando.ui.panels.lab.LabTable.Bar;
import dev.marregui.comando.ui.panels.lab.LabTable.Column;
import dev.marregui.comando.ui.panels.lab.PayloadText;
import dev.marregui.comando.ui.panels.lab.TrafficRows;
import dev.marregui.comando.ui.panels.lab.TrafficRows.Row;

/**
 * The lower panel's frontear tab (record 187): this instance's node in four views, each with a line above its table
 * saying what a row is and a tooltip on each column header saying what the column means. Overview: the node, its
 * broker's figures, and a row per peer with its link's state and send rates. Traffic: the packets, filtered by topic
 * and kind, frontear's own control traffic hidden unless asked for, a run of one origin's presences as one row unless
 * every presence is asked for, the selected packet's payload beside them (indented when it is JSON), and the steps on
 * the links of a selected publish selected with it. Octopus: each link's figures. Store: each link's log, how far its
 * peer has read, and its force rounds. The tab opens the port when it is first shown (the application opens it at
 * launch, so the node is watched before); then the port's listener refreshes it. EDT only.
 */
final class FrontearTab {

    /** The packets the tab keeps, as many as the port's window keeps (record 187). */
    static final int KEPT = 10_000;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.ROOT);
    private static final String EVERY_KIND = "every kind";
    private static final String OVERVIEW =
            "One row per peer: its link's state, what this node sends it per second, and what waits for it";
    private static final String TRAFFIC =
            "One row per packet event on this node: a publish here, or one step of it on a link to a peer";
    private static final String OCTOPUS =
            "One row per link to a peer: what the octopus forwards to it, and its last connect and disconnect";
    private static final String STORE =
            "One durable log per peer: the messages this node keeps until that peer acknowledges them";

    private final JTabbedPane views = new JTabbedPane();
    private final JLabel node = label("frontear.node");
    private final JLabel totals = label("frontear.totals");
    private final JLabel counts = label("frontear.counts");
    private final LabTable peers = new LabTable("frontear.overview",
            new Column("peer", "The peer node the link goes to"),
            new Column("link state", "connected, or backoff: waiting to try the peer again"),
            new Column("msg/s", "Messages the peer acknowledged per second, over the last 5 s"),
            new Column("bytes/s", "Payload bytes the peer acknowledged per second, over the last 5 s"),
            new Column("queued", "Messages written to the peer's log and not yet acknowledged"));
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
    private final LabTable octopus = new LabTable("frontear.octopus",
            new Column("to peer", "The peer node the link goes to"),
            new Column("state", "connected, or backoff: waiting to try the peer again"),
            new Column("queued", "Messages in the log not yet delivered: cursor to tail"),
            new Column("cursor", "Log offset of the next message to deliver to the peer"),
            new Column("tail", "Log offset the next append takes"),
            new Column("sent", "Messages the peer acknowledged"),
            new Column("dropped", "Given up: refused for good, expired, past the hop limit, or QoS 0 over the bound"),
            new Column("reordered", "Messages taken out of order after the peer refused one for a while"),
            new Column("waiting to append", "Broker messages waiting to be written to the log, of the limit"),
            new Column("last up", "When the link last connected, local time"),
            new Column("last down", "When the link last disconnected, local time"),
            new Column("detail", "The reason the last connect or disconnect gave"));
    private final LabTable store = new LabTable("frontear.store",
            new Column("to peer", "The peer node whose messages the log keeps"),
            new Column("delivered / written", "Records the peer acknowledged, of those written to the log"),
            new Column("bytes", "The log's size on disk; a segment stays until all of it is delivered"),
            new Column("segments", "The log's segment files, of 64 MiB each"),
            new Column("rounds/s", "fsync rounds per second, over the last 5 s"),
            new Column("last records", "Records the last fsync round made durable"),
            new Column("last bytes", "Bytes the last fsync round made durable"),
            new Column("last force ms", "How long the last round's force to disk took"),
            new Column("force p50 ms", "Median force time of the last 200 rounds"),
            new Column("force p99 ms", "99th percentile force time of the last 200 rounds"));
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
    /** Set while the tab sets the selection itself, so the listener does not take it for the user's. */
    private boolean selecting;

    FrontearTab() {
        views.setName("frontear.views");
        views.addTab("Overview", overview());
        views.addTab("Traffic", traffic());
        views.addTab("Octopus", captioned("octopus", OCTOPUS, new JScrollPane(octopus.table())));
        views.addTab("Store", captioned("store", STORE, new JScrollPane(store.table())));
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

    private JComponent overview() {
        final JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_S, Theme.SPACE_S, Theme.SPACE_S, Theme.SPACE_S));
        top.add(node);
        top.add(totals);
        top.add(counts);
        final JPanel p = new JPanel(new BorderLayout());
        p.add(top, BorderLayout.NORTH);
        p.add(captioned("overview", OVERVIEW, new JScrollPane(peers.table())), BorderLayout.CENTER);
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
        t.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        t.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && !selecting) {
                final int i = t.getSelectionModel().getLeadSelectionIndex();
                selectedSeq = i >= 0 && i < rows.size() && t.isRowSelected(i) ? rows.get(i).firstSeq() : -1L;
                select();
            }
        });
        head.setName("frontear.head");
        head.setEditable(false);
        head.setLineWrap(true);
        head.setWrapStyleWord(true);
        head.setFont(Theme.mono());
        final JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(t), new JScrollPane(head));
        split.setResizeWeight(0.75);
        final JPanel p = new JPanel(new BorderLayout());
        p.add(bar, BorderLayout.NORTH);
        p.add(captioned("traffic", TRAFFIC, split), BorderLayout.CENTER);
        return p;
    }

    private void showNode(final Node n) {
        node.setText(n.up() ? "node " + n.name() + " on port " + n.port() + ", at " + time(n.atMillis())
                : "no frontear node runs in this instance");
        final LabPort.Totals b = n.totals();
        totals.setText("sessions " + b.sessions() + " · connections " + b.connections() + " · retained " + b.retained()
                + " (" + b.retainedBytes() + " bytes) · dropped: full " + b.droppedFull() + ", expired "
                + b.droppedExpired() + ", oversize " + b.droppedOversize() + " · refused " + b.refused()
                + " · backlog disconnects " + b.backlogDisconnects());
        final LabPort.Counts c = n.counts();
        counts.setText("watch: window dropped " + c.windowDropped() + ", ring missed " + c.ringMissed()
                + " · cdb: written " + c.sinkWritten() + ", dropped full " + c.sinkDroppedFull() + ", dropped down "
                + c.sinkDroppedDown());
        final long lost = missed + c.ringMissed();
        missedLabel.setText(lost == 0L ? "" : "missed " + lost);
        final List<Object[]> overview = new ArrayList<>();
        final List<Object[]> links = new ArrayList<>();
        for (final Link l : n.links()) {
            final String state = l.connected() ? "connected" : "backoff";
            overview.add(new Object[]{l.id(), state, rate(l.messagesPerSecond()), rate(l.bytesPerSecond()),
                l.queued()});
            links.add(new Object[]{l.id(), state, l.queued(), l.cursor(), l.tail(), l.sent(), l.dropped(),
                l.reordered(), l.queuedDeliveries() + " / " + l.queueMax(), time(l.lastUpMillis()),
                time(l.lastDownMillis()), l.lastDetail()});
        }
        peers.rows(overview);
        octopus.rows(links);
        final List<Object[]> logs = new ArrayList<>();
        for (final Log g : n.logs()) {
            logs.add(new Object[]{g.link(), new Bar(g.cursor(), g.tail()), g.bytes(), g.segments(),
                rate(g.roundsPerSecond()), g.lastRecords(), g.lastBytes(), millis(g.lastMillis()),
                millis(g.p50Millis()), millis(g.p99Millis())});
        }
        store.rows(logs);
    }

    private void showTraffic() {
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

    /** Selects the user's row and the steps on its path, and shows its payload. */
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
            t.clearSelection();
            if (at >= 0) {
                t.setRowSelectionInterval(at, at);
                for (final int i : TrafficRows.path(rows, at)) {
                    t.addRowSelectionInterval(i, i);
                }
                t.getSelectionModel().setLeadSelectionIndex(at);
            }
        } finally {
            selecting = false;
        }
        head.setText(at < 0 ? "" : headOf(rows.get(at).packet()));
        head.setCaretPosition(0);
    }

    /** The packet's whole payload, else its head, indented when it is JSON; then the step's reason if any. */
    private static String headOf(final Packet p) {
        final String payload = PayloadText.shown(p.payload().isEmpty() ? p.head() : p.payload());
        return p.detail().isEmpty() ? payload : payload + (payload.isEmpty() ? "" : "\n\n") + p.detail();
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

    private static String millis(final double ms) {
        return String.format(Locale.ROOT, "%.2f", ms);
    }
}
