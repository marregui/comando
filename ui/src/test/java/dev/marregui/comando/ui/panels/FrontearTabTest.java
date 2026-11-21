package dev.marregui.comando.ui.panels;

import java.awt.Component;
import java.awt.Container;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import javax.swing.table.JTableHeader;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.port.LabPort;
import dev.marregui.comando.core.port.LabPort.Counts;
import dev.marregui.comando.core.port.LabPort.Frame;
import dev.marregui.comando.core.port.LabPort.Kind;
import dev.marregui.comando.core.port.LabPort.Link;
import dev.marregui.comando.core.port.LabPort.Log;
import dev.marregui.comando.core.port.LabPort.Node;
import dev.marregui.comando.core.port.LabPort.Packet;
import dev.marregui.comando.core.port.LabPort.Totals;
import dev.marregui.comando.core.result.ResultRegistry;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.LowerTab;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.core.transcript.TranscriptRegistry;
import dev.marregui.comando.ui.kit.action.ActionRegistry;
import dev.marregui.comando.ui.kit.theme.Theme;
import dev.marregui.comando.ui.widget.WidgetContext;
import dev.marregui.comando.ui.widget.WidgetRegistry;

import static org.assertj.core.api.Assertions.assertThat;

/// [FrontearTab], off screen, from a fake [LabPort], and its place in [LowerPanel].
/// - the tab opens its port only once shown; then each of the four views is filled
/// - every column header of the four views says what its column means, and each view says what a row is
/// - the packet log hides frontear's control traffic until asked, collapses presences, filters by topic and kind,
///   names each kind in a word, selects a publish's path and shows its payload, indented when it is JSON
/// - paused, the log stays as it is; the packets lost are counted beside it
/// - the frontear tab is a lower tab of the state: rendered, it comes to the front; chosen, it is selected
class FrontearTabTest {

    /// Set on the port, the tab opens nothing until shown; shown, it opens the port once and fills Overview, Octopus
    /// and Store with a row per link, Traffic with the packets, and the labels with the node and its counts.
    @Test
    void shownItOpensThePortOnceAndFillsTheFourViews() throws Exception {
        final FakeLab lab = new FakeLab();
        final FrontearTab tab = onEdt(FrontearTab::new);
        SwingUtilities.invokeAndWait(() -> tab.lab(lab));
        assertThat(lab.opened).as("not shown yet").isZero();

        SwingUtilities.invokeAndWait(() -> {
            tab.shown();
            tab.shown();
        });
        assertThat(lab.opened).isEqualTo(1);
        SwingUtilities.invokeAndWait(() -> {
            final JTabbedPane views = (JTabbedPane) tab.component();
            assertThat(views.getTabCount()).isEqualTo(4);
            assertThat(views.getTitleAt(0)).isEqualTo("Overview");
            assertThat(views.getTitleAt(3)).isEqualTo("Store");
            assertThat(table(tab, "frontear.overview").getRowCount()).isEqualTo(2);
            assertThat(table(tab, "frontear.overview").getValueAt(1, 1)).isEqualTo("backoff");
            assertThat(table(tab, "frontear.octopus").getRowCount()).isEqualTo(2);
            assertThat(table(tab, "frontear.octopus").getValueAt(0, 8)).isEqualTo("3 / 64");
            assertThat(table(tab, "frontear.store").getRowCount()).isEqualTo(2);
            assertThat(String.valueOf(table(tab, "frontear.store").getValueAt(0, 1))).isEqualTo("40 / 50");
            assertThat(table(tab, "frontear.traffic").getRowCount()).as("five presences in one row").isEqualTo(4);
            assertThat(find(tab, "frontear.node", JLabel.class).getText()).startsWith("node alpha on port 1883");
            assertThat(find(tab, "frontear.counts", JLabel.class).getText()).contains("dropped full 6");
            assertThat(find(tab, "frontear.missed", JLabel.class).getText()).isEqualTo("missed 4");
        });
    }

    /// Five presences of one origin are one row, `Published (5)`; every presence shows the five; the topic filter and
    /// the kind chosen keep only the packets they name.
    @Test
    void theLogCollapsesPresencesAndFiltersByTopicAndKind() throws Exception {
        final FrontearTab tab = shown(new FakeLab());
        SwingUtilities.invokeAndWait(() -> {
            final JTable t = table(tab, "frontear.traffic");
            assertThat(t.getValueAt(0, 1)).isEqualTo("Published (5)");
            find(tab, "frontear.presence", JToggleButton.class).doClick();
            assertThat(t.getRowCount()).isEqualTo(8);
            find(tab, "frontear.presence", JToggleButton.class).doClick();
            find(tab, "frontear.filter", JTextField.class).setText("work/a");
            assertThat(t.getRowCount()).isEqualTo(3);
            find(tab, "frontear.kind", JComboBox.class).setSelectedItem("Sent");
            assertThat(t.getRowCount()).isEqualTo(1);
            assertThat(t.getValueAt(0, 1)).isEqualTo("Sent");
            assertThat(t.getValueAt(0, 2)).as("a step names its link").isEqualTo("beta");
        });
    }

