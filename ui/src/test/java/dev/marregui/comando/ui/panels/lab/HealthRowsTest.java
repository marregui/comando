package dev.marregui.comando.ui.panels.lab;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.core.port.LabPort.Counts;
import dev.marregui.comando.core.port.LabPort.Link;
import dev.marregui.comando.core.port.LabPort.Log;
import dev.marregui.comando.core.port.LabPort.Node;
import dev.marregui.comando.core.port.LabPort.Totals;
import dev.marregui.comando.ui.panels.lab.HealthRows.Health;
import dev.marregui.comando.ui.panels.lab.HealthRows.Status;

import static org.assertj.core.api.Assertions.assertThat;

/// [HealthRows], the Health view's rows without Swing.
/// - a link that is down sorts first, says how long, and names the step for a local instance or another machine
/// - a full log, a queue growing for 5 s, a drop within a minute and a slow force each have their status
/// - the faults line names only the counters that are not zero; each with its rate over a minute and its total, led
///   by a suspended cdb table whose reason is the tooltip
/// - the detail names every figure of the link and its log
class HealthRowsTest {

    /// A link down for three minutes sorts above one that is ok, reads `down 3m`, and tells to start the instance; a
    /// peer named `name@machine` is on another machine, and its step says to check that machine.
    @Test
    void aDownLinkSortsFirstAndNamesItsStep() {
        final List<Health> rows = new HealthRows().read(node(200_000L,
                link("alice", true, 0L, 0L), link("bob", false, 5L, 0L), link("main@laptop2", false, 0L, 0L)));
        assertThat(rows).extracting(h -> h.link().id()).containsExactly("bob", "main@laptop2", "alice");
        assertThat(rows.getFirst().label()).isEqualTo("down 3m");
        assertThat(rows.getFirst().step()).startsWith("instance bob is not running");
        assertThat(rows.get(1).step()).startsWith("the machine is unreachable");
        assertThat(rows.get(2).status()).isEqualTo(Status.OK);
        assertThat(rows.get(2).step()).isEmpty();
    }

    /// A down link whose backlog grew for 5 minutes says so beside how long it has been down.
    @Test
    void aDownLinkWhoseBacklogGrowsSaysSo() {
        final Link growing = new Link("bob", false, 5L, 0L, 5L, 0L, 0L, 0L, 0, 64, 0.0, 0.0, 1L, 20_000L, "",
                3L, 4L, 1_024L, 4_096L, true);
        assertThat(new HealthRows().read(node(200_000L, growing)).getFirst().label())
                .isEqualTo("down 3m, backlog growing");
    }

    /// A queue that is above what it was 5 s before, while up, is backing up; one that stays level is ok, and the
    /// first read, with no history, is ok too.
    @Test
    void aQueueGrowingForFiveSecondsIsBackingUp() {
        final HealthRows h = new HealthRows();
        assertThat(h.read(node(10_000L, link("bob", true, 2L, 0L))).getFirst().status()).isEqualTo(Status.OK);
        assertThat(h.read(node(12_000L, link("bob", true, 4L, 0L))).getFirst().status()).as("2 s").isEqualTo(Status.OK);
        assertThat(h.read(node(15_000L, link("bob", true, 6L, 0L))).getFirst().label()).isEqualTo("backing up");

        final HealthRows level = new HealthRows();
        level.read(node(10_000L, link("bob", true, 6L, 0L)));
        assertThat(level.read(node(16_000L, link("bob", true, 6L, 0L))).getFirst().status()).isEqualTo(Status.OK);
    }

    /// A drop since the last read flags the link `dropping` for a minute; after that it is ok again.
    @Test
    void aDropFlagsTheLinkForAMinute() {
        final HealthRows h = new HealthRows();
        h.read(node(1_000L, link("bob", true, 0L, 3L)));
        assertThat(h.read(node(2_000L, link("bob", true, 0L, 4L))).getFirst().status()).isEqualTo(Status.DROPPING);
        assertThat(h.read(node(61_000L, link("bob", true, 0L, 4L))).getFirst().status()).isEqualTo(Status.DROPPING);
        assertThat(h.read(node(62_000L, link("bob", true, 0L, 4L))).getFirst().status()).isEqualTo(Status.OK);
    }

