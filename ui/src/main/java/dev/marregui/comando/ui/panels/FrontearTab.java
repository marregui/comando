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
import dev.marregui.comando.ui.panels.lab.TrafficRows;
import dev.marregui.comando.ui.panels.lab.TrafficRows.Row;

/**
 * The lower panel's frontear tab (record 187): this instance's node in four views. Overview: the node, its broker's
 * figures, and a row per peer with its link's state and send rates. Traffic: the packets, filtered by topic and kind, a
 * run of one origin's presences as one row unless every presence is asked for, the start of the selected packet's
 * payload beside them, and the steps on the links of a selected publish selected with it. Octopus: each link's figures.
 * Store: each link's log, how far its peer has read, and its force rounds. Nothing is watched until the tab is first
 * shown; then the port's listener refreshes it. EDT only.
 */
final class FrontearTab {

    /** The packets the tab keeps, as many as the port's window keeps (record 187). */
    static final int KEPT = 10_000;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.ROOT);
    private static final String EVERY_KIND = "every kind";

    private final JTabbedPane views = new JTabbedPane();
    private final JLabel node = label("frontear.node");
    private final JLabel totals = label("frontear.totals");
    private final JLabel counts = label("frontear.counts");
    private final LabTable peers = new LabTable("frontear.overview", "peer", "link", "msg/s", "bytes/s", "queued");
    private final JTextField filter = new JTextField(24);
    private final JComboBox<String> kinds = new JComboBox<>();
    private final JToggleButton everyPresence = Plain.only(new JToggleButton());
    private final JToggleButton pause = Plain.only(new JToggleButton());
    private final JLabel missedLabel = label("frontear.missed");
    private final LabTable traffic = new LabTable("frontear.traffic", "time", "kind", "origin / link", "topic", "QoS",
            "retain", "bytes", "hops", "correlation");
    private final JTextArea head = new JTextArea();
    private final LabTable octopus = new LabTable("frontear.octopus", "link", "state", "queued", "cursor", "tail",
            "sent", "dropped", "reordered", "deliveries", "last up", "last down", "detail");
    private final LabTable store = new LabTable("frontear.store", "link", "read", "bytes", "segments", "rounds/s",
            "last records", "last bytes", "last ms", "p50 ms", "p99 ms");
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
        views.addTab("Octopus", new JScrollPane(octopus.table()));
        views.addTab("Store", new JScrollPane(store.table()));
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

    /** The tab came on screen: the node is watched from now on. */
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
        p.add(new JScrollPane(peers.table()), BorderLayout.CENTER);
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
        everyPresence.setName("frontear.presence");
        everyPresence.setText("every presence");
        everyPresence.addActionListener(_ -> showTraffic());
        pause.setName("frontear.pause");
        pause.setText("pause");
        pause.addActionListener(_ -> refresh());
        final JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEADING, Theme.GAP, Theme.SPACE_XS));
        bar.add(filter);
        bar.add(kinds);
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
        head.setFont(Theme.mono());
        final JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(t), new JScrollPane(head));
        split.setResizeWeight(0.75);
        final JPanel p = new JPanel(new BorderLayout());
        p.add(bar, BorderLayout.NORTH);
        p.add(split, BorderLayout.CENTER);
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
        rows = TrafficRows.of(packets, filter.getText(), kind, everyPresence.isSelected());
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

    /** Selects the user's row and the steps on its path, and shows the start of its payload. */
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

    private static String headOf(final Packet p) {
        return p.detail().isEmpty() ? p.head() : p.head() + (p.head().isEmpty() ? "" : "\n\n") + p.detail();
    }

    /** A kind as the log names it: a publish, else the link's step. */
    static String kindName(final Kind k) {
        return k == Kind.PUBLISHED ? "Published" : k.name();
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