    /// Selecting a publish selects its steps on the links with it and shows its JSON payload beside the log, indented
    /// by two spaces, one field per line.
    @Test
    void selectingAPublishSelectsItsPathAndShowsItsPayloadIndented() throws Exception {
        final FrontearTab tab = shown(new FakeLab());
        SwingUtilities.invokeAndWait(() -> {
            final JTable t = table(tab, "frontear.traffic");
            t.setRowSelectionInterval(1, 1);
            assertThat(t.getSelectedRows()).containsExactly(1, 2, 3);
            final JTextArea head = find(tab, "frontear.head", JTextArea.class);
            assertThat(head.getText().lines().toList()).containsExactly("{", "  \"n\" : 1", "}");
            assertThat(head.getLineWrap()).as("long lines wrap").isTrue();
            assertThat(head.getFont().getFamily()).isEqualTo(Theme.mono().getFamily());
        });
    }

    /// A payload that is not JSON, or JSON cut short, is shown as it came.
    @Test
    void aPayloadThatIsNotJsonIsShownAsItCame() throws Exception {
        final FakeLab lab = new FakeLab();
        lab.packets.add(FakeLab.published(9, 9_000L, "alpha", "work/t", "temperature 21.5 {not json"));
        lab.packets.add(FakeLab.published(10, 9_001L, "alpha", "work/u", "{\"cut\": [1, 2"));
        final FrontearTab tab = shown(lab);
        SwingUtilities.invokeAndWait(() -> {
            final JTable t = table(tab, "frontear.traffic");
            final JTextArea head = find(tab, "frontear.head", JTextArea.class);
            t.setRowSelectionInterval(4, 4);
            assertThat(head.getText()).isEqualTo("temperature 21.5 {not json");
            t.setRowSelectionInterval(5, 5);
            assertThat(head.getText()).isEqualTo("{\"cut\": [1, 2");
        });
    }

    /// A publish whose whole payload the port kept shows all of it, not its head; one without shows its head.
    @Test
    void theWholePayloadIsShownWhereThePortKeptIt() throws Exception {
        final FakeLab lab = new FakeLab();
        lab.packets.add(new Packet(9, 9_000L, Kind.PUBLISHED, "alpha", "", "work/w", 1, false, 30, 0, -1L, "",
                "{\"n\":1,\"tag", "", "{\"n\":1,\"tags\":[\"a\",\"b\"]}"));
        final FrontearTab tab = shown(lab);
        SwingUtilities.invokeAndWait(() -> {
            final JTable t = table(tab, "frontear.traffic");
            final JTextArea head = find(tab, "frontear.head", JTextArea.class);
            t.setRowSelectionInterval(4, 4);
            assertThat(head.getText().lines().toList())
                    .containsExactly("{", "  \"n\" : 1,", "  \"tags\" : [ \"a\", \"b\" ]", "}");
            t.setRowSelectionInterval(1, 1);
            assertThat(head.getText().lines().toList()).as("head only").containsExactly("{", "  \"n\" : 1", "}");
        });
    }

    /// The topics of frontear's own traffic, `$frontear/` and `comando/v1/local/fabric/`, are hidden until the control
    /// toggle shows them; a topic that only holds one of them stays shown.
    @Test
    void controlTrafficIsHiddenUntilTheToggleShowsIt() throws Exception {
        final FakeLab lab = new FakeLab();
        lab.packets.add(FakeLab.published(9, 9_000L, "beta", "$frontear/check/alpha", ""));
        lab.packets.add(FakeLab.published(10, 9_001L, "beta", "comando/v1/local/fabric/state", "{}"));
        lab.packets.add(FakeLab.published(11, 9_002L, "beta", "work/$frontear/x", ""));
        final FrontearTab tab = shown(lab);
        SwingUtilities.invokeAndWait(() -> {
            final JTable t = table(tab, "frontear.traffic");
            final JToggleButton control = find(tab, "frontear.control", JToggleButton.class);
            assertThat(control.isSelected()).as("off by default").isFalse();
            assertThat(control.getText()).isEqualTo("show control");
            assertThat(t.getRowCount()).isEqualTo(5);
            assertThat(t.getValueAt(4, 3)).isEqualTo("work/$frontear/x");
            control.doClick();
            assertThat(t.getRowCount()).isEqualTo(7);
            assertThat(t.getValueAt(4, 3)).isEqualTo("$frontear/check/alpha");
            control.doClick();
            assertThat(t.getRowCount()).isEqualTo(5);
        });
    }

