package dev.marregui.comando.ui.panels;

import java.awt.Component;
import java.awt.Container;
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

import org.junit.jupiter.api.Test;

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
import dev.marregui.comando.ui.widget.WidgetContext;
import dev.marregui.comando.ui.widget.WidgetRegistry;

import static org.assertj.core.api.Assertions.assertThat;

/// [FrontearTab], off screen, from a fake [LabPort], and its place in [LowerPanel].
/// - nothing is watched until the tab is shown; then each of the four views is filled
/// - the packet log collapses presences, filters by topic and kind, selects a publish's path and shows its payload
/// - paused, the log stays as it is; the packets lost are counted beside it
/// - the frontear tab stays in front until the state selects another tab
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
            find(tab, "frontear.kind", JComboBox.class).setSelectedItem("SENT");
            assertThat(t.getRowCount()).isEqualTo(1);
            assertThat(t.getValueAt(0, 1)).isEqualTo("SENT");
            assertThat(t.getValueAt(0, 2)).as("a step names its link").isEqualTo("beta");
        });
    }

    /// Selecting a publish selects its steps on the links with it and shows the start of its payload beside the log.
    @Test
    void selectingAPublishSelectsItsPathAndShowsItsPayload() throws Exception {
        final FrontearTab tab = shown(new FakeLab());
        SwingUtilities.invokeAndWait(() -> {
            final JTable t = table(tab, "frontear.traffic");
            t.setRowSelectionInterval(1, 1);
            assertThat(t.getSelectedRows()).containsExactly(1, 2, 3);
            assertThat(find(tab, "frontear.head", JTextArea.class).getText()).isEqualTo("{\"n\":1}");
        });
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

    /// The frontear tab sits after Results; brought forward, it stays while the state's tab stays, and gives way when
    /// the state selects another tab.
    @Test
    void theFrontearTabStaysInFrontUntilTheStateSelectsAnother() throws Exception {
        final LowerPanel lower = onEdt(() -> new LowerPanel(new WidgetContext(_ -> { }, new ActionRegistry()),
                new ResultRegistry(), new TranscriptRegistry(), new WidgetRegistry()));
        final AppState s = AppState.initial(
                List.of(new PanelState(new PanelId("p"), "p", "", "")), List.of(), List.of());
        SwingUtilities.invokeAndWait(() -> {
            final JTabbedPane tabs = (JTabbedPane) lower.component();
            lower.render(s.withLowerTab(LowerTab.SESSION));
            assertThat(tabs.getTitleAt(LowerPanel.FRONTEAR)).isEqualTo("frontear");
            tabs.setSelectedIndex(LowerPanel.FRONTEAR);
            lower.render(s.withLowerTab(LowerTab.SESSION));
            assertThat(tabs.getSelectedIndex()).isEqualTo(LowerPanel.FRONTEAR);
            assertThat(lower.selectedView()).isEqualTo(-1L);
            lower.render(s.withLowerTab(LowerTab.APPROVALS));
            assertThat(tabs.getSelectedIndex()).isEqualTo(LowerPanel.APPROVALS);
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