    /// A link whose gateway messages wait at the limit is `log full`; a log whose 99th percentile force is over 50 ms
    /// is `slow disk`.
    @Test
    void aFullLogAndASlowForceHaveTheirStatus() {
        final Link full = new Link("bob", true, 0L, 0L, 0L, 0L, 0L, 0L, 64, 64, 0.0, 0.0, 1L, 0L, "");
        assertThat(new HealthRows().read(node(1_000L, full)).getFirst().label()).isEqualTo("log full");
        final Node slow = new Node("main", 18883, 1_000L, true, Totals.NONE, List.of(link("bob", true, 0L, 0L)),
                List.of(new Log("bob", 1L, 1L, 10L, 1, 1.0, 1, 10L, 80.0, 20.0, 51.0)), Counts.NONE);
        assertThat(new HealthRows().read(slow).getFirst().label()).isEqualTo("slow disk");
    }

    /// The faults line holds only the counters that are not zero; a clean node has none.
    @Test
    void theFaultsLineNamesOnlyTheCountersThatAreNotZero() {
        final Node clean = node(1_000L);
        assertThat(HealthRows.faults(clean, 0L)).isEmpty();
        final Node bad = new Node("main", 18883, 1_000L, true, new Totals(1, 1, 0, 0L, 2L, 0L, 0L, 1L, 0L), List.of(),
                List.of(), new Counts(5L, 3L, 9L, 0L, 4L));
        assertThat(HealthRows.faults(bad, 1L)).isEqualTo(
                "dropped full 2 · refused 1 · packets missed 4 · watch window dropped 5 · cdb dropped down 4");
    }

    /// Each fault counter reads its rate over the last minute and its total, the node's clock its only clock: 72 000
    /// packets missed over the first minute read 1.2k a second, still so half a minute on, as the window then starts
    /// at the read half a minute in, which had none; a minute after the last of them, none a second. A total that
    /// went back starts its window again, and the tab's rate of misses and the sink's of samples come with the line.
    @Test
    void eachFaultCounterReadsItsRateOverTheLastMinuteAndItsTotal() {
        final HealthRows h = new HealthRows();
        assertThat(h.faults(counted(0L, 0L), 0L, 0L, "").text()).isEmpty();
        assertThat(h.faults(counted(30_000L, 0L), 0L, 0L, "").text()).isEmpty();
        final HealthRows.Faults minute = h.faults(counted(60_000L, 2_000L), 70_000L, 120_000L, "");
        assertThat(minute.text()).isEqualTo("packets missed 1.2k/s · 72.0k total");
        assertThat(minute.missedPerSecond()).isEqualTo(1_200.0);
        assertThat(minute.sampledPerSecond()).isEqualTo(2_000.0);
        assertThat(h.faults(counted(90_000L, 2_000L), 70_000L, 120_000L, "").text())
                .isEqualTo("packets missed 1.2k/s · 72.0k total");
        final HealthRows.Faults quiet = h.faults(counted(120_000L, 2_000L), 70_000L, 120_000L, "");
        assertThat(quiet.text()).isEqualTo("packets missed 0/s · 72.0k total");
        assertThat(quiet.sampledPerSecond()).isZero();

        final Node many = new Node("main", 18883, 180_000L, true, new Totals(0, 0, 0, 0L, 7_800_000L, 0L, 0L, 0L, 0L),
                List.of(), List.of(), new Counts(0L, 0L, 0L, 0L, 5L));
        assertThat(h.faults(many, 0L, 0L, "").text())
                .isEqualTo("dropped full 130.0k/s · 7.8M total; cdb dropped down 0.1/s · 5 total");
        final Node again = new Node("main", 18883, 240_000L, true, new Totals(0, 0, 0, 0L, 600L, 0L, 0L, 0L, 0L),
                List.of(), List.of(), Counts.NONE);
        assertThat(h.faults(again, 0L, 0L, "").text()).as("a node started again")
                .isEqualTo("dropped full 0/s · 600 total");
    }