    /// Each column of Overview, Traffic, Octopus and Store has a header tooltip saying what it means, one short line
    /// without a closing period, and the renamed headers read as the store and the octopus mean them.
    @Test
    void everyColumnHeaderSaysWhatItsColumnMeans() throws Exception {
        final FrontearTab tab = shown(new FakeLab());
        SwingUtilities.invokeAndWait(() -> {
            for (final String name : List.of("frontear.overview", "frontear.traffic", "frontear.octopus",
                    "frontear.store")) {
                final JTable t = table(tab, name);
                final JTableHeader header = t.getTableHeader();
                assertThat(t.getColumnCount()).as(name).isPositive();
                for (int i = 0; i < t.getColumnCount(); i++) {
                    final Rectangle r = header.getHeaderRect(i);
                    final String tip = header.getToolTipText(new MouseEvent(header, MouseEvent.MOUSE_MOVED, 0L, 0,
                            r.x + r.width / 2, r.y + r.height / 2, 0, false));
                    final String column = name + " " + t.getColumnName(i);
                    assertThat(tip).as(column).isNotBlank().hasSizeLessThanOrEqualTo(80).doesNotContain("\n");
                    assertThat(tip).as(column).doesNotEndWith(".");
                }
            }
            assertThat(columns(table(tab, "frontear.store"))).startsWith("to peer", "delivered / written")
                    .contains("last force ms", "force p50 ms", "force p99 ms")
                    .doesNotContain("read", "link", "last ms", "p50 ms", "p99 ms");
            assertThat(columns(table(tab, "frontear.octopus"))).startsWith("to peer").contains("waiting to append")
                    .doesNotContain("link", "deliveries");
        });
    }

    /// Each view says in one line above its table what a row is; the store's is that a row is a peer's durable log.
    @Test
    void eachViewSaysWhatARowIs() throws Exception {
        final FrontearTab tab = shown(new FakeLab());
        SwingUtilities.invokeAndWait(() -> {
            for (final String view : List.of("overview", "traffic", "octopus", "store")) {
                final String caption = find(tab, "frontear.caption." + view, JLabel.class).getText();
                assertThat(caption).as(view).startsWith("One ").doesNotContain("\n");
            }
            assertThat(find(tab, "frontear.caption.store", JLabel.class).getText()).isEqualTo(
                    "One durable log per peer: the messages this node keeps until that peer acknowledges them");
        });
    }

    /// Every kind is named in a word, as the kind filter offers it.
    @Test
    void everyKindIsNamedInAWord() {
        assertThat(List.of(Kind.values())).extracting(FrontearTab::kindName).containsExactly("Published", "Appended",
                "Sent", "Arrived", "Dropped", "Connected", "Disconnected");
    }

    /// Paused, a refresh leaves the log as it is; resumed, the packets that came meanwhile join it.
    @Test
    void pausedTheLogStaysUntilResumed() throws Exception {
        final FakeLab lab = new FakeLab();
        final FrontearTab tab = shown(lab);
        SwingUtilities.invokeAndWait(() -> {
            final JToggleButton pause = find(tab, "frontear.pause", JToggleButton.class);
            pause.doClick();
            lab.packets.add(FakeLab.published(9, 9_000L, "alpha", "work/z", ""));
            tab.refresh();
            assertThat(table(tab, "frontear.traffic").getRowCount()).isEqualTo(4);
            pause.doClick();
            assertThat(table(tab, "frontear.traffic").getRowCount()).isEqualTo(5);
        });
    }

    /// The frontear tab sits after Results. Chosen by the user, it asks the state to select it; the state's frontear
    /// tab brings it to the front, and another tab of the state makes it give way.
    @Test
    void theFrontearTabIsALowerTabOfTheState() throws Exception {
        final List<Intent> dispatched = new ArrayList<>();
        final LowerPanel lower = onEdt(() -> new LowerPanel(new WidgetContext(dispatched::add, new ActionRegistry()),
                new ResultRegistry(), new TranscriptRegistry(), new WidgetRegistry()));
        final AppState s = AppState.initial(
                List.of(new PanelState(new PanelId("p"), "p", "", "")), List.of(), List.of());
        SwingUtilities.invokeAndWait(() -> {
            final JTabbedPane tabs = (JTabbedPane) lower.component();
            lower.render(s.withLowerTab(LowerTab.SESSION));
            assertThat(tabs.getTitleAt(LowerPanel.FRONTEAR)).isEqualTo("frontear");
            tabs.setSelectedIndex(LowerPanel.FRONTEAR);
            assertThat(dispatched).containsExactly(new Intent.SelectLowerTab(LowerTab.FRONTEAR));
            lower.render(s.withLowerTab(LowerTab.APPROVALS));
            assertThat(tabs.getSelectedIndex()).isEqualTo(LowerPanel.APPROVALS);
            lower.render(s.withLowerTab(LowerTab.FRONTEAR));
            assertThat(tabs.getSelectedIndex()).isEqualTo(LowerPanel.FRONTEAR);
            assertThat(lower.selectedView()).isEqualTo(-1L);
            assertThat(lower.frontear()).isSameAs(tabs.getComponentAt(LowerPanel.FRONTEAR));
            assertThat(dispatched).as("rendering asks nothing of the state").hasSize(1);
        });
    }

    private static FrontearTab shown(final FakeLab lab) throws Exception {
        final FrontearTab tab = onEdt(FrontearTab::new);
        SwingUtilities.invokeAndWait(() -> {
            tab.lab(lab);
            tab.shown();
        });
        return tab;
    }

    private static List<String> columns(final JTable t) {
        final List<String> names = new ArrayList<>();
        for (int i = 0; i < t.getColumnCount(); i++) {
            names.add(t.getColumnName(i));
        }
        return names;
    }

    private static JTable table(final FrontearTab tab, final String name) {
        return find(tab, name, JTable.class);
    }

    private static <T extends Component> T find(final FrontearTab tab, final String name, final Class<T> type) {
        final T found = find(tab.component(), name, type);
        assertThat(found).as(name).isNotNull();
        return found;
    }

    private static <T extends Component> T find(final Component c, final String name, final Class<T> type) {
        if (type.isInstance(c) && name.equals(c.getName())) {
            return type.cast(c);
        }
        if (c instanceof final Container parent) {
            for (final Component child : parent.getComponents()) {
                final T found = find(child, name, type);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static <T> T onEdt(final java.util.function.Supplier<T> make) throws Exception {
        final List<T> made = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> made.add(make.get()));
        return made.getFirst();
    }

    /// A node `alpha` with two links, `beta` up and `gamma` in backoff, and eight packets: five presences of `beta`,
    /// a publish on `work/a` and its APPENDED and SENT steps on `beta`; three packets lost to the window, one to the
    /// ring.
    private static final class FakeLab implements LabPort {

        private final List<Packet> packets = new ArrayList<>();
        private int opened;

        FakeLab() {
            for (int i = 1; i <= 5; i++) {
                packets.add(published(i, 1_000L * i, "beta", "comando/instances/beta/presence", ""));
            }
            packets.add(published(6, 6_000L, "alpha", "work/a", "{\"n\":1}"));
            packets.add(new Packet(7, 6_001L, Kind.APPENDED, "", "beta", "work/a", -1, false, 7, -1, 70L, "", "", ""));
            packets.add(new Packet(8, 6_002L, Kind.SENT, "", "beta", "work/a", -1, false, 7, -1, 70L, "", "", ""));
        }

        @Override
        public void open() {
            opened++;
        }

        @Override
        public void listen(final Runnable changed) {
        }

        @Override
        public Frame read(final long after, final int max) {
            final List<Packet> out = new ArrayList<>();
            for (final Packet p : packets) {
                if (p.seq() > after && out.size() < max) {
                    out.add(p);
                }
            }
            final Node node = new Node("alpha", 1883, 10_000L, true,
                    new Totals(2, 2, 3, 300L, 1L, 0L, 0L, 0L, 0L),
                    List.of(new Link("beta", true, 0L, 50L, 50L, 8L, 0L, 0L, 3, 64, 2.0, 14.0, 1_000L, 0L, ""),
                            new Link("gamma", false, 5L, 0L, 5L, 0L, 0L, 0L, 0, 64, 0.0, 0.0, 0L, 900L, "refused")),
                    List.of(new Log("beta", 40L, 50L, 4_096L, 1, 4.0, 2, 120L, 0.5, 0.4, 0.9),
                            new Log("gamma", 0L, 5L, 1_024L, 1, 0.0, 0, 0L, 0.0, 0.0, 0.0)),
                    new Counts(0L, 1L, 10L, 6L, 0L));
            final long missed = after == 0L && max > 0 ? 3L : 0L;
            return new Frame(node, out, missed, out.isEmpty() ? after : out.getLast().seq());
        }

        static Packet published(final long seq, final long atMillis, final String origin, final String topic,
                final String head) {
            return new Packet(seq, atMillis, Kind.PUBLISHED, origin, "", topic, 1, true, 7, 0, -1L, "", head, "");
        }
    }
}