    /// While cdb refuses the lab's rows for a table it suspended, the faults line starts `cdb table suspended` and
    /// the tooltip gives cdb's reason; once rows land again the line says no more of it.
    @Test
    void aSuspendedTableLeadsTheFaultsLineWithItsReasonInTheTooltip() {
        final HealthRows h = new HealthRows();
        final String reason = "refuse commit to failed table [table=frontear_packet]";
        final HealthRows.Faults suspended = h.faults(counted(1_000L, 0L), 0L, 0L, reason);
        assertThat(suspended.text()).isEqualTo("cdb table suspended");
        assertThat(suspended.tip()).isEqualTo(reason);
        final Node dropping = new Node("main", 18883, 2_000L, true, Totals.NONE, List.of(), List.of(),
                new Counts(0L, 0L, 0L, 0L, 4L));
        assertThat(h.faults(dropping, 0L, 0L, reason).text())
                .isEqualTo("cdb table suspended; cdb dropped down 4/s · 4 total");
        final HealthRows.Faults landed = h.faults(dropping, 0L, 0L, "");
        assertThat(landed.text()).doesNotContain(HealthRows.SUSPENDED);
        assertThat(landed.tip()).isEmpty();
    }

    /// Counts read in at most four figures and a unit.
    @Test
    void aCountReadsInAtMostFourFiguresAndAUnit() {
        assertThat(HealthRows.compact(0.0)).isEqualTo("0");
        assertThat(HealthRows.compact(0.5)).isEqualTo("0.5");
        assertThat(HealthRows.compact(7.0)).isEqualTo("7");
        assertThat(HealthRows.compact(999.0)).isEqualTo("999");
        assertThat(HealthRows.compact(999.6)).isEqualTo("1.0k");
        assertThat(HealthRows.compact(1_200.0)).isEqualTo("1.2k");
        assertThat(HealthRows.compact(999_960.0)).isEqualTo("1.0M");
        assertThat(HealthRows.compact(7_800_000.0)).isEqualTo("7.8M");
        assertThat(HealthRows.compact(3_000_000_000.0)).isEqualTo("3.0G");
    }

    /// The detail names the link, its status and step, its last reason, and every figure of the link and its log.
    @Test
    void theDetailNamesEveryFigure() {
        final Link down = new Link("bob", false, 5L, 10L, 15L, 8L, 1L, 2L, 3, 64, 0.0, 0.0, 1_000L, 2_000L, "refused");
        final Node n = new Node("main", 18883, 2_500L, true, Totals.NONE, List.of(down),
                List.of(new Log("bob", 10L, 15L, 4_096L, 1, 4.0, 2, 120L, 0.5, 0.4, 0.9)), Counts.NONE);
        final String text = HealthRows.detail(new HealthRows().read(n).getFirst(), at -> "t" + at);
        assertThat(text.lines().toList()).startsWith("bob · down 0s",
                "To resolve: instance bob is not running, or its frontear is off: start it",
                "Last disconnect: refused");
        assertThat(text).contains("queued 5 (cursor 10, tail 15)", "sent 8", "dropped 1", "reordered 2",
                "waiting to append 3 / 64", "last up t1000", "last down t2000", "delivered 10 / written 15",
                "4096 bytes in 1 segments", "fsync 4.0 rounds/s", "p99 0.90 ms");
    }

    /// Durations read in their largest whole unit.
    @Test
    void aDurationReadsInItsLargestWholeUnit() {
        assertThat(HealthRows.duration(45_000L)).isEqualTo("45s");
        assertThat(HealthRows.duration(185_000L)).isEqualTo("3m");
        assertThat(HealthRows.duration(7_300_000L)).isEqualTo("2h");
    }

    private static Node node(final long atMillis, final Link... links) {
        return new Node("main", 18883, atMillis, true, Totals.NONE, List.of(links), List.of(), Counts.NONE);
    }

    /// A node read at `atMillis` whose ring overwrote `ringMissed` packets, and nothing else.
    private static Node counted(final long atMillis, final long ringMissed) {
        return new Node("main", 18883, atMillis, true, Totals.NONE, List.of(), List.of(),
                new Counts(0L, ringMissed, 0L, 0L, 0L));
    }

    private static Link link(final String id, final boolean up, final long queued, final long dropped) {
        return new Link(id, up, queued, 0L, queued, 0L, dropped, 0L, 0, 64, 0.0, 0.0, up ? 1L : 0L,
                up ? 0L : 20_000L, "");
    }
}
